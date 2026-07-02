$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Assert-Condition {
  param(
    [Parameter(Mandatory = $true)][bool]$Condition,
    [Parameter(Mandatory = $true)][string]$Message
  )

  if (-not $Condition) {
    throw $Message
  }
}

<#
  Extracts a top-level job body from the release workflow. The meta-check keeps
  this parser intentionally small because it only protects authority boundaries,
  not the full YAML schema or every implementation detail inside each job.
#>
function Get-JobBlock {
  param(
    [Parameter(Mandatory = $true)][string]$WorkflowText,
    [Parameter(Mandatory = $true)][string]$JobName
  )

  $escapedName = [regex]::Escape($JobName)
  $match = [regex]::Match($WorkflowText, "(?ms)^  ${escapedName}:`r?`n(?<body>.*?)(?=^  [A-Za-z0-9_-]+:`r?`n|\z)")
  Assert-Condition $match.Success "Missing workflow job: $JobName"
  return $match.Groups["body"].Value
}

<#
  Reads only the manual dispatch input surface. Release tags, release names, and
  changelog mode must stay CI-derived, while implementation-specific validation
  belongs in the runtime gate scripts that consume those inputs.
#>
function Get-WorkflowDispatchInputsBlock {
  param([Parameter(Mandatory = $true)][string]$WorkflowText)

  $match = [regex]::Match($WorkflowText, "(?ms)^  workflow_dispatch:`r?`n    inputs:`r?`n(?<body>.*?)(?=^permissions:`r?`n|
^env:`r?`n|^jobs:`r?`n|\z)")
  Assert-Condition $match.Success "Missing workflow_dispatch inputs block."
  return $match.Groups["body"].Value
}

<#
  Checks the permission assigned to a job without coupling this script to runner
  images, Android SDK packages, or script-internal assertions. Those lower-level
  details change more often and should fail in their owning runtime gates.
#>
function Assert-JobPermission {
  param(
    [Parameter(Mandatory = $true)][string]$JobName,
    [Parameter(Mandatory = $true)][string]$JobBlock,
    [Parameter(Mandatory = $true)][string]$Permission
  )

  Assert-Condition ($JobBlock -match "(?ms)permissions:`r?`n      contents: $Permission") "$JobName must use contents: $Permission."
}

<#
  Guards read-job checkout credentials. The publish workflow needs repository
  scripts in read jobs, but the checked-out token must not be persisted into the
  workspace where later build or artifact staging steps could accidentally copy it.
#>
function Assert-ReadJobCheckout {
  param(
    [Parameter(Mandatory = $true)][string]$JobName,
    [Parameter(Mandatory = $true)][string]$JobBlock
  )

  Assert-Condition ($JobBlock -match "uses: actions/checkout") "$JobName must checkout repository sources."
  Assert-Condition ($JobBlock -match "persist-credentials: false") "$JobName checkout must disable persisted credentials."
}

<#
  Keeps write-authority jobs narrow. They may mutate GitHub Releases, so they
  must not checkout source code, run Gradle, inspect signing material, or call
  helper scripts that could grow hidden behavior outside this workflow file.
#>
function Assert-WriteJobBoundary {
  param(
    [Parameter(Mandatory = $true)][string]$JobName,
    [Parameter(Mandatory = $true)][string]$JobBlock
  )

  Assert-JobPermission $JobName $JobBlock "write"
  Assert-Condition ($JobBlock -notmatch "uses: actions/checkout") "$JobName must not checkout repository sources."
  Assert-Condition ($JobBlock -notmatch "\.ps1") "$JobName must keep release mutations inline."
  Assert-Condition ($JobBlock -notmatch "KEYSTORE_|KEY_ALIAS|KEY_PASSWORD|SIGNING_CERTIFICATE|gradlew|assembleRelease|apksigner") "$JobName must not access signing secrets, Gradle, Android build, or APK signing verification."
}

<#
  Allows the remote verification gate to hold write-scoped contents permission
  because GitHub hides draft releases from read-only tokens. The job may run the
  checked-in remote gate script, but it must keep persisted checkout credentials
  disabled and must not perform release mutation, signing, or Android build work.
