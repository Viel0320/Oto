$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Get-EnvValue {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [bool]$Required = $true
  )

  $value = [Environment]::GetEnvironmentVariable($Name)
  if ($Required -and [string]::IsNullOrWhiteSpace($value)) {
    throw "Missing required environment variable: $Name"
  }
  return $value
}

function Write-StepOutput {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][string]$Value
  )

  if (-not [string]::IsNullOrWhiteSpace($env:GITHUB_OUTPUT)) {
    Add-Content -LiteralPath $env:GITHUB_OUTPUT -Value "$Name=$Value"
  }
}

function Normalize-Text {
  param([string]$Value)
  if ($null -eq $Value) {
    return ""
  }
  return (($Value -replace "`r`n", "`n") -replace "`r", "`n")
}

function Normalize-Sha256 {
  param([Parameter(Mandatory = $true)][string]$Value)
  return ($Value -replace "[:\s]", "").ToLowerInvariant()
}

function Read-JsonFile {
  param([Parameter(Mandatory = $true)][string]$Path)
  return (Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json)
}

function Write-JsonFile {
  param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)]$Value
  )

  [IO.File]::WriteAllText($Path, (($Value | ConvertTo-Json -Depth 8) + "`n"), [Text.UTF8Encoding]::new($false))
}

function Get-Sha256 {
  param([Parameter(Mandatory = $true)][string]$Path)
  return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-FileSize {
  param([Parameter(Mandatory = $true)][string]$Path)
  return (Get-Item -LiteralPath $Path).Length
}

<#
  Keeps artifact handoff deterministic between CI jobs. The verify gate trusts
  producer jobs as part of the same workflow run, but it still rejects extra
  files, directories, and reparse points so later jobs receive a narrow payload.
#>
function Assert-ExactFileSet {
  param(
    [Parameter(Mandatory = $true)][string]$Directory,
    [Parameter(Mandatory = $true)][string[]]$ExpectedNames,
    [Parameter(Mandatory = $true)][string]$Description
  )

  $items = @(Get-ChildItem -LiteralPath $Directory -Force)
  foreach ($item in $items) {
    if ($item.PSIsContainer) {
      throw "$Description contains a directory: $($item.Name)"
    }
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
      throw "$Description contains a reparse point or symlink: $($item.Name)"
    }
  }

  $actual = @($items | ForEach-Object { $_.Name } | Sort-Object)
  $expected = @($ExpectedNames | Sort-Object)
  if ($null -ne (Compare-Object -ReferenceObject $expected -DifferenceObject $actual)) {
    throw "Unexpected $Description file set. Expected: $($expected -join ', '). Actual: $($actual -join ', ')."
  }
}

<#
  Runs local git checks against the checked-out target commit. Changelog
  generation may be parallelized, but the final verify step still confirms that
  the baseline tag referenced by release metadata is present and ancestral.
#>
function Invoke-CheckedCommand {
  param(
    [Parameter(Mandatory = $true)][string]$FilePath,
    [Parameter(Mandatory = $true)][string[]]$Arguments,
    [Parameter(Mandatory = $true)][string]$FailureMessage
  )

  $output = & $FilePath @Arguments 2>&1
  if ($LASTEXITCODE -ne 0) {
    $joined = ($output | Out-String).Trim()
    throw "$FailureMessage`n$joined"
  }
  return ,@($output)
}

<#
  Release notes are published verbatim, so bare links are constrained to the
  repository release surface. This repeats the producer-side validation at the
  unified handoff boundary where the final files are assembled.
#>
function Assert-AllowedRawLinks {
  param(
    [Parameter(Mandatory = $true)][string]$Value,
    [Parameter(Mandatory = $true)][string]$Description
  )

  $matches = [regex]::Matches($Value, "(?i)\bhttps?://[^\s<>\]\)]+")
  foreach ($match in $matches) {
    $url = $match.Value.TrimEnd([char[]]".,;:!?")
    if ($url -notmatch "^https://github\.com/[^/\s]+/[^/\s]+/releases(?:$|/tag/[^/\s]+$|/download/[^/\s]+/[^/\s]+$)") {
      throw "$Description contains an unsupported raw link: $url"
    }
  }
}

