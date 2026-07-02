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

function Get-WorkflowDispatchInputsBlock {
  param([Parameter(Mandatory = $true)][string]$WorkflowText)

  $match = [regex]::Match($WorkflowText, "(?ms)^  workflow_dispatch:`r?`n    inputs:`r?`n(?<body>.*?)(?=^permissions:`r?`n|
^env:`r?`n|^jobs:`r?`n|\z)")
  Assert-Condition $match.Success "Missing workflow_dispatch inputs block."
  return $match.Groups["body"].Value
}

$workflowPath = Join-Path (Get-Location) ".github/workflows/release-publish.yml"
$workflow = Get-Content -LiteralPath $workflowPath -Raw
$workflowInputs = Get-WorkflowDispatchInputsBlock $workflow
$versionTagGateScript = Get-Content -LiteralPath (Join-Path (Get-Location) ".github/scripts/release-version-tag-gate.ps1") -Raw
$changelogGenerateScript = Get-Content -LiteralPath (Join-Path (Get-Location) ".github/scripts/release-changelog-generate.ps1") -Raw
$metadataVerifyScript = Get-Content -LiteralPath (Join-Path (Get-Location) ".github/scripts/release-metadata-verify.ps1") -Raw

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
  "publish-remote-gate" = $publishRemoteJob
}

foreach ($entry in $readOnlyJobs.GetEnumerator()) {
  Assert-Condition ($entry.Value -match "runs-on: ubuntu-latest") "$($entry.Key) must run on ubuntu-latest."
  Assert-Condition ($entry.Value -match "(?ms)permissions:`r?`n      contents: read") "$($entry.Key) must use contents: read."
  Assert-Condition ($entry.Value -match "uses: actions/checkout") "$($entry.Key) must checkout repository sources."
  Assert-Condition ($entry.Value -match "persist-credentials: false") "$($entry.Key) checkout must disable persisted credentials."
}
foreach ($entry in ([ordered]@{
  "publish-draft" = $publishDraftJob
  "publish" = $publishJob
  "publish-cleanup-draft" = $publishCleanupJob
}).GetEnumerator()) {
  Assert-Condition ($entry.Value -match "runs-on: ubuntu-latest") "$($entry.Key) must run on ubuntu-latest."
  Assert-Condition ($entry.Value -match "(?ms)permissions:`r?`n      contents: write") "$($entry.Key) must use contents: write."
  Assert-Condition ($entry.Value -notmatch "uses: actions/checkout") "$($entry.Key) must not checkout repository sources."
}