#>
function Assert-RemoteGateBoundary {
  param(
    [Parameter(Mandatory = $true)][string]$JobName,
    [Parameter(Mandatory = $true)][string]$JobBlock
  )

  Assert-JobPermission $JobName $JobBlock "write"
  Assert-ReadJobCheckout $JobName $JobBlock
  Assert-Condition ($JobBlock -match "release-publish-remote-gate\.ps1") "$JobName must run the remote verification gate script."
  Assert-Condition ($JobBlock -notmatch "release (create|edit|delete|upload)") "$JobName must not mutate GitHub Releases."
  Assert-Condition ($JobBlock -notmatch "KEYSTORE_|KEY_ALIAS|KEY_PASSWORD|SIGNING_CERTIFICATE|gradlew|assembleRelease|apksigner") "$JobName must not access signing secrets, Gradle, Android build, or APK signing verification."
}

$workflowPath = Join-Path (Get-Location) ".github/workflows/release-publish.yml"
$workflow = Get-Content -LiteralPath $workflowPath -Raw
$workflowInputs = Get-WorkflowDispatchInputsBlock $workflow
$changelogScriptPath = Join-Path (Get-Location) ".github/scripts/release-changelog-generate.ps1"
$changelogScript = Get-Content -LiteralPath $changelogScriptPath -Raw
$handoffScriptPath = Join-Path (Get-Location) ".github/scripts/release-handoff-gate.ps1"
$handoffScript = Get-Content -LiteralPath $handoffScriptPath -Raw
$publishPreflightScriptPath = Join-Path (Get-Location) ".github/scripts/release-publish-preflight-gate.ps1"
$publishPreflightScript = Get-Content -LiteralPath $publishPreflightScriptPath -Raw
$versionScriptPath = Join-Path (Get-Location) ".github/scripts/release-version-tag-gate.ps1"
$versionScript = Get-Content -LiteralPath $versionScriptPath -Raw

Assert-Condition ($workflow -match "(?ms)^permissions:`r?`n  contents: read`r?`n") "Workflow-level permissions must stay contents: read."
Assert-Condition ($workflow -notmatch "(?ms)^permissions:`r?`n  contents: write`r?`n") "Workflow-level contents: write is forbidden."
Assert-Condition ($workflowInputs -notmatch "(?m)^      tag_name:") "Manual tag_name input is forbidden."
Assert-Condition ($workflowInputs -notmatch "(?m)^      release_name:") "Manual release_name input is forbidden."
Assert-Condition ($workflowInputs -notmatch "(?m)^      generate_changelog:") "generate_changelog input is forbidden."

$metaJob = Get-JobBlock $workflow "workflow-meta-check"
$sourceRefJob = Get-JobBlock $workflow "source-ref-gate"
$releaseBuildJob = Get-JobBlock $workflow "release-build"
$versionJob = Get-JobBlock $workflow "version-tag-gate"
$changelogGenerateJob = Get-JobBlock $workflow "changelog-generate"
$releaseVerifyJob = Get-JobBlock $workflow "release-verify"
$handoffJob = Get-JobBlock $workflow "handoff-gate"
$publishPreflightJob = Get-JobBlock $workflow "publish-preflight-gate"
$publishDraftJob = Get-JobBlock $workflow "publish-draft"
$publishRemoteJob = Get-JobBlock $workflow "publish-remote-gate"
$publishJob = Get-JobBlock $workflow "publish"
$publishCleanupJob = Get-JobBlock $workflow "publish-cleanup-draft"

$readOnlyJobs = [ordered]@{
  "workflow-meta-check" = $metaJob
  "source-ref-gate" = $sourceRefJob
  "release-build" = $releaseBuildJob
  "version-tag-gate" = $versionJob
  "changelog-generate" = $changelogGenerateJob
  "release-verify" = $releaseVerifyJob
  "handoff-gate" = $handoffJob
  "publish-preflight-gate" = $publishPreflightJob
}

