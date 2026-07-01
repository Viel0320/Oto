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
  # Unary comma keeps single-line command output as a 1-element array instead of
  # letting PowerShell unwrap it to a scalar string on return.
  return ,@($output)
}

function Assert-ExactFileSet {
  param(
    [Parameter(Mandatory = $true)][string]$Directory,
    [Parameter(Mandatory = $true)][string[]]$ExpectedNames
  )

  $items = @(Get-ChildItem -LiteralPath $Directory -Force)
  foreach ($item in $items) {
    if ($item.PSIsContainer) {
      throw "Remote artifact download contains a directory: $($item.Name)"
    }
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
      throw "Remote artifact download contains a reparse point or symlink: $($item.Name)"
    }
  }

  $actual = @($items | ForEach-Object { $_.Name } | Sort-Object)
  $expected = @($ExpectedNames | Sort-Object)
  if ($null -ne (Compare-Object -ReferenceObject $expected -DifferenceObject $actual)) {
    throw "Unexpected remote asset set. Expected: $($expected -join ', '). Actual: $($actual -join ', ')."
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
    throw "Remote oto-update.json does not match handoff metadata."
  }
}

function Get-ReleaseView {
  param([Parameter(Mandatory = $true)][string]$TagName)
  $json = Invoke-CheckedCommand "gh" @("release", "view", $TagName, "--json", "tagName,name,isDraft,isPrerelease,body,assets,targetCommitish") "Failed to inspect GitHub Release."
  return (($json | Out-String).Trim() | ConvertFrom-Json)
}

$handoffDir = Get-EnvValue "RELEASE_HANDOFF_DIR"
$metadata = Read-JsonFile (Join-Path $handoffDir "release-metadata.json")
if ([string]::IsNullOrWhiteSpace($metadata.createdByRunId) -or $metadata.createdByRunId -ne (Get-EnvValue "GITHUB_RUN_ID")) {
  throw "Handoff artifact was not created by this workflow run."
}

$remote = Get-ReleaseView $metadata.tagName
if (-not $remote.isDraft) {
  throw "GitHub Release was not left as draft before remote verification."
}
if ($remote.name -ne $metadata.releaseName) {
  throw "Remote release title does not match handoff metadata."
}
if ([bool]$remote.isPrerelease -ne [bool]$metadata.prerelease) {
  throw "Remote prerelease flag does not match handoff metadata."
}
if ((Normalize-Text $remote.body).TrimEnd() -ne (Normalize-Text (Get-Content -LiteralPath (Join-Path $handoffDir $metadata.releaseBodyFileName) -Raw)).TrimEnd()) {
  throw "Remote release body does not match handoff body."
}

$remoteAssets = @($remote.assets)
$remoteAssetNames = @($remoteAssets | ForEach-Object { $_.name } | Sort-Object)
$expectedAssetNames = @($metadata.apkAssetName, $metadata.sha256AssetName, $metadata.changelogAssetName, $metadata.manifestAssetName) | Sort-Object
if ($null -ne (Compare-Object -ReferenceObject $expectedAssetNames -DifferenceObject $remoteAssetNames)) {
  throw "Remote asset set does not match handoff metadata."
}
foreach ($asset in $remoteAssets) {
  $localPath = Join-Path $handoffDir $asset.name
  if ([int64]$asset.size -ne (Get-FileSize $localPath)) {
    throw "Remote asset size does not match local handoff file: $($asset.name)"
  }
}

$downloadDir = Join-Path (Get-EnvValue "RUNNER_TEMP") "oto-release-remote-verify"
if (Test-Path -LiteralPath $downloadDir) {
  Remove-Item -LiteralPath $downloadDir -Recurse -Force
}
New-Item -ItemType Directory -Path $downloadDir | Out-Null
Invoke-CheckedCommand "gh" @("release", "download", $metadata.tagName, "--dir", $downloadDir, "--pattern", "*") "Failed to download uploaded release assets for verification." | Out-Null
Assert-ExactFileSet $downloadDir @($metadata.apkAssetName, $metadata.sha256AssetName, $metadata.changelogAssetName, $metadata.manifestAssetName)

if ((Get-Sha256 (Join-Path $downloadDir $metadata.apkAssetName)) -ne $metadata.apkSha256) {
  throw "Remote APK hash does not match handoff metadata."
}
if ((Get-Sha256 (Join-Path $downloadDir $metadata.changelogAssetName)) -ne $metadata.changelogSha256) {
  throw "Remote changelog hash does not match handoff metadata."
}

$remoteShaContent = Normalize-Text (Get-Content -LiteralPath (Join-Path $downloadDir $metadata.sha256AssetName) -Raw)
if ($remoteShaContent -ne "$($metadata.apkSha256)  $($metadata.apkAssetName)`n") {
  throw "Remote APK SHA-256 asset content does not match handoff metadata."
}

$remoteManifest = Read-JsonFile (Join-Path $downloadDir $metadata.manifestAssetName)
Assert-ManifestMatchesMetadata $remoteManifest $metadata
Write-Host "Publish remote gate passed for $($metadata.tagName)."