Assert-Condition ($sourceRefJob -match "release-source-ref-gate\.ps1") "source-ref-gate must run release-source-ref-gate.ps1."
Assert-Condition ($sourceRefJob -match "release-checkout-token-gate\.ps1") "source-ref-gate must run release-checkout-token-gate.ps1."
Assert-Condition ($releaseBuildJob -match "source-ref-gate") "release-build must depend on source-ref-gate before signing secret injection."
Assert-Condition ($releaseBuildJob -match "changelog-generate") "release-build must depend on changelog-generate so changelog generation blocks the signed APK build."
Assert-Condition ($releaseBuildJob -match "release-checkout-token-gate\.ps1") "release-build must run release-checkout-token-gate.ps1."
Assert-Condition ($releaseBuildJob.IndexOf("release-checkout-token-gate.ps1", [StringComparison]::Ordinal) -lt $releaseBuildJob.IndexOf("KEYSTORE_BASE64", [StringComparison]::Ordinal)) "release-build must run checkout token gate before signing secret injection."
Assert-Condition ($releaseBuildJob -match "KEYSTORE_BASE64") "release-build must own signing secret injection."
Assert-Condition ($releaseBuildJob -match "Missing required release signing secret") "release-build must fail when release signing secrets are incomplete."
Assert-Condition ($releaseBuildJob -match "bash ./gradlew assembleRelease") "release-build must build the signed release APK."
Assert-Condition ($releaseBuildJob -match "android-actions/setup-android@v3") "release-build must set up Android SDK tools for producer metadata."
Assert-Condition ($releaseBuildJob -match 'sdkmanager "build-tools;36\.0\.0" "platforms;android-36" "platforms;android-37"') "release-build must install Android build tools and compile SDK platform for producer metadata."
Assert-Condition ($releaseBuildJob -match "release-build-artifact-stage\.ps1") "release-build must stage the APK and producer metadata together."
Assert-Condition ($releaseBuildJob -match "oto-release-build") "release-build must upload the combined release build artifact."
Assert-Condition ($versionJob -match "release-version-tag-gate\.ps1") "version-tag-gate must run release-version-tag-gate.ps1."
Assert-Condition ($versionJob -match "release-build-metadata\.json") "version-tag-gate must derive tag metadata from release-build producer metadata."
Assert-Condition ($versionTagGateScript -match "releases/latest") "version-tag-gate must use GitHub Latest release as the stable changelog baseline."
Assert-Condition ($versionTagGateScript -notmatch "Sort-Object CreatedAt -Descending \| Select-Object -First 1") "version-tag-gate must not derive the stable changelog baseline by release-list ordering."
Assert-Condition ($versionTagGateScript -match "isStableManifestBootstrap") "version-tag-gate must expose stable manifest bootstrap metadata."
Assert-Condition ($versionTagGateScript -notmatch [regex]::Escape("^.+\((\d+)\)$")) "version-tag-gate must not parse legacy stable tags for bootstrap floor."
Assert-Condition ($changelogGenerateJob -match "release-changelog-generate\.ps1") "changelog-generate must run release-changelog-generate.ps1."
Assert-Condition ($changelogGenerateJob -match "source-ref-gate") "changelog-generate must depend on source-ref-gate for channel resolution."
Assert-Condition ($changelogGenerateJob -notmatch "version-tag-gate") "changelog-generate must not depend on version-tag-gate."
Assert-Condition ($changelogGenerateJob -match "RELEASE_CHANNEL") "changelog-generate must receive the resolved release channel directly."
Assert-Condition ($changelogGenerateScript -match "release-changelog-metadata\.json") "changelog-generate must produce changelog producer metadata."
Assert-Condition ($releaseVerifyJob -match "release-verify-gate\.ps1") "release-verify must run release-verify-gate.ps1."
Assert-Condition ($releaseVerifyJob -match "release-build") "release-verify must depend on release-build."
Assert-Condition ($releaseVerifyJob -match "version-tag-gate") "release-verify must depend on version-tag-gate."
Assert-Condition ($releaseVerifyJob -match "changelog-generate") "release-verify must depend on changelog-generate."
Assert-Condition ($releaseVerifyJob -match "oto-release-build") "release-verify must consume the release build artifact."
Assert-Condition ($releaseVerifyJob -match "oto-release-changelog") "release-verify must consume the changelog artifact."
Assert-Condition ($releaseVerifyJob -match "oto-release-version-metadata") "release-verify must consume version metadata."
Assert-Condition ($releaseVerifyJob -match "android-actions/setup-android@v3") "release-verify must set up Android SDK tools for producer metadata source verification."
Assert-Condition ($releaseVerifyJob -match 'sdkmanager "build-tools;36\.0\.0" "platforms;android-36" "platforms;android-37"') "release-verify must install Android build tools for producer metadata source verification."
Assert-Condition ($releaseVerifyJob -match "release-metadata-verify\.ps1") "release-verify must run release-metadata-verify.ps1."
Assert-Condition ($releaseVerifyJob.IndexOf("release-metadata-verify.ps1", [StringComparison]::Ordinal) -lt $releaseVerifyJob.IndexOf("release-verify-gate.ps1", [StringComparison]::Ordinal)) "release-verify must check producer metadata sources before the final release verify gate."
Assert-Condition ($releaseVerifyJob -match "oto-release-verified") "release-verify must upload the verified release payload."
Assert-Condition ($releaseVerifyJob -match "SIGNING_CERTIFICATE_SHA256") "release-verify must compare producer signing metadata with the configured release certificate."
Assert-Condition ($metadataVerifyScript -match "release-build-metadata\.json" -and $metadataVerifyScript -match "release-changelog-metadata\.json") "metadata verify must check both producer metadata files."
Assert-Condition ($metadataVerifyScript -match "apksigner" -and $metadataVerifyScript -match "Read-BadgingOutput") "metadata verify must re-read APK metadata and signing facts from the APK source file."
Assert-Condition ($metadataVerifyScript -match "release-changelog\.full\.md" -and $metadataVerifyScript -match "bodyLengthCharacters") "metadata verify must re-check changelog metadata against generated Markdown files."
Assert-Condition ($changelogGenerateScript -match "isStableManifestBootstrap" -and $changelogGenerateScript -match "Stable manifest bootstrap requires manual_changelog") "changelog-generate must require manual_changelog during stable manifest bootstrap."
Assert-Condition ($workflow -notmatch "(?m)^  (apk-file-gate|apk-metadata-gate|signing-gate|changelog-gate):") "Legacy split artifact gates must not be reintroduced."
Assert-Condition ($workflow -notmatch "release-apk-file-gate\.ps1|release-apk-metadata-gate\.ps1|release-signing-gate\.ps1|release-changelog-gate\.ps1") "Release workflow must use producer metadata plus release-verify instead of legacy gate scripts."
Assert-Condition ($handoffJob -match "release-handoff-gate\.ps1") "handoff-gate must run release-handoff-gate.ps1."
Assert-Condition ($handoffJob -match "oto-release-verified") "handoff-gate must consume the verified release payload."
Assert-Condition ($handoffJob -match "RELEASE_VERIFIED_DIR") "handoff-gate must receive the verified release payload directory."
Assert-Condition ($publishPreflightJob -match "release-publish-preflight-gate\.ps1") "publish-preflight-gate must run release-publish-preflight-gate.ps1."
Assert-Condition ($publishRemoteJob -match "release-publish-remote-gate\.ps1") "publish-remote-gate must run release-publish-remote-gate.ps1."
Assert-Condition ($publishDraftJob -match "gh @createArgs") "publish-draft must create the draft release inline."
Assert-Condition ($publishDraftJob -match "oto-release-draft-state") "publish-draft must persist a draft ownership artifact."
Assert-Condition ($publishDraftJob -match 'createdRelease = \$true') "publish-draft must record that this run created the draft release."
Assert-Condition ($publishDraftJob -match "releaseDatabaseId") "publish-draft must record the GitHub Release database id for cleanup ownership."
Assert-Condition ($publishDraftJob -match "releaseNodeId") "publish-draft must record the GitHub Release node id for cleanup ownership."
Assert-Condition ($publishDraftJob -match "gh @uploadArgs") "publish-draft must upload release assets inline."
Assert-Condition ($publishJob -match "--draft=false") "publish must finalize the verified draft release inline."
Assert-Condition (($publishDraftJob + $publishJob + $publishCleanupJob) -notmatch "--latest") "publish write jobs must not actively maintain the GitHub Latest pointer."
Assert-Condition ($publishCleanupJob -match "release delete") "publish-cleanup-draft must remove failed draft releases."
Assert-Condition ($publishCleanupJob -match "oto-release-draft-state") "publish-cleanup-draft must require the draft ownership artifact."
Assert-Condition ($publishCleanupJob -match "releaseDatabaseId") "publish-cleanup-draft must match the remote release id before cleanup."
Assert-Condition ($publishCleanupJob -match "releaseNodeId") "publish-cleanup-draft must match the remote release node id before cleanup."
Assert-Condition ($publishCleanupJob -match "No draft ownership record found") "publish-cleanup-draft must no-op when no draft ownership record exists."

Assert-Condition ($workflow -notmatch "release-build-verify\.ps1") "release-build-verify.ps1 must not be used by the workflow."
Assert-Condition ($workflow -notmatch "release-publish-transaction\.ps1") "release-publish-transaction.ps1 must not be used by the workflow."
Assert-Condition (($publishDraftJob + $publishJob + $publishCleanupJob) -notmatch "\.ps1") "publish write jobs must not call helper scripts."
Assert-Condition (($publishDraftJob + $publishJob + $publishCleanupJob) -notmatch "KEYSTORE_|KEY_ALIAS|KEY_PASSWORD|SIGNING_CERTIFICATE|gradlew|assembleRelease|apksigner") "publish write jobs must not access signing secrets, Gradle, Android build, or APK signing verification."
Assert-Condition ($handoffJob -notmatch "KEYSTORE_|KEY_ALIAS|KEY_PASSWORD|gradlew|assembleRelease") "handoff-gate must not access signing secrets or build the APK."

Write-Host "Release workflow static invariants passed."