<#
  Checks generated changelog headings without applying those taxonomy rules to
  manual changelogs. Manual changelogs are an explicit maintainer artifact,
  while generated ones must stay in the release-plan category order.
#>
function Assert-GeneratedChangelogCategories {
  param([Parameter(Mandatory = $true)][string]$Value)

  $headings = @(
    "### New Features",
    "### Improvements",
    "### Fixes",
    "### Changes"
  )
  $lastIndex = -1
  foreach ($match in [regex]::Matches($Value, "(?m)^### (?<name>.+)$")) {
    $heading = "### $($match.Groups["name"].Value)"
    $order = [Array]::IndexOf($headings, $heading)
    if ($order -lt 0) {
      throw "Generated changelog contains a category heading outside the release plan taxonomy: $heading"
    }
    if ($order -lt $lastIndex) {
      throw "Generated changelog category headings are not in the required order."
    }
    $lastIndex = $order
  }
}

$buildDir = Get-EnvValue "RELEASE_BUILD_ARTIFACT_DIR"
$changelogDir = Get-EnvValue "RELEASE_CHANGELOG_DIR"
$versionMetadataPath = Get-EnvValue "RELEASE_VERSION_METADATA_PATH"
$outputDir = Get-EnvValue "RELEASE_VERIFIED_OUTPUT_DIR"
$expectedSigningSha256 = Normalize-Sha256 (Get-EnvValue "RELEASE_EXPECTED_SIGNING_CERT_SHA256")

if ($expectedSigningSha256 -notmatch "^[0-9a-f]{64}$") {
  throw "RELEASE_EXPECTED_SIGNING_CERT_SHA256 must be a 64-character SHA-256 hex digest."
}
if (Test-Path -LiteralPath $outputDir) {
  $existingOutput = @(Get-ChildItem -LiteralPath $outputDir -Force)
  if ($existingOutput.Count -gt 0) {
    throw "Release verify output directory must be empty: $outputDir"
  }
} else {
  New-Item -ItemType Directory -Path $outputDir | Out-Null
}

Assert-ExactFileSet $buildDir @("release.apk", "release-build-metadata.json") "release build artifact"
Assert-ExactFileSet $changelogDir @("CHANGELOG.md", "release-body.md", "release-changelog.full.md", "release-changelog-metadata.json") "release changelog artifact"

$buildMetadata = Read-JsonFile (Join-Path $buildDir "release-build-metadata.json")
$changelogMetadata = Read-JsonFile (Join-Path $changelogDir "release-changelog-metadata.json")
$versionMetadata = Read-JsonFile $versionMetadataPath
$currentRunId = Get-EnvValue "GITHUB_RUN_ID"
$currentRunAttempt = Get-EnvValue "GITHUB_RUN_ATTEMPT" $false
$targetCommit = Get-EnvValue "GITHUB_SHA"

if ([int]$buildMetadata.schemaVersion -ne 1 -or $buildMetadata.artifactKind -ne "release-build") {
  throw "Release build metadata has an unsupported schema."
}
if ($buildMetadata.apkFileName -ne "release.apk") {
  throw "Release build metadata must describe release.apk."
}
if ($buildMetadata.createdByRunId -ne $currentRunId -or $buildMetadata.targetCommit -ne $targetCommit) {
  throw "Release build metadata was not produced by this workflow run."
}

$apkPath = Join-Path $buildDir "release.apk"
if ((Get-Sha256 $apkPath) -ne $buildMetadata.apkSha256) {
  throw "Release APK hash does not match producer metadata."
}
if ((Get-FileSize $apkPath) -ne [int64]$buildMetadata.apkSizeBytes) {
  throw "Release APK size does not match producer metadata."
}

