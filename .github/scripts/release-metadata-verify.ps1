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

function Normalize-Sha256 {
  param([Parameter(Mandatory = $true)][string]$Value)
  return ($Value -replace "[:\s]", "").ToLowerInvariant()
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

function Assert-StringEquals {
  param(
    [Parameter(Mandatory = $true)][string]$Actual,
    [Parameter(Mandatory = $true)][string]$Expected,
    [Parameter(Mandatory = $true)][string]$Description
  )

  if (-not [string]::Equals($Actual, $Expected, [StringComparison]::Ordinal)) {
    throw "$Description drifted from its source file. Expected '$Expected', metadata has '$Actual'."
  }
}

function Assert-Int64Equals {
  param(
    [Parameter(Mandatory = $true)][int64]$Actual,
    [Parameter(Mandatory = $true)][int64]$Expected,
    [Parameter(Mandatory = $true)][string]$Description
  )

  if ($Actual -ne $Expected) {
    throw "$Description drifted from its source file. Expected '$Expected', metadata has '$Actual'."
  }
}

<#
  Resolves Android build tools inside the verify job so producer metadata is
  checked against the APK body instead of trusted as a producer assertion.
#>
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

  throw "Android SDK tool not found: $ToolName"
}

function Read-BadgingOutput {
  param([Parameter(Mandatory = $true)][string]$ApkPath)

  try {
    $aapt = Find-AndroidTool "aapt"
    return Invoke-CheckedCommand $aapt @("dump", "badging", $ApkPath) "Failed to read APK metadata with aapt."
  } catch {
    $aapt2 = Find-AndroidTool "aapt2"
    return Invoke-CheckedCommand $aapt2 @("dump", "badging", $ApkPath) "Failed to read APK metadata with aapt2."
  }
}

function Select-RequiredLine {
  param(
    [Parameter(Mandatory = $true)][object[]]$Lines,
    [Parameter(Mandatory = $true)][string]$Pattern,
    [Parameter(Mandatory = $true)][string]$Description
  )

  $matches = @($Lines | Where-Object { $_ -match $Pattern } | Select-Object -First 1)
  if ($matches.Count -eq 0) {
    $badgingText = ($Lines | Out-String).Trim()
    throw "Failed to find APK $Description metadata in badging output.`n$badgingText"
  }
  return $matches[0]
}

<#
  Reconstructs the APK-derived portion of release-build-metadata.json from the
  normalized APK. This catches drift where the JSON survives but no longer
  describes the artifact that later gates and handoff steps consume.
#>
function Read-ApkSourceFacts {
  param([Parameter(Mandatory = $true)][string]$ApkPath)

  $badging = Read-BadgingOutput $ApkPath
  $packageLine = Select-RequiredLine $badging "^package:" "package"
  if (-not ($packageLine -match "name='([^']+)'\s+versionCode='([^']+)'\s+versionName='([^']+)'")) {
    throw "Failed to parse APK package metadata: $packageLine"
  }
  $packageName = $Matches[1]
  $versionCode = $Matches[2]
  $versionName = $Matches[3]

  $sdkLine = Select-RequiredLine $badging "^sdkVersion:" "minSdk"
  if (-not ($sdkLine -match "sdkVersion:'([^']+)'")) {
    throw "Failed to parse APK minSdk metadata: $sdkLine"
  }
  $minSdk = $Matches[1]

  $targetSdkLine = Select-RequiredLine $badging "^targetSdkVersion:" "targetSdk"
  if (-not ($targetSdkLine -match "targetSdkVersion:'([^']+)'")) {
    throw "Failed to parse APK targetSdk metadata: $targetSdkLine"
  }
  $targetSdk = $Matches[1]

  $apksigner = Find-AndroidTool "apksigner"
  $certOutput = Invoke-CheckedCommand $apksigner @("verify", "--print-certs", $ApkPath) "APK signing verification failed."
  $certText = ($certOutput | Out-String).Trim()
  $certLine = @($certOutput | Where-Object { $_ -match "SHA-256 digest:\s*([0-9A-Fa-f:]+)" } | Select-Object -First 1)
  if ($certLine.Count -eq 0) {
    throw "Failed to parse APK signing certificate SHA-256.`n$certText"
  }
  if (-not ([string]$certLine[0] -match "SHA-256 digest:\s*([0-9A-Fa-f:]+)")) {
    throw "Failed to parse APK signing certificate SHA-256.`n$certText"
  }

  return [pscustomobject]@{
    ApkSha256 = Get-Sha256 $ApkPath
    ApkSizeBytes = Get-FileSize $ApkPath
    PackageName = $packageName
    VersionCode = $versionCode
    VersionName = $versionName
    MinSdk = $minSdk
    TargetSdk = $targetSdk
    SigningCertificateSha256 = Normalize-Sha256 $Matches[1]
  }
}

