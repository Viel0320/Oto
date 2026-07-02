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

function Test-Truthy {
  param([string]$Value)
  return [string]::Equals($Value, "true", [StringComparison]::OrdinalIgnoreCase)
}

function Normalize-Text {
  param([string]$Value)
  if ($null -eq $Value) {
    return ""
  }
  return (($Value -replace "`r`n", "`n") -replace "`r", "`n")
}

function Write-Utf8NoBomFile {
  param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)][string]$Content
  )

  [IO.File]::WriteAllText($Path, $Content, [Text.UTF8Encoding]::new($false))
}

function Read-JsonFile {
  param([Parameter(Mandatory = $true)][string]$Path)
  return (Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json)
}

function Get-Sha256 {
  param([Parameter(Mandatory = $true)][string]$Path)
  return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-FileSize {
  param([Parameter(Mandatory = $true)][string]$Path)
  return (Get-Item -LiteralPath $Path).Length
}

function Assert-ExactFileSet {
  param(
    [Parameter(Mandatory = $true)][string]$Directory,
    [Parameter(Mandatory = $true)][string[]]$ExpectedNames
  )

  $items = @(Get-ChildItem -LiteralPath $Directory -Force)
  foreach ($item in $items) {
    if ($item.PSIsContainer) {
      throw "Handoff artifact contains a directory: $($item.Name)"
    }
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
      throw "Handoff artifact contains a reparse point or symlink: $($item.Name)"
    }
  }

  $actual = @($items | ForEach-Object { $_.Name } | Sort-Object)
  $expected = @($ExpectedNames | Sort-Object)
  $diff = Compare-Object -ReferenceObject $expected -DifferenceObject $actual
  if ($null -ne $diff) {
    throw "Unexpected handoff file set. Expected: $($expected -join ', '). Actual: $($actual -join ', ')."
  }
}

function Assert-ManifestMatchesMetadata {
  param(
    [Parameter(Mandatory = $true)]$Manifest,
    [Parameter(Mandatory = $true)]$Metadata
  )

  if ([int]$Manifest.schemaVersion -ne 1 -or
      $Manifest.packageName -ne $Metadata.packageName -or
      [int]$Manifest.versionCode -ne [int]$Metadata.versionCode -or
      $Manifest.versionName -ne $Metadata.versionName -or
      $Manifest.channel -ne $Metadata.channel -or
      $Manifest.releaseDate -ne $Metadata.releaseDate -or
      [int]$Manifest.minSdk -ne [int]$Metadata.minSdk -or
      [int]$Manifest.targetSdk -ne [int]$Metadata.targetSdk -or
      $Manifest.apkAssetName -ne $Metadata.apkAssetName -or
      $Manifest.apkSha256 -ne $Metadata.apkSha256 -or
      [int64]$Manifest.apkSizeBytes -ne [int64]$Metadata.apkSizeBytes -or
      $Manifest.signingCertificateSha256 -ne $Metadata.signingCertificateSha256 -or
      $Manifest.changelogAssetName -ne $Metadata.changelogAssetName -or
      $Manifest.changelogSha256 -ne $Metadata.changelogSha256 -or
      [int64]$Manifest.changelogSizeBytes -ne [int64]$Metadata.changelogSizeBytes -or
      $Manifest.changelogFormat -ne "markdown" -or
      $Manifest.changelogFormat -ne $Metadata.changelogFormat -or
      $Manifest.fullChangelogUrl -ne $Metadata.fullChangelogUrl) {
    throw "oto-update.json does not match handoff metadata."
  }
}

$verifiedDir = Get-EnvValue "RELEASE_VERIFIED_DIR"
$versionMetadata = Read-JsonFile (Get-EnvValue "RELEASE_VERSION_METADATA_PATH")
$handoffDir = Get-EnvValue "RELEASE_HANDOFF_DIR"
$publish = Test-Truthy (Get-EnvValue "RELEASE_PUBLISH")