if ($buildMetadata.packageName -ne $versionMetadata.packageName -or
    [int]$buildMetadata.versionCode -ne [int]$versionMetadata.versionCode -or
    $buildMetadata.versionName -ne $versionMetadata.versionName -or
    [int]$buildMetadata.minSdk -ne [int]$versionMetadata.minSdk -or
    [int]$buildMetadata.targetSdk -ne [int]$versionMetadata.targetSdk) {
  throw "Release build metadata and version metadata disagree."
}

$actualSigningSha256 = Normalize-Sha256 ([string]$buildMetadata.signingCertificateSha256)
if ($actualSigningSha256 -ne $expectedSigningSha256) {
  throw "APK signing certificate SHA-256 does not match expected certificate."
}

$denylistRaw = Get-EnvValue "RELEASE_DEBUG_SIGNING_CERT_SHA256_DENYLIST" $false
if (-not [string]::IsNullOrWhiteSpace($denylistRaw)) {
  $deniedHashes = @(
    $denylistRaw -split "[,;\s]+" |
      ForEach-Object { Normalize-Sha256 $_ } |
      Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
  )
  foreach ($deniedHash in $deniedHashes) {
    if ($deniedHash -notmatch "^[0-9a-f]{64}$") {
      throw "RELEASE_DEBUG_SIGNING_CERT_SHA256_DENYLIST contains an invalid SHA-256 digest."
    }
    if ($actualSigningSha256 -eq $deniedHash) {
      throw "APK signing certificate SHA-256 is in the debug certificate denylist."
    }
  }
}

if ([int]$changelogMetadata.schemaVersion -ne 1 -or $changelogMetadata.artifactKind -ne "release-changelog") {
  throw "Release changelog metadata has an unsupported schema."
}
if ($changelogMetadata.createdByRunId -ne $currentRunId -or $changelogMetadata.targetCommit -ne $targetCommit) {
  throw "Release changelog metadata was not produced by this workflow run."
}
if ($changelogMetadata.channel -ne $versionMetadata.channel) {
  throw "Release changelog metadata channel does not match version metadata."
}
if ([bool]$changelogMetadata.isStableManifestBootstrap -ne [bool]$versionMetadata.isStableManifestBootstrap) {
  throw "Release changelog bootstrap state does not match version metadata."
}
if ($changelogMetadata.source -ne "manual" -and $changelogMetadata.source -ne "generated") {
  throw "Release changelog metadata has an invalid source."
}
if ([bool]$versionMetadata.isStableManifestBootstrap -and $changelogMetadata.source -ne "manual") {
  throw "Stable manifest bootstrap requires manual_changelog."
}

$changelogPath = Join-Path $changelogDir "CHANGELOG.md"
$releaseBodyPath = Join-Path $changelogDir "release-body.md"
if ((Get-Sha256 $changelogPath) -ne $changelogMetadata.changelogSha256) {
  throw "CHANGELOG.md hash does not match producer metadata."
}
if ((Get-FileSize $changelogPath) -ne [int64]$changelogMetadata.changelogSizeBytes) {
  throw "CHANGELOG.md size does not match producer metadata."
}
if ((Get-Sha256 $releaseBodyPath) -ne $changelogMetadata.releaseBodySha256) {
  throw "release-body.md hash does not match producer metadata."
}
if ((Get-FileSize $releaseBodyPath) -ne [int64]$changelogMetadata.releaseBodySizeBytes) {
  throw "release-body.md size does not match producer metadata."
}

$changelog = Normalize-Text (Get-Content -LiteralPath $changelogPath -Raw)
$releaseBody = Normalize-Text (Get-Content -LiteralPath $releaseBodyPath -Raw)
if ([string]::IsNullOrWhiteSpace($changelog)) {
  throw "CHANGELOG.md must not be empty."
}
if ($changelog -ne $releaseBody) {
  throw "Release body does not match CHANGELOG.md."
}
if ($changelog.Length -gt 125000) {
  throw "GitHub Release body exceeds the platform length limit."
}
Assert-AllowedRawLinks $changelog "CHANGELOG.md"