function Assert-BuildMetadataMatchesApk {
  param([Parameter(Mandatory = $true)][string]$BuildDir)

  Assert-ExactFileSet $BuildDir @("release.apk", "release-build-metadata.json") "release build artifact"

  $apkPath = Join-Path $BuildDir "release.apk"
  $metadata = Read-JsonFile (Join-Path $BuildDir "release-build-metadata.json")
  if ([int]$metadata.schemaVersion -ne 1 -or $metadata.artifactKind -ne "release-build") {
    throw "Release build metadata has an unsupported schema."
  }
  Assert-StringEquals ([string]$metadata.apkFileName) "release.apk" "Release build metadata apkFileName"

  $sourceApkName = [string]$metadata.sourceApkFileName
  if ([string]::IsNullOrWhiteSpace($sourceApkName) -or $sourceApkName -notlike "*.apk") {
    throw "Release build metadata sourceApkFileName must describe the producer APK file."
  }

  $facts = Read-ApkSourceFacts $apkPath
  Assert-StringEquals ([string]$metadata.apkSha256) $facts.ApkSha256 "Release build metadata apkSha256"
  Assert-Int64Equals ([int64]$metadata.apkSizeBytes) ([int64]$facts.ApkSizeBytes) "Release build metadata apkSizeBytes"
  Assert-StringEquals ([string]$metadata.packageName) $facts.PackageName "Release build metadata packageName"
  Assert-StringEquals ([string]$metadata.versionCode) $facts.VersionCode "Release build metadata versionCode"
  Assert-StringEquals ([string]$metadata.versionName) $facts.VersionName "Release build metadata versionName"
  Assert-StringEquals ([string]$metadata.minSdk) $facts.MinSdk "Release build metadata minSdk"
  Assert-StringEquals ([string]$metadata.targetSdk) $facts.TargetSdk "Release build metadata targetSdk"
  Assert-StringEquals (Normalize-Sha256 ([string]$metadata.signingCertificateSha256)) $facts.SigningCertificateSha256 "Release build metadata signingCertificateSha256"
}

<#
  Verifies the changelog producer metadata against the three emitted Markdown
  files. release-changelog.full.md is included even though only CHANGELOG.md and
  release-body.md are published, because it is the producer's original body.
#>
function Assert-ChangelogMetadataMatchesFiles {
  param([Parameter(Mandatory = $true)][string]$ChangelogDir)

  Assert-ExactFileSet $ChangelogDir @("CHANGELOG.md", "release-body.md", "release-changelog.full.md", "release-changelog-metadata.json") "release changelog artifact"

  $metadata = Read-JsonFile (Join-Path $ChangelogDir "release-changelog-metadata.json")
  if ([int]$metadata.schemaVersion -ne 1 -or $metadata.artifactKind -ne "release-changelog") {
    throw "Release changelog metadata has an unsupported schema."
  }

  $changelogPath = Join-Path $ChangelogDir "CHANGELOG.md"
  $releaseBodyPath = Join-Path $ChangelogDir "release-body.md"
  $fullChangelogPath = Join-Path $ChangelogDir "release-changelog.full.md"
  $changelog = Normalize-Text (Get-Content -LiteralPath $changelogPath -Raw)
  $releaseBody = Normalize-Text (Get-Content -LiteralPath $releaseBodyPath -Raw)
  $fullChangelog = Normalize-Text (Get-Content -LiteralPath $fullChangelogPath -Raw)

  if ($changelog -ne $releaseBody -or $changelog -ne $fullChangelog) {
    throw "Release changelog files drifted from the producer body."
  }

  Assert-StringEquals ([string]$metadata.changelogSha256) (Get-Sha256 $changelogPath) "Release changelog metadata changelogSha256"
  Assert-Int64Equals ([int64]$metadata.changelogSizeBytes) (Get-FileSize $changelogPath) "Release changelog metadata changelogSizeBytes"
  Assert-StringEquals ([string]$metadata.releaseBodySha256) (Get-Sha256 $releaseBodyPath) "Release changelog metadata releaseBodySha256"
  Assert-Int64Equals ([int64]$metadata.releaseBodySizeBytes) (Get-FileSize $releaseBodyPath) "Release changelog metadata releaseBodySizeBytes"
  Assert-Int64Equals ([int64]$metadata.bodyLengthCharacters) ([int64]$changelog.Length) "Release changelog metadata bodyLengthCharacters"
}

$buildDir = Get-EnvValue "RELEASE_BUILD_ARTIFACT_DIR"
$changelogDir = Get-EnvValue "RELEASE_CHANGELOG_DIR"

Assert-BuildMetadataMatchesApk $buildDir
Assert-ChangelogMetadataMatchesFiles $changelogDir

Write-Host "Release producer metadata source verification passed."