# Verified Payload Boundary
# Handoff consumes one already-verified payload instead of rebuilding the same
# checks from separate APK, signing, and changelog artifacts.
Assert-ExactFileSet $verifiedDir @(
  "release.apk",
  "release-apk-metadata.json",
  "release-build-metadata.json",
  "release-changelog-metadata.json",
  "release-signing-metadata.json",
  "release-verify-metadata.json",
  "CHANGELOG.md",
  "release-body.md"
)

$apkPath = Join-Path $verifiedDir "release.apk"
$apkMetadata = Read-JsonFile (Join-Path $verifiedDir "release-apk-metadata.json")
$signingMetadata = Read-JsonFile (Join-Path $verifiedDir "release-signing-metadata.json")
$verifyMetadata = Read-JsonFile (Join-Path $verifiedDir "release-verify-metadata.json")
$changelogDir = $verifiedDir

if ($verifyMetadata.createdByRunId -ne (Get-EnvValue "GITHUB_RUN_ID") -or
    $verifyMetadata.tagName -ne $versionMetadata.tagName -or
    $verifyMetadata.releaseName -ne $versionMetadata.releaseName -or
    $verifyMetadata.targetCommit -ne $versionMetadata.targetCommit) {
  throw "Verified release payload does not match version metadata."
}

if (-not (Test-Path -LiteralPath $apkPath)) {
  throw "APK does not exist: $apkPath"
}

if ($apkMetadata.packageName -ne $versionMetadata.packageName -or
    [int]$apkMetadata.versionCode -ne [int]$versionMetadata.versionCode -or
    $apkMetadata.versionName -ne $versionMetadata.versionName -or
    [int]$apkMetadata.minSdk -ne [int]$versionMetadata.minSdk -or
    [int]$apkMetadata.targetSdk -ne [int]$versionMetadata.targetSdk) {
  throw "APK metadata and version metadata disagree."
}

if (Test-Path -LiteralPath $handoffDir) {
  Remove-Item -LiteralPath $handoffDir -Recurse -Force
}
New-Item -ItemType Directory -Path $handoffDir | Out-Null

$apkAssetName = "oto-$($versionMetadata.channel)-$($versionMetadata.versionName)-$($versionMetadata.versionCode)-universal.apk"
$stagedApk = Join-Path $handoffDir $apkAssetName
Copy-Item -LiteralPath $apkPath -Destination $stagedApk
$apkSha256 = Get-Sha256 $stagedApk
$apkSize = Get-FileSize $stagedApk
$shaAssetName = "$apkAssetName.sha256"
Write-Utf8NoBomFile (Join-Path $handoffDir $shaAssetName) "$apkSha256  $apkAssetName`n"

$changelogPath = Join-Path $changelogDir "CHANGELOG.md"
$releaseBodyPath = Join-Path $changelogDir "release-body.md"
if (-not (Test-Path -LiteralPath $changelogPath) -or -not (Test-Path -LiteralPath $releaseBodyPath)) {
  throw "Changelog gate output is incomplete."
}
Copy-Item -LiteralPath $changelogPath -Destination (Join-Path $handoffDir "CHANGELOG.md")
Copy-Item -LiteralPath $releaseBodyPath -Destination (Join-Path $handoffDir "release-body.md")

$stagedChangelogPath = Join-Path $handoffDir "CHANGELOG.md"
$stagedReleaseBodyPath = Join-Path $handoffDir "release-body.md"
$changelogSha256 = Get-Sha256 $stagedChangelogPath
$changelogSize = Get-FileSize $stagedChangelogPath
if ((Normalize-Text (Get-Content -LiteralPath $stagedChangelogPath -Raw)) -ne (Normalize-Text (Get-Content -LiteralPath $stagedReleaseBodyPath -Raw))) {
  throw "Release body does not match CHANGELOG.md."
}