if (-not [bool]$versionMetadata.isStableManifestBootstrap) {
  $previousStableTag = [string]$versionMetadata.previousStableTag
  if ([string]::IsNullOrWhiteSpace($previousStableTag)) {
    throw "Previous stable release tag is required after stable manifest bootstrap."
  }
  Invoke-CheckedCommand "git" @("rev-parse", "--verify", "refs/tags/$previousStableTag^{commit}") "GitHub Latest stable release tag '$previousStableTag' is missing from checkout." | Out-Null
  Invoke-CheckedCommand "git" @("merge-base", "--is-ancestor", "refs/tags/$previousStableTag^{commit}", $targetCommit) "GitHub Latest stable release tag '$previousStableTag' is not an ancestor of the current release commit." | Out-Null

  if ($changelogMetadata.source -eq "generated" -and $changelogMetadata.previousStableTag -ne $previousStableTag) {
    throw "Generated changelog baseline does not match version metadata."
  }
}

if ($changelogMetadata.source -eq "generated") {
  Assert-GeneratedChangelogCategories $changelog
  if ($changelog -match "(?m)^### (Dependencies, Build, And Release|Docs & Translations|Documentation|Tests|Internal Maintenance|Other Changes)$") {
    throw "Generated changelog contains an internal-only category heading."
  }
}

$apkMetadata = [ordered]@{
  packageName = $buildMetadata.packageName
  versionCode = [string]$buildMetadata.versionCode
  versionName = $buildMetadata.versionName
  minSdk = [string]$buildMetadata.minSdk
  targetSdk = [string]$buildMetadata.targetSdk
}
$signingMetadata = [ordered]@{
  signingCertificateSha256 = $actualSigningSha256
}
$verifyMetadata = [ordered]@{
  schemaVersion = 1
  artifactKind = "release-verify"
  tagName = $versionMetadata.tagName
  releaseName = $versionMetadata.releaseName
  targetCommit = $targetCommit
  channel = $versionMetadata.channel
  apkSha256 = $buildMetadata.apkSha256
  apkSizeBytes = [int64]$buildMetadata.apkSizeBytes
  changelogSha256 = $changelogMetadata.changelogSha256
  changelogSizeBytes = [int64]$changelogMetadata.changelogSizeBytes
  signingCertificateSha256 = $actualSigningSha256
  changelogSource = $changelogMetadata.source
  createdByRunId = $currentRunId
  createdByRunAttempt = $currentRunAttempt
}

Copy-Item -LiteralPath $apkPath -Destination (Join-Path $outputDir "release.apk") -Force
Copy-Item -LiteralPath $changelogPath -Destination (Join-Path $outputDir "CHANGELOG.md") -Force
Copy-Item -LiteralPath $releaseBodyPath -Destination (Join-Path $outputDir "release-body.md") -Force
Copy-Item -LiteralPath (Join-Path $buildDir "release-build-metadata.json") -Destination (Join-Path $outputDir "release-build-metadata.json") -Force
Copy-Item -LiteralPath (Join-Path $changelogDir "release-changelog-metadata.json") -Destination (Join-Path $outputDir "release-changelog-metadata.json") -Force
Write-JsonFile (Join-Path $outputDir "release-apk-metadata.json") $apkMetadata
Write-JsonFile (Join-Path $outputDir "release-signing-metadata.json") $signingMetadata
Write-JsonFile (Join-Path $outputDir "release-verify-metadata.json") $verifyMetadata

Assert-ExactFileSet $outputDir @(
  "release.apk",
  "release-apk-metadata.json",
  "release-build-metadata.json",
  "release-changelog-metadata.json",
  "release-signing-metadata.json",
  "release-verify-metadata.json",
  "CHANGELOG.md",
  "release-body.md"
) "verified release payload"

Write-StepOutput "verified_dir" $outputDir
Write-Host "Release verify gate passed for $($versionMetadata.tagName)."
