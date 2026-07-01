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

function Normalize-Text {
  param([string]$Value)
  if ($null -eq $Value) {
    return ""
  }
  return (($Value -replace "`r`n", "`n") -replace "`r", "`n")
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
  if ($null -ne (Compare-Object -ReferenceObject $expected -DifferenceObject $actual)) {
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

function Test-GitTagRefExists {
  param([Parameter(Mandatory = $true)][string]$TagName)

  $repo = Get-EnvValue "GITHUB_REPOSITORY"
  $output = & gh api "repos/$repo/git/ref/tags/$TagName" 2>&1
  if ($LASTEXITCODE -eq 0) {
    return $true
  }

  $message = ($output | Out-String).Trim()
  if ($message -match "HTTP 404" -or $message -match "Not Found") {
    return $false
  }
  throw "Failed to query git tag ref '$TagName'. $message"
}

function Test-GitHubReleaseExists {
  param([Parameter(Mandatory = $true)][string]$TagName)

  $output = & gh release view $TagName --json tagName,isDraft 2>&1
  if ($LASTEXITCODE -eq 0) {
    return $true
  }

  $message = ($output | Out-String).Trim()
  if ($message -match "not found" -or $message -match "HTTP 404" -or $message -match "Not Found") {
    return $false
  }
  throw "Failed to query GitHub Release '$TagName'. $message"
}

function Get-ReleaseList {
  $output = & gh release list --limit 1000 --json tagName,isDraft,isPrerelease 2>&1
  if ($LASTEXITCODE -ne 0) {
    $message = ($output | Out-String).Trim()
    throw "Failed to query GitHub Releases for package-global floor recheck. $message"
  }

  $json = ($output | Out-String).Trim()
  if ([string]::IsNullOrWhiteSpace($json)) {
    return @()
  }
  return @($json | ConvertFrom-Json)
}

<#
  Recomputes the package-global version floor from a fresh GitHub Releases
  snapshot taken at publish time. version-tag-gate computes the floor from a
  read-only snapshot much earlier in the run, so a concurrently publishing run
  can advance the floor after that gate passes. Rechecking here, immediately
  before the draft is created, closes that window: the floor is the highest
  versionCode already published under the new tag contract, and bootstrap
  (no published new-format stable release yet) keeps the floor at zero.
#>
function Get-PackageGlobalVersionFloor {
  $releases = Get-ReleaseList
  $nonDraftReleases = @($releases | Where-Object { -not $_.isDraft })
  $tagRegex = "^(stable|prerelease)-(\d{2}\.\d{2}\.\d{2})-(\d+)$"
  $newFormatVersionCodes = @()
  foreach ($release in $nonDraftReleases) {
    if ($release.tagName -match $tagRegex) {
      $newFormatVersionCodes += [int]$Matches[3]
    }
  }

  $hasNewFormatStable = @($nonDraftReleases | Where-Object { $_.tagName -match "^stable-\d{2}\.\d{2}\.\d{2}-\d+$" }).Count -gt 0
  if (-not $hasNewFormatStable) {
    return 0
  }
  return [int](@($newFormatVersionCodes | Measure-Object -Maximum).Maximum)
}

$handoffDir = Get-EnvValue "RELEASE_HANDOFF_DIR"
$metadataPath = Join-Path $handoffDir "release-metadata.json"
$metadata = Read-JsonFile $metadataPath

if ([string]::IsNullOrWhiteSpace($metadata.createdByRunId) -or $metadata.createdByRunId -ne (Get-EnvValue "GITHUB_RUN_ID")) {
  throw "Handoff artifact was not created by this workflow run."
}

Assert-ExactFileSet $handoffDir @(
  $metadata.apkAssetName,
  $metadata.sha256AssetName,
  $metadata.changelogAssetName,
  $metadata.manifestAssetName,
  $metadata.releaseBodyFileName,
  "release-metadata.json"
)

$apkPath = Join-Path $handoffDir $metadata.apkAssetName
$shaPath = Join-Path $handoffDir $metadata.sha256AssetName
$changelogPath = Join-Path $handoffDir $metadata.changelogAssetName
$manifestPath = Join-Path $handoffDir $metadata.manifestAssetName
$bodyPath = Join-Path $handoffDir $metadata.releaseBodyFileName

if ((Get-Sha256 $apkPath) -ne $metadata.apkSha256) {
  throw "APK hash does not match handoff metadata."
}
if ((Get-FileSize $apkPath) -ne [int64]$metadata.apkSizeBytes) {
  throw "APK size does not match handoff metadata."
}
if ((Get-Sha256 $changelogPath) -ne $metadata.changelogSha256) {
  throw "CHANGELOG.md hash does not match handoff metadata."
}
if ((Get-FileSize $changelogPath) -ne [int64]$metadata.changelogSizeBytes) {
  throw "CHANGELOG.md size does not match handoff metadata."
}

$shaContent = Normalize-Text (Get-Content -LiteralPath $shaPath -Raw)
if ($shaContent -ne "$($metadata.apkSha256)  $($metadata.apkAssetName)`n") {
  throw "APK SHA-256 asset content does not match sha256sum format."
}

if ((Normalize-Text (Get-Content -LiteralPath $changelogPath -Raw)) -ne (Normalize-Text (Get-Content -LiteralPath $bodyPath -Raw))) {
  throw "Release body does not match CHANGELOG.md."
}

$manifest = Read-JsonFile $manifestPath
Assert-ManifestMatchesMetadata $manifest $metadata

if (Test-GitTagRefExists $metadata.tagName) {
  throw "Git tag/ref '$($metadata.tagName)' already exists before publish."
}
if (Test-GitHubReleaseExists $metadata.tagName) {
  throw "GitHub Release '$($metadata.tagName)' already exists before publish."
}

$versionCodeInt = [int]$metadata.versionCode
$floor = Get-PackageGlobalVersionFloor
if ($versionCodeInt -le $floor) {
  $overrideRaw = Get-EnvValue "RELEASE_VERSION_FLOOR_OVERRIDE" $false
  if (-not [string]::IsNullOrWhiteSpace($overrideRaw)) {
    $ignoredTag = Get-EnvValue "RELEASE_VERSION_FLOOR_OVERRIDE_IGNORED_TAG"
    $reason = Get-EnvValue "RELEASE_VERSION_FLOOR_OVERRIDE_REASON"
    if ($overrideRaw -notmatch "^\d+$") {
      throw "RELEASE_VERSION_FLOOR_OVERRIDE must be numeric."
    }
    $overrideFloor = [int]$overrideRaw
    if ($overrideFloor -ge $versionCodeInt) {
      throw "RELEASE_VERSION_FLOOR_OVERRIDE must be lower than the current APK versionCode."
    }
    Write-Host "Using audited floor override $overrideFloor. Ignored tag: $ignoredTag. Reason: $reason. Operator: $env:GITHUB_ACTOR."
    $floor = $overrideFloor
  }
}
if ($versionCodeInt -le $floor) {
  throw "APK versionCode $versionCodeInt must be greater than package-global release floor $floor. A concurrent release likely advanced the floor after version-tag-gate."
}

Write-Host "Publish preflight gate passed for $($metadata.tagName)."
