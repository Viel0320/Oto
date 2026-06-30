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

  if ([string]::IsNullOrWhiteSpace($env:GITHUB_OUTPUT)) {
    return
  }
  Add-Content -LiteralPath $env:GITHUB_OUTPUT -Value "$Name=$Value"
}

function Test-Truthy {
  param([string]$Value)
  return [string]::Equals($Value, "true", [StringComparison]::OrdinalIgnoreCase)
}

function Normalize-Sha256 {
  param([Parameter(Mandatory = $true)][string]$Value)
  return ($Value -replace "[:\s]", "").ToLowerInvariant()
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

function Find-AndroidTool {
  param([Parameter(Mandatory = $true)][string]$ToolName)

  $androidHome = Get-EnvValue "ANDROID_HOME"
  $buildTools = Join-Path $androidHome "build-tools"
  $candidateNames = @($ToolName)
  if ($ToolName -notmatch "\.") {
    $candidateNames += @("$ToolName.bat", "$ToolName.exe")
  }

  foreach ($candidateName in $candidateNames) {
    $tool = Get-ChildItem -LiteralPath $buildTools -Recurse -Filter $candidateName -File |
      Sort-Object FullName -Descending |
      Select-Object -First 1
    if ($null -ne $tool) {
      return $tool.FullName
    }
  }

  if ($null -eq $tool) {
    throw "Android SDK tool not found: $ToolName"
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

function Read-ApkMetadata {
  param([Parameter(Mandatory = $true)][string]$ApkPath)

  $aapt2 = Find-AndroidTool "aapt2"
  $badging = Invoke-CheckedCommand $aapt2 @("dump", "badging", $ApkPath) "Failed to read APK metadata with aapt2."
  $packageLine = @($badging | Where-Object { $_ -match "^package:" } | Select-Object -First 1)[0]
  if ($packageLine -notmatch "name='([^']+)'\s+versionCode='([^']+)'\s+versionName='([^']+)'") {
    throw "Failed to parse APK package metadata."
  }
  $packageName = $Matches[1]
  $versionCode = $Matches[2]
  $versionName = $Matches[3]

  $sdkLine = @($badging | Where-Object { $_ -match "^sdkVersion:" } | Select-Object -First 1)[0]
  $targetSdkLine = @($badging | Where-Object { $_ -match "^targetSdkVersion:" } | Select-Object -First 1)[0]
  if ($sdkLine -notmatch "sdkVersion:'([^']+)'") {
    throw "Failed to parse APK minSdk metadata."
  }
  $minSdk = $Matches[1]
  if ($targetSdkLine -notmatch "targetSdkVersion:'([^']+)'") {
    throw "Failed to parse APK targetSdk metadata."
  }
  $targetSdk = $Matches[1]

  return [pscustomobject]@{
    PackageName = $packageName
    VersionCode = $versionCode
    VersionName = $versionName
    MinSdk = $minSdk
    TargetSdk = $targetSdk
  }
}

function Read-ApkSigningCertificateSha256 {
  param([Parameter(Mandatory = $true)][string]$ApkPath)

  $apksigner = Find-AndroidTool "apksigner"
  $certOutput = Invoke-CheckedCommand $apksigner @("verify", "--print-certs", $ApkPath) "APK signing verification failed."
  $certLine = @($certOutput | Where-Object { $_ -match "SHA-256 digest:\s*([0-9A-Fa-f:]+)" } | Select-Object -First 1)[0]
  if ($certLine -notmatch "SHA-256 digest:\s*([0-9A-Fa-f:]+)") {
    throw "Failed to parse APK signing certificate SHA-256."
  }
  return Normalize-Sha256 $Matches[1]
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
    $actualText = $actual -join ", "
    $expectedText = $expected -join ", "
    throw "Unexpected handoff file set. Expected: $expectedText. Actual: $actualText."
  }
}

$channel = Get-EnvValue "RELEASE_CHANNEL"
$releaseDate = Get-EnvValue "OTO_RELEASE_DATE"
$publish = Test-Truthy (Get-EnvValue "RELEASE_PUBLISH")
$prerelease = Test-Truthy (Get-EnvValue "RELEASE_PRERELEASE")
$manualChangelog = Normalize-Text (Get-EnvValue "RELEASE_MANUAL_CHANGELOG" $false)
$highlights = Normalize-Text (Get-EnvValue "RELEASE_HIGHLIGHTS" $false)
$expectedCertificateSha256 = Normalize-Sha256 (Get-EnvValue "SIGNING_CERTIFICATE_SHA256")

if ($expectedCertificateSha256 -notmatch "^[0-9a-f]{64}$") {
  throw "SIGNING_CERTIFICATE_SHA256 must be a 64-character SHA-256 hex digest."
}
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

$gitConfig = Join-Path (Get-Location) ".git/config"
if (Test-Path -LiteralPath $gitConfig) {
  $configText = Get-Content -LiteralPath $gitConfig -Raw
  if ($configText -match "ghs_" -or $configText -match "x-access-token" -or $configText -match "oauth") {
    throw "Checkout credentials were persisted in .git/config."
  }
}

$keystorePath = Join-Path (Get-EnvValue "RUNNER_TEMP") "release-ci.jks"
$workspacePath = [IO.Path]::GetFullPath((Get-EnvValue "GITHUB_WORKSPACE")).TrimEnd("\", "/")
$fullKeystorePath = [IO.Path]::GetFullPath($keystorePath)
if ($fullKeystorePath.StartsWith($workspacePath, [StringComparison]::OrdinalIgnoreCase)) {
  throw "Release keystore must not be materialized inside the workspace."
}

$keystoreBase64 = Get-EnvValue "KEYSTORE_BASE64"
$keyStorePassword = Get-EnvValue "KEYSTORE_PASSWORD"
$keyAlias = Get-EnvValue "KEY_ALIAS"
$keyPassword = Get-EnvValue "KEY_PASSWORD"
[IO.File]::WriteAllBytes($keystorePath, [Convert]::FromBase64String($keystoreBase64))

try {
  $env:KEYSTORE_FILE = $keystorePath
  $env:KEYSTORE_PASSWORD = $keyStorePassword
  $env:KEY_ALIAS = $keyAlias
  $env:KEY_PASSWORD = $keyPassword
  $env:OTO_REQUIRE_RELEASE_SIGNING = "true"
  $env:OTO_CI_RELEASE_BUILD = "true"
  $env:OTO_RELEASE_DATE = $releaseDate

  $isWindowsRunner = [Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([Runtime.InteropServices.OSPlatform]::Windows)
  if ($isWindowsRunner) {
    Invoke-CheckedCommand ".\gradlew.bat" @("assembleRelease") "Gradle release build failed." | Out-Null
  } else {
    Invoke-CheckedCommand "bash" @("./gradlew", "assembleRelease") "Gradle release build failed." | Out-Null
  }
} finally {
  if (Test-Path -LiteralPath $keystorePath) {
    Remove-Item -LiteralPath $keystorePath -Force
  }
}

$releaseApks = @(Get-ChildItem -LiteralPath "app/build/outputs/apk/release" -Filter "*.apk" -File)
if ($releaseApks.Count -ne 1) {
  throw "Expected exactly one release APK, found $($releaseApks.Count)."
}
$apk = $releaseApks[0]

$metadata = Read-ApkMetadata $apk.FullName
$certificateSha256 = Read-ApkSigningCertificateSha256 $apk.FullName
if ($certificateSha256 -ne $expectedCertificateSha256) {
  throw "APK signing certificate SHA-256 does not match SIGNING_CERTIFICATE_SHA256."
}

$expectedVersionCode = (Invoke-CheckedCommand "git" @("rev-list", "--count", "HEAD") "Failed to compute git commit count.")[0].Trim()
if ($metadata.VersionCode -ne $expectedVersionCode) {
  throw "APK versionCode '$($metadata.VersionCode)' does not match expected '$expectedVersionCode'."
}
if ($metadata.VersionName -ne $releaseDate) {
  throw "APK versionName '$($metadata.VersionName)' does not match release date '$releaseDate'."
}
if ($metadata.PackageName -ne "com.viel.oto") {
  throw "APK package name '$($metadata.PackageName)' does not match com.viel.oto."
}
if ($metadata.MinSdk -ne "33") {
  throw "APK minSdk '$($metadata.MinSdk)' does not match 33."
}
if ($metadata.TargetSdk -ne "36") {
  throw "APK targetSdk '$($metadata.TargetSdk)' does not match 36."
}

$tagName = "$channel-$($metadata.VersionName)-$($metadata.VersionCode)"
if ($tagName -notmatch "^(stable|prerelease)-\d{2}\.\d{2}\.\d{2}-\d+$") {
  throw "Generated tag '$tagName' does not match the release tag contract."
}
$releaseName = "Oto $($metadata.VersionName) ($($metadata.VersionCode)) [$channel]"
$apkAssetName = "oto-$channel-$($metadata.VersionName)-$($metadata.VersionCode)-universal.apk"

if (Test-GitTagRefExists $tagName) {
  throw "Git tag/ref '$tagName' already exists."
}

$releases = Get-ReleaseList
$matchingRelease = @($releases | Where-Object { $_.tagName -eq $tagName })
if ($matchingRelease.Count -gt 0) {
  throw "GitHub Release '$tagName' already exists, including draft releases."
}

$nonDraftReleases = @($releases | Where-Object { -not $_.isDraft })
$tagRegex = "^(stable|prerelease)-(\d{2}\.\d{2}\.\d{2})-(\d+)$"
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
  $legacyCodes = @()
  foreach ($release in @($nonDraftReleases | Where-Object { -not $_.isPrerelease })) {
    if ($release.tagName -match "^.+\((\d+)\)$") {
      $legacyCodes += [int]$Matches[1]
    }
  }
  if ($legacyCodes.Count -gt 0) {
    $floor = [int](($legacyCodes | Measure-Object -Maximum).Maximum)
  }
}

$versionCodeInt = [int]$metadata.VersionCode
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
  throw "APK versionCode $versionCodeInt must be greater than package-global release floor $floor."
}

$previousStableRelease = @($nonDraftReleases | Where-Object { -not $_.isPrerelease } | Select-Object -First 1)
$previousStableTag = if ($previousStableRelease.Count -eq 0) { "" } else { $previousStableRelease[0].tagName }

$manualTrimmed = $manualChangelog.Trim()
$highlightsTrimmed = $highlights.Trim()
if ($manualTrimmed.Length -gt 0 -and $highlightsTrimmed.Length -gt 0) {
  throw "highlights must be empty when manual_changelog is provided."
}

if ($manualTrimmed.Length -gt 0) {
  if ($manualTrimmed -match "(?is)<\s*(script|iframe|img)\b") {
    throw "manual_changelog contains unsupported raw HTML."
  }
  $changelog = $manualTrimmed.TrimEnd() + "`n"
} else {
  $notes = [System.Collections.Generic.List[string]]::new()
  if ($highlightsTrimmed.Length -gt 0) {
    $notes.Add("## Highlights")
    $notes.Add("")
    $notes.Add($highlightsTrimmed.TrimEnd())
    $notes.Add("")
  }
  $notes.Add("## What's Changed")
  $notes.Add("")
  if ([string]::IsNullOrWhiteSpace($previousStableTag)) {
    $notes.Add("Changes included in this release:")
    $commitTitles = Invoke-CheckedCommand "git" @("log", $env:GITHUB_SHA, "--pretty=format:%s") "Failed to read release changelog commits."
  } else {
    Invoke-CheckedCommand "git" @("rev-parse", "--verify", "refs/tags/$previousStableTag^{commit}") "Previous stable release tag '$previousStableTag' is missing from checkout." | Out-Null
    $notes.Add("Changes since ``$previousStableTag``:")
    $commitTitles = Invoke-CheckedCommand "git" @("log", "$previousStableTag..$env:GITHUB_SHA", "--pretty=format:%s") "Failed to read release changelog commits."
  }
  $commitTitles = @($commitTitles | ForEach-Object { $_.Trim() } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
  if ($commitTitles.Count -eq 0) {
    $notes.Add("- No commits were found for this release range.")
  } else {
    foreach ($title in $commitTitles) {
      $notes.Add("- $title")
    }
  }
  $changelog = (($notes -join "`n").TrimEnd()) + "`n"
}

if ($changelog.Length -gt 125000) {
  throw "GitHub Release body exceeds the platform length limit."
}

$handoffDir = Join-Path (Get-EnvValue "RUNNER_TEMP") "oto-release-publish-handoff"
if (Test-Path -LiteralPath $handoffDir) {
  Remove-Item -LiteralPath $handoffDir -Recurse -Force
}
New-Item -ItemType Directory -Path $handoffDir | Out-Null

$stagedApk = Join-Path $handoffDir $apkAssetName
Copy-Item -LiteralPath $apk.FullName -Destination $stagedApk
$apkSha256 = Get-Sha256 $stagedApk
$apkSize = Get-FileSize $stagedApk
$shaAssetName = "$apkAssetName.sha256"
Write-Utf8NoBomFile (Join-Path $handoffDir $shaAssetName) "$apkSha256  $apkAssetName`n"

$changelogPath = Join-Path $handoffDir "CHANGELOG.md"
$releaseBodyPath = Join-Path $handoffDir "release-body.md"
Write-Utf8NoBomFile $changelogPath $changelog
Write-Utf8NoBomFile $releaseBodyPath $changelog
$changelogSha256 = Get-Sha256 $changelogPath
$changelogSize = Get-FileSize $changelogPath

$repo = Get-EnvValue "GITHUB_REPOSITORY"
$targetCommit = Get-EnvValue "GITHUB_SHA"
$runId = Get-EnvValue "GITHUB_RUN_ID"
$runAttempt = Get-EnvValue "GITHUB_RUN_ATTEMPT" $false
$manifest = [ordered]@{
  schemaVersion = 1
  packageName = $metadata.PackageName
  versionCode = $versionCodeInt
  versionName = $metadata.VersionName
  channel = $channel
  releaseDate = $releaseDate
  minSdk = [int]$metadata.MinSdk
  targetSdk = [int]$metadata.TargetSdk
  apkAssetName = $apkAssetName
  apkSha256 = $apkSha256
  apkSize = $apkSize
  signingCertificateSha256 = $certificateSha256
  changelogAssetName = "CHANGELOG.md"
  changelogSha256 = $changelogSha256
  changelogSize = $changelogSize
  fullChangelogUrl = "https://github.com/$repo/releases/tag/$tagName"
}
Write-Utf8NoBomFile (Join-Path $handoffDir "oto-update.json") (($manifest | ConvertTo-Json -Depth 8) + "`n")

$handoffMetadata = [ordered]@{
  tagName = $tagName
  releaseName = $releaseName
  channel = $channel
  prerelease = $prerelease
  targetCommit = $targetCommit
  createdByRunId = $runId
  createdByRunAttempt = $runAttempt
  apkAssetName = $apkAssetName
  sha256AssetName = $shaAssetName
  changelogAssetName = "CHANGELOG.md"
  manifestAssetName = "oto-update.json"
  releaseBodyFileName = "release-body.md"
  apkSha256 = $apkSha256
  apkSize = $apkSize
  changelogSha256 = $changelogSha256
  changelogSize = $changelogSize
  signingCertificateSha256 = $certificateSha256
  packageName = $metadata.PackageName
  versionCode = $versionCodeInt
  versionName = $metadata.VersionName
  minSdk = [int]$metadata.MinSdk
  targetSdk = [int]$metadata.TargetSdk
  releaseDate = $releaseDate
  versionFloor = $floor
  previousStableTag = $previousStableTag
  uploadAssets = @($apkAssetName, $shaAssetName, "CHANGELOG.md", "oto-update.json")
}
Write-Utf8NoBomFile (Join-Path $handoffDir "release-metadata.json") (($handoffMetadata | ConvertTo-Json -Depth 8) + "`n")

Assert-ExactFileSet $handoffDir @($apkAssetName, $shaAssetName, "CHANGELOG.md", "oto-update.json", "release-body.md", "release-metadata.json")
if ((Get-Sha256 $stagedApk) -ne $apkSha256) {
  throw "Staged APK hash changed after staging."
}
if ((Get-Sha256 $changelogPath) -ne $changelogSha256) {
  throw "Staged changelog hash changed after staging."
}

Write-StepOutput "handoff_dir" $handoffDir
Write-StepOutput "tag_name" $tagName
if ($publish) {
  Write-StepOutput "publish_authorized" "true"
  Write-Host "Publish authorized for $tagName."
} else {
  Write-StepOutput "publish_authorized" "false"
  Write-Host "Dry run completed for $tagName. Publish is disabled."
}