foreach ($entry in $readOnlyJobs.GetEnumerator()) {
  Assert-JobPermission $entry.Key $entry.Value "read"
  Assert-ReadJobCheckout $entry.Key $entry.Value
}
Assert-RemoteGateBoundary "publish-remote-gate" $publishRemoteJob
foreach ($entry in ([ordered]@{
  "publish-draft" = $publishDraftJob
  "publish" = $publishJob
  "publish-cleanup-draft" = $publishCleanupJob
}).GetEnumerator()) {
  Assert-WriteJobBoundary $entry.Key $entry.Value
}

<# High-value release graph checks:
   - source-ref-gate runs before signing material can appear;
   - changelog generation blocks the signed APK build;
   - verify/handoff/publish jobs consume the expected upstream artifacts;
   - publish jobs run only after the handoff gate authorizes publishing;
   - changelog GitHub API endpoints keep PowerShell variable names separated
     from query-string markers before the StrictMode runtime sees them. #>
Assert-Condition ($sourceRefJob -match "release-source-ref-gate\.ps1") "source-ref-gate must run release-source-ref-gate.ps1."
Assert-Condition ($sourceRefJob -match "release-checkout-token-gate\.ps1") "source-ref-gate must run release-checkout-token-gate.ps1."
Assert-Condition ($releaseBuildJob -match "source-ref-gate") "release-build must depend on source-ref-gate before signing secret injection."
Assert-Condition ($releaseBuildJob -match "changelog-generate") "release-build must depend on changelog-generate so changelog generation blocks the signed APK build."
Assert-Condition ($releaseBuildJob -match "release-checkout-token-gate\.ps1") "release-build must run release-checkout-token-gate.ps1."
Assert-Condition ($releaseBuildJob.IndexOf("release-checkout-token-gate.ps1", [StringComparison]::Ordinal) -lt $releaseBuildJob.IndexOf("KEYSTORE_BASE64", [StringComparison]::Ordinal)) "release-build must run checkout token gate before signing secret injection."
Assert-Condition ($releaseBuildJob -match "KEYSTORE_BASE64") "release-build must own signing secret injection."
Assert-Condition ($releaseBuildJob -match "bash ./gradlew assembleRelease") "release-build must build the signed release APK."
Assert-Condition ($releaseBuildJob -match "release-build-artifact-stage\.ps1") "release-build must stage the APK and producer metadata together."
Assert-Condition ($releaseBuildJob -match "oto-release-build") "release-build must upload the combined release build artifact."
Assert-Condition ($versionJob -match "release-version-tag-gate\.ps1") "version-tag-gate must run release-version-tag-gate.ps1."
Assert-Condition ($versionJob -match "release-build-metadata\.json") "version-tag-gate must derive tag metadata from release-build producer metadata."
Assert-Condition ($versionScript -match 'oto-\$channel') "version-tag-gate must generate the prefixed release identity tag."
Assert-Condition (($changelogScript + $publishPreflightScript + $versionScript) -notmatch '\(\?:oto-\)\?') "Release tag parsing must not accept legacy no-prefix tags."
Assert-Condition (($changelogScript + $publishPreflightScript + $versionScript) -match '\^oto-\(stable\|prerelease\)') "Release tag parsing must require the prefixed release identity."
Assert-Condition ($changelogScript -notmatch '\$[A-Za-z_][A-Za-z0-9_]*\?') "release-changelog-generate.ps1 must delimit PowerShell variables before query strings."
Assert-Condition ($changelogGenerateJob -match "release-changelog-generate\.ps1") "changelog-generate must run release-changelog-generate.ps1."
Assert-Condition ($changelogGenerateJob -match "source-ref-gate") "changelog-generate must depend on source-ref-gate for channel resolution."
Assert-Condition ($changelogGenerateJob -notmatch "version-tag-gate") "changelog-generate must not depend on version-tag-gate."
Assert-Condition ($changelogGenerateJob -match "RELEASE_CHANNEL") "changelog-generate must receive the resolved release channel directly."
Assert-Condition ($releaseVerifyJob -match "release-verify-gate\.ps1") "release-verify must run release-verify-gate.ps1."
Assert-Condition ($releaseVerifyJob -match "release-build") "release-verify must depend on release-build."
Assert-Condition ($releaseVerifyJob -match "version-tag-gate") "release-verify must depend on version-tag-gate."
Assert-Condition ($releaseVerifyJob -match "changelog-generate") "release-verify must depend on changelog-generate."
Assert-Condition ($releaseVerifyJob -match "oto-release-build") "release-verify must consume the release build artifact."
Assert-Condition ($releaseVerifyJob -match "oto-release-changelog") "release-verify must consume the changelog artifact."
Assert-Condition ($releaseVerifyJob -match "oto-release-version-metadata") "release-verify must consume version metadata."
Assert-Condition ($releaseVerifyJob -match "release-metadata-verify\.ps1") "release-verify must run release-metadata-verify.ps1."
Assert-Condition ($releaseVerifyJob.IndexOf("release-metadata-verify.ps1", [StringComparison]::Ordinal) -lt $releaseVerifyJob.IndexOf("release-verify-gate.ps1", [StringComparison]::Ordinal)) "release-verify must check producer metadata sources before the final release verify gate."
Assert-Condition ($releaseVerifyJob -match "oto-release-verified") "release-verify must upload the verified release payload."
Assert-Condition ($releaseVerifyJob -match "SIGNING_CERTIFICATE_SHA256") "release-verify must compare producer signing metadata with the configured release certificate."
Assert-Condition ($handoffJob -match "release-handoff-gate\.ps1") "handoff-gate must run release-handoff-gate.ps1."
Assert-Condition ($handoffJob -match "oto-release-verified") "handoff-gate must consume the verified release payload."
Assert-Condition ($handoffJob -match "RELEASE_VERIFIED_DIR") "handoff-gate must receive the verified release payload directory."
Assert-Condition ($handoffJob -notmatch "KEYSTORE_|KEY_ALIAS|KEY_PASSWORD|gradlew|assembleRelease") "handoff-gate must not access signing secrets or build the APK."
Assert-Condition ($handoffScript -match 'versionMetadata\.tagName\)-universal\.apk') "handoff-gate must derive the universal APK asset name from the release tag."
Assert-Condition ($handoffScript -match "manifestSha256sumAssetName") "handoff-gate must publish the oto-update.json sha256sum sidecar."
Assert-Condition ($handoffScript -notmatch '\$apkAssetName\.sha256') "handoff-gate must not publish an APK SHA-256 sidecar asset."
Assert-Condition ($publishPreflightJob -match "release-publish-preflight-gate\.ps1") "publish-preflight-gate must run release-publish-preflight-gate.ps1."
Assert-Condition ($publishRemoteJob -match "release-publish-remote-gate\.ps1") "publish-remote-gate must run release-publish-remote-gate.ps1."
Assert-Condition ($publishPreflightJob -match "needs: handoff-gate") "publish-preflight-gate must wait for handoff-gate."
Assert-Condition ($publishDraftJob -match "publish-preflight-gate") "publish-draft must wait for publish-preflight-gate."
Assert-Condition ($publishRemoteJob -match "publish-draft") "publish-remote-gate must wait for publish-draft."
Assert-Condition ($publishJob -match "publish-remote-gate") "publish must wait for publish-remote-gate."
Assert-Condition ($publishDraftJob -match "gh @createArgs") "publish-draft must create the draft release inline."
Assert-Condition ($publishDraftJob -match "oto-release-draft-state") "publish-draft must persist a draft ownership artifact."
Assert-Condition ($publishDraftJob -match "gh @uploadArgs") "publish-draft must upload release assets inline."
Assert-Condition ($publishJob -match "--draft=false") "publish must finalize the verified draft release inline."
Assert-Condition (($publishDraftJob + $publishRemoteJob + $publishJob + $publishCleanupJob) -notmatch "--latest") "publish write jobs must not actively maintain the GitHub Latest pointer."
Assert-Condition ($publishCleanupJob -match "release delete") "publish-cleanup-draft must remove failed draft releases."
Assert-Condition ($publishCleanupJob -match "oto-release-draft-state") "publish-cleanup-draft must require the draft ownership artifact."

Write-Host "Release workflow authority boundaries passed."