$repo = Get-EnvValue "GITHUB_REPOSITORY"
$fullChangelogUrl = "https://github.com/$repo/releases/tag/$($versionMetadata.tagName)"
$manifest = [ordered]@{
  schemaVersion = 1
  packageName = $versionMetadata.packageName
  versionCode = [int]$versionMetadata.versionCode
  versionName = $versionMetadata.versionName
  channel = $versionMetadata.channel
  releaseDate = $versionMetadata.releaseDate
  minSdk = [int]$versionMetadata.minSdk
  targetSdk = [int]$versionMetadata.targetSdk
  apkAssetName = $apkAssetName
  apkSha256 = $apkSha256
  apkSizeBytes = $apkSize
  signingCertificateSha256 = $signingMetadata.signingCertificateSha256
  changelogAssetName = "CHANGELOG.md"
  changelogSha256 = $changelogSha256
  changelogSizeBytes = $changelogSize
  changelogFormat = "markdown"
  fullChangelogUrl = $fullChangelogUrl
}
Write-Utf8NoBomFile (Join-Path $handoffDir "oto-update.json") (($manifest | ConvertTo-Json -Depth 8) + "`n")

$handoffMetadata = [ordered]@{
  tagName = $versionMetadata.tagName
  releaseName = $versionMetadata.releaseName
  channel = $versionMetadata.channel
  prerelease = [bool]$versionMetadata.prerelease
  targetCommit = $versionMetadata.targetCommit
  createdByRunId = $versionMetadata.createdByRunId
  createdByRunAttempt = $versionMetadata.createdByRunAttempt
  apkAssetName = $apkAssetName
  sha256AssetName = $shaAssetName
  changelogAssetName = "CHANGELOG.md"
  manifestAssetName = "oto-update.json"
  releaseBodyFileName = "release-body.md"
  apkSha256 = $apkSha256
  apkSizeBytes = $apkSize
  changelogSha256 = $changelogSha256
  changelogSizeBytes = $changelogSize
  changelogFormat = "markdown"
  fullChangelogUrl = $fullChangelogUrl
  signingCertificateSha256 = $signingMetadata.signingCertificateSha256
  packageName = $versionMetadata.packageName
  versionCode = [int]$versionMetadata.versionCode
  versionName = $versionMetadata.versionName
  minSdk = [int]$versionMetadata.minSdk
  targetSdk = [int]$versionMetadata.targetSdk
  releaseDate = $versionMetadata.releaseDate
  versionFloor = [int]$versionMetadata.versionFloor
  previousStableTag = $versionMetadata.previousStableTag
  uploadAssets = @($apkAssetName, $shaAssetName, "CHANGELOG.md", "oto-update.json")
}
Write-Utf8NoBomFile (Join-Path $handoffDir "release-metadata.json") (($handoffMetadata | ConvertTo-Json -Depth 8) + "`n")

Assert-ExactFileSet $handoffDir @($apkAssetName, $shaAssetName, "CHANGELOG.md", "oto-update.json", "release-body.md", "release-metadata.json")
if ((Get-Sha256 $stagedApk) -ne $apkSha256) {
  throw "Staged APK hash changed after staging."
}
if ((Get-Sha256 $stagedChangelogPath) -ne $changelogSha256) {
  throw "Staged changelog hash changed after staging."
}

$stagedManifest = Read-JsonFile (Join-Path $handoffDir "oto-update.json")
$stagedMetadata = Read-JsonFile (Join-Path $handoffDir "release-metadata.json")
Assert-ManifestMatchesMetadata $stagedManifest $stagedMetadata

Write-StepOutput "handoff_dir" $handoffDir
Write-StepOutput "tag_name" $versionMetadata.tagName
if ($publish) {
  Write-StepOutput "publish_authorized" "true"
  Write-Host "Publish authorized for $($versionMetadata.tagName)."
} else {
  Write-StepOutput "publish_authorized" "false"
  Write-Host "Dry run completed for $($versionMetadata.tagName). Publish is disabled."
}
