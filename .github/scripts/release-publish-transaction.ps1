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
  return @($output)
}

function Read-JsonFile {
  param([Parameter(Mandatory = $true)][string]$Path)
  return (Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json)
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
      [int64]$Manifest.apkSize -ne [int64]$Metadata.apkSize -or
      $Manifest.signingCertificateSha256 -ne $Metadata.signingCertificateSha256 -or
      $Manifest.changelogAssetName -ne $Metadata.changelogAssetName -or
      $Manifest.changelogSha256 -ne $Metadata.changelogSha256 -or
      [int64]$Manifest.changelogSize -ne [int64]$Metadata.changelogSize) {
    throw "oto-update.json does not match handoff metadata."
  }
}

function Assert-ExactFileSet {
  param(
    [Parameter(Mandatory = $true)][string]$Directory,
    [Parameter(Mandatory = $true)][string[]]$ExpectedNames
  )

  $items = @(Get-ChildItem -LiteralPath $Directory -Force)
  foreach ($item in $items) {
    if ($item.PSIsContainer) {
      throw "Artifact contains a directory: $($item.Name)"
    }
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
      throw "Artifact contains a reparse point or symlink: $($item.Name)"
    }
  }

  $actual = @($items | ForEach-Object { $_.Name } | Sort-Object)
  $expected = @($ExpectedNames | Sort-Object)
  $diff = Compare-Object -ReferenceObject $expected -DifferenceObject $actual
  if ($null -ne $diff) {
    throw "Unexpected file set. Expected: $($expected -join ', '). Actual: $($actual -join ', ')."
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

function Assert-HandoffArtifact {
  param(
    [Parameter(Mandatory = $true)][string]$Directory,
    [Parameter(Mandatory = $true)]$Metadata
  )

  Assert-ExactFileSet $Directory @(
    $Metadata.apkAssetName,
    $Metadata.sha256AssetName,
    $Metadata.changelogAssetName,
    $Metadata.manifestAssetName,
    $Metadata.releaseBodyFileName,
    "release-metadata.json"
  )

  $apkPath = Join-Path $Directory $Metadata.apkAssetName
  $shaPath = Join-Path $Directory $Metadata.sha256AssetName
  $changelogPath = Join-Path $Directory $Metadata.changelogAssetName
  $manifestPath = Join-Path $Directory $Metadata.manifestAssetName
  $bodyPath = Join-Path $Directory $Metadata.releaseBodyFileName

  if ((Get-Sha256 $apkPath) -ne $Metadata.apkSha256) {
    throw "APK hash does not match handoff metadata."
  }
  if ((Get-FileSize $apkPath) -ne [int64]$Metadata.apkSize) {
    throw "APK size does not match handoff metadata."
  }
  if ((Get-Sha256 $changelogPath) -ne $Metadata.changelogSha256) {
    throw "CHANGELOG.md hash does not match handoff metadata."
  }
  if ((Get-FileSize $changelogPath) -ne [int64]$Metadata.changelogSize) {
    throw "CHANGELOG.md size does not match handoff metadata."
  }

  $shaContent = Normalize-Text (Get-Content -LiteralPath $shaPath -Raw)
  if ($shaContent -ne "$($Metadata.apkSha256)  $($Metadata.apkAssetName)`n") {
    throw "APK SHA-256 asset content does not match sha256sum format."
  }

  $changelog = Normalize-Text (Get-Content -LiteralPath $changelogPath -Raw)
  $body = Normalize-Text (Get-Content -LiteralPath $bodyPath -Raw)
  if ($changelog -ne $body) {
    throw "Release body does not match CHANGELOG.md."
  }

  $manifest = Read-JsonFile $manifestPath
  Assert-ManifestMatchesMetadata $manifest $Metadata
}

function Get-ReleaseView {
  param([Parameter(Mandatory = $true)][string]$TagName)
  $json = Invoke-CheckedCommand "gh" @("release", "view", $TagName, "--json", "tagName,name,isDraft,isPrerelease,body,assets,targetCommitish") "Failed to inspect GitHub Release."
  return (($json | Out-String).Trim() | ConvertFrom-Json)
}

function Remove-RecordedDraftRelease {
  param(
    [Parameter(Mandatory = $true)][string]$TagName,
    [Parameter(Mandatory = $true)][string]$ExpectedTargetCommit,
    [Parameter(Mandatory = $true)][string]$ExpectedRunId
  )

  if ($ExpectedRunId -ne (Get-EnvValue "GITHUB_RUN_ID")) {
    return
  }

  $release = $null
  try {
    $release = Get-ReleaseView $TagName
  } catch {
    return
  }
  if ($null -eq $release -or -not $release.isDraft -or $release.tagName -ne $TagName) {
    return
  }
  if ($release.targetCommitish -ne $ExpectedTargetCommit) {
    return
  }

  Invoke-CheckedCommand "gh" @("release", "delete", $TagName, "--yes", "--cleanup-tag") "Failed to delete recorded draft release." | Out-Null
}

$handoffDir = Get-EnvValue "RELEASE_HANDOFF_DIR"
$metadataPath = Join-Path $handoffDir "release-metadata.json"
$metadata = Read-JsonFile $metadataPath
if ([string]::IsNullOrWhiteSpace($metadata.createdByRunId) -or $metadata.createdByRunId -ne (Get-EnvValue "GITHUB_RUN_ID")) {
  throw "Handoff artifact was not created by this workflow run."
}
Assert-HandoffArtifact $handoffDir $metadata

if (Test-GitTagRefExists $metadata.tagName) {
  throw "Git tag/ref '$($metadata.tagName)' already exists before publish."
}
if (Test-GitHubReleaseExists $metadata.tagName) {
  throw "GitHub Release '$($metadata.tagName)' already exists before publish."
}

$createdRelease = $false
try {
  $bodyPath = Join-Path $handoffDir $metadata.releaseBodyFileName
  $createArgs = @(
    "release",
    "create",
    $metadata.tagName,
    "--draft",
    "--title",
    $metadata.releaseName,
    "--notes-file",
    $bodyPath,
    "--target",
    $metadata.targetCommit
  )
  if ($metadata.prerelease) {
    $createArgs += @("--prerelease", "--latest=false")
  } else {
    $createArgs += "--latest=true"
  }
  Invoke-CheckedCommand "gh" $createArgs "Failed to create draft GitHub Release." | Out-Null
  $createdRelease = $true

  $assetPaths = @(
    Join-Path $handoffDir $metadata.apkAssetName,
    Join-Path $handoffDir $metadata.sha256AssetName,
    Join-Path $handoffDir $metadata.changelogAssetName,
    Join-Path $handoffDir $metadata.manifestAssetName
  )
  $uploadArgs = @("release", "upload", $metadata.tagName) + $assetPaths
  Invoke-CheckedCommand "gh" $uploadArgs "Failed to upload release assets." | Out-Null

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

  $editArgs = @("release", "edit", $metadata.tagName, "--draft=false")
  if ($metadata.prerelease) {
    $editArgs += @("--prerelease", "--latest=false")
  } else {
    $editArgs += "--latest=true"
  }
  Invoke-CheckedCommand "gh" $editArgs "Failed to publish verified draft release." | Out-Null
  Write-Host "Published release $($metadata.tagName)."
} catch {
  if ($createdRelease) {
    Remove-RecordedDraftRelease $metadata.tagName $metadata.targetCommit $metadata.createdByRunId
  }
  throw
}
