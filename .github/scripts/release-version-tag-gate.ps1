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

function Get-ReleaseList {
  $output = & gh release list --limit 1000 --json tagName,name,isDraft,isPrerelease,createdAt,publishedAt 2>&1
  if ($LASTEXITCODE -ne 0) {
    $message = ($output | Out-String).Trim()
    throw "Failed to query GitHub Releases. $message"
  }

  $json = ($output | Out-String).Trim()
  if ([string]::IsNullOrWhiteSpace($json)) {
    return @()
  }
  return @($json | ConvertFrom-Json)
}

function Get-LatestStableRelease {
  $repo = Get-EnvValue "GITHUB_REPOSITORY"
  $output = & gh api "repos/$repo/releases/latest" 2>&1
  if ($LASTEXITCODE -eq 0) {
    $json = ($output | Out-String).Trim()
    if ([string]::IsNullOrWhiteSpace($json)) {
      return $null
    }

    $release = $json | ConvertFrom-Json
    if ($release.draft -or $release.prerelease) {
      throw "GitHub Latest release must point to a published stable release."
    }
    return [pscustomobject]@{
      TagName = $release.tag_name
      Name = $release.name
      CreatedAt = $release.created_at
      PublishedAt = $release.published_at
    }
  }

  $message = ($output | Out-String).Trim()
  if ($message -match "HTTP 404" -or $message -match "Not Found") {
    return $null
  }
  throw "Failed to query GitHub Latest release. $message"
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

<#
  Matches release tags managed by the CI version floor. The generated
  `oto-<channel>-<versionName>-<versionCode>` identity is now the only managed
  contract; no-prefix tags are excluded from bootstrap and floor calculation.
#>
function Get-ManagedReleaseTagRegex {
  return "^oto-(stable|prerelease)-(\d{2}\.\d{2}\.\d{2})-(\d+)$"
}

$metadata = Read-JsonFile (Get-EnvValue "RELEASE_APK_METADATA_PATH")
$outputPath = Get-EnvValue "RELEASE_VERSION_METADATA_PATH"
$channel = Get-EnvValue "RELEASE_CHANNEL"
$releaseDate = Get-EnvValue "OTO_RELEASE_DATE"
$prerelease = Test-Truthy (Get-EnvValue "RELEASE_PRERELEASE")

if ($channel -ne "stable" -and $channel -ne "prerelease") {
  throw "Invalid release channel: $channel"
}
if ($channel -eq "prerelease" -and -not $prerelease) {
  throw "Release channel and prerelease input disagree."
}
if ($channel -eq "stable" -and $prerelease) {
  throw "Release channel and prerelease input disagree."
}
if ($releaseDate -notmatch "^\d{2}\.\d{2}\.\d{2}$") {
  throw "OTO_RELEASE_DATE must use yy.MM.dd format."
}

$expectedVersionCode = (Invoke-CheckedCommand "git" @("rev-list", "--count", "HEAD") "Failed to compute git commit count.")[0].Trim()
if ($metadata.versionCode -ne $expectedVersionCode) {
  throw "APK versionCode '$($metadata.versionCode)' does not match expected '$expectedVersionCode'."
}
if ($metadata.versionName -ne $releaseDate) {
  throw "APK versionName '$($metadata.versionName)' does not match release date '$releaseDate'."
}
if ($metadata.packageName -ne "com.viel.oto") {
  throw "APK package name '$($metadata.packageName)' does not match com.viel.oto."
}

$releaseIdentity = "oto-$channel-$($metadata.versionName)-$($metadata.versionCode)"
$tagName = $releaseIdentity
if ($tagName -notmatch "^oto-(stable|prerelease)-\d{2}\.\d{2}\.\d{2}-\d+$") {
  throw "Generated tag '$tagName' does not match the release tag contract."
}
$releaseName = "Oto $($metadata.versionName) ($($metadata.versionCode)) [$channel]"

if (Test-GitTagRefExists $tagName) {
  throw "Git tag/ref '$tagName' already exists."
}
if (Test-GitHubReleaseExists $tagName) {
  throw "GitHub Release '$tagName' already exists, including draft releases."
}

$releases = Get-ReleaseList
$matchingRelease = @($releases | Where-Object { $_.tagName -eq $tagName })
if ($matchingRelease.Count -gt 0) {
  throw "GitHub Release '$tagName' already exists, including draft releases."
}

$nonDraftReleases = @($releases | Where-Object { -not $_.isDraft })
$tagRegex = Get-ManagedReleaseTagRegex
$newFormatReleases = @()
foreach ($release in $nonDraftReleases) {
  if ($release.tagName -match $tagRegex) {
    $releaseChannel = $Matches[1]
    $releaseVersionName = $Matches[2]
    $releaseVersionCode = [int]$Matches[3]
    $expectedTitle = "Oto $releaseVersionName ($releaseVersionCode) [$releaseChannel]"
    if (-not [string]::IsNullOrWhiteSpace($release.name) -and $release.name -ne $expectedTitle) {
      throw "Release title '$($release.name)' does not match tag '$($release.tagName)'."
    }
    # A new-format tag's channel is the source of truth for the update manifest, so it must agree
    # with the GitHub prerelease flag. Otherwise a stable-tagged GitHub prerelease would be counted
    # as a stable baseline here while releases/latest ignores it, corrupting bootstrap and channel gating.
    $expectedPrerelease = $releaseChannel -eq "prerelease"
    if ([bool]$release.isPrerelease -ne $expectedPrerelease) {
      throw "Release '$($release.tagName)' GitHub prerelease flag '$([bool]$release.isPrerelease)' does not match its tag channel '$releaseChannel'."
    }
    $newFormatReleases += [pscustomobject]@{
      TagName = $release.tagName
      Channel = $releaseChannel
      VersionCode = $releaseVersionCode
      IsPrerelease = $release.isPrerelease
      CreatedAt = $release.createdAt
    }
  }
}

$hasNewFormatStable = @($newFormatReleases | Where-Object { $_.Channel -eq "stable" }).Count -gt 0
$floor = 0
$isStableManifestBootstrap = -not $hasNewFormatStable
if ($hasNewFormatStable) {
  $firstNewFormatStable = @($newFormatReleases | Where-Object { $_.Channel -eq "stable" } | Sort-Object CreatedAt | Select-Object -First 1)[0]
  foreach ($release in $nonDraftReleases) {
    if ($release.tagName -notmatch $tagRegex -and
        -not [string]::IsNullOrWhiteSpace($release.createdAt) -and
        ([DateTimeOffset]$release.createdAt) -ge ([DateTimeOffset]$firstNewFormatStable.CreatedAt)) {
      throw "Non-draft release '$($release.tagName)' is not a new-format tag after stable manifest bootstrap."
    }
  }
  $floor = [int](@($newFormatReleases | Measure-Object -Property VersionCode -Maximum).Maximum)
} else {
  if ($channel -eq "prerelease") {
    throw "The first manifest release must be stable; prerelease cannot bootstrap the update manifest."
  }
}

$versionCodeInt = [int]$metadata.versionCode
if ($versionCodeInt -le $floor) {
  throw "APK versionCode $versionCodeInt must be greater than package-global release floor $floor."
}

$latestStableRelease = Get-LatestStableRelease
$previousStableTag = if ($null -eq $latestStableRelease) { "" } else { $latestStableRelease.TagName }

$versionMetadata = [ordered]@{
  tagName = $tagName
  releaseName = $releaseName
  channel = $channel
  prerelease = $prerelease
  targetCommit = Get-EnvValue "GITHUB_SHA"
  packageName = $metadata.packageName
  versionCode = $versionCodeInt
  versionName = $metadata.versionName
  minSdk = [int]$metadata.minSdk
  targetSdk = [int]$metadata.targetSdk
  releaseDate = $releaseDate
  versionFloor = $floor
  previousStableTag = $previousStableTag
  isStableManifestBootstrap = $isStableManifestBootstrap
  createdByRunId = Get-EnvValue "GITHUB_RUN_ID"
  createdByRunAttempt = Get-EnvValue "GITHUB_RUN_ATTEMPT" $false
}

Write-JsonFile $outputPath $versionMetadata
Write-StepOutput "tag_name" $tagName
Write-StepOutput "release_name" $releaseName
Write-Host "Version/tag gate passed for $tagName."

# GitHub Actions Pwsh Exit Boundary
# Expected negative GitHub CLI probes, such as a missing latest stable release
# during bootstrap, leave LASTEXITCODE set to 1 even after this script handles
# that absence as valid state. Reset it after all gate outputs are written so
# the GitHub Actions pwsh wrapper reports the gate result instead of the last
# handled native-command probe.
$global:LASTEXITCODE = 0
