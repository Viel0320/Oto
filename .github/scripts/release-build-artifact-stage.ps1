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

function Normalize-Sha256 {
  param([Parameter(Mandatory = $true)][string]$Value)
  return ($Value -replace "[:\s]", "").ToLowerInvariant()
}

<#
  Runs Android command-line tools with uniform error handling so the producer
  metadata records only values that were successfully read from the final APK.
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
  Locates Android build tools from the SDK installed in the current runner.
  The release build job is the artifact producer, so this lookup keeps APK
  metadata extraction in the same job that produced the APK.
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

<#
  Reads package, version, and SDK values from the APK body rather than from
  Gradle logs or file names. aapt is preferred when available, with aapt2 as
  the fallback used by newer Android build tool installations.
#>
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
  Blocks the Android debug certificate at the producer boundary before the
  artifact is passed to later read-only jobs. The unified verify job still
  checks the expected release certificate hash from repository configuration.
#>
function Assert-NotDebugSigningCertificate {
  param(
    [Parameter(Mandatory = $true)][string]$CertificateText,
    [Parameter(Mandatory = $true)][string]$CertificateSha256
  )

  $knownDebugCertificateSubjects = @(
    "CN\s*=\s*Android Debug",
    "O\s*=\s*Android",
    "C\s*=\s*US"
  )

  $matchedDebugSubjects = @($knownDebugCertificateSubjects | Where-Object { $CertificateText -match $_ })
  if ($matchedDebugSubjects.Count -eq $knownDebugCertificateSubjects.Count) {
    throw "APK uses the Android debug signing certificate subject."
  }

  if ($CertificateText -match "(?i)androiddebugkey") {
    throw "APK uses the Android debug signing key alias."
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
      if ($CertificateSha256 -eq $deniedHash) {
        throw "APK signing certificate SHA-256 is in the debug certificate denylist."
      }
    }
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
      throw "Release build artifact contains a directory: $($item.Name)"
    }
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
      throw "Release build artifact contains a reparse point or symlink: $($item.Name)"
    }
  }

  $actual = @($items | ForEach-Object { $_.Name } | Sort-Object)
  $expected = @($ExpectedNames | Sort-Object)
  if ($null -ne (Compare-Object -ReferenceObject $expected -DifferenceObject $actual)) {
    throw "Unexpected release build artifact file set. Expected: $($expected -join ', '). Actual: $($actual -join ', ')."
  }
}

$inputDir = Get-EnvValue "RELEASE_APK_INPUT_DIR"
$outputDir = Get-EnvValue "RELEASE_BUILD_ARTIFACT_DIR"

if (-not (Test-Path -LiteralPath $inputDir)) {
  throw "APK input directory does not exist: $inputDir"
}
if (Test-Path -LiteralPath $outputDir) {
  $existingOutput = @(Get-ChildItem -LiteralPath $outputDir -Force)
  if ($existingOutput.Count -gt 0) {
    throw "Release build artifact output directory must be empty: $outputDir"
  }
} else {
  New-Item -ItemType Directory -Path $outputDir | Out-Null
}

$inputItems = @(Get-ChildItem -LiteralPath $inputDir -Force)
foreach ($item in $inputItems) {
  if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw "Release APK output contains a reparse point or symlink: $($item.Name)"
  }
}

$apks = @($inputItems | Where-Object { -not $_.PSIsContainer -and $_.Name -like "*.apk" })
if ($apks.Count -ne 1) {
  throw "Expected exactly one release APK output, found $($apks.Count)."
}

$apkPath = Join-Path $outputDir "release.apk"
Copy-Item -LiteralPath $apks[0].FullName -Destination $apkPath -Force

$badging = Read-BadgingOutput $apkPath
$packageLine = Select-RequiredLine $badging "^package:" "package"
if ($packageLine -notmatch "name='([^']+)'\s+versionCode='([^']+)'\s+versionName='([^']+)'") {
  throw "Failed to parse APK package metadata: $packageLine"
}
$packageName = $Matches[1]
$versionCode = $Matches[2]
$versionName = $Matches[3]

$sdkLine = Select-RequiredLine $badging "^sdkVersion:" "minSdk"
if ($sdkLine -notmatch "sdkVersion:'([^']+)'") {
  throw "Failed to parse APK minSdk metadata: $sdkLine"
}
$minSdk = $Matches[1]

$targetSdkLine = Select-RequiredLine $badging "^targetSdkVersion:" "targetSdk"
if ($targetSdkLine -notmatch "targetSdkVersion:'([^']+)'") {
  throw "Failed to parse APK targetSdk metadata: $targetSdkLine"
}
$targetSdk = $Matches[1]

$apksigner = Find-AndroidTool "apksigner"
$certOutput = Invoke-CheckedCommand $apksigner @("verify", "--print-certs", $apkPath) "APK signing verification failed."
$certText = ($certOutput | Out-String).Trim()
$certLine = @($certOutput | Where-Object { $_ -match "SHA-256 digest:\s*([0-9A-Fa-f:]+)" } | Select-Object -First 1)
if ($certLine.Count -eq 0 -or $certLine[0] -notmatch "SHA-256 digest:\s*([0-9A-Fa-f:]+)") {
  throw "Failed to parse APK signing certificate SHA-256.`n$certText"
}
$signingCertificateSha256 = Normalize-Sha256 $Matches[1]
Assert-NotDebugSigningCertificate $certText $signingCertificateSha256

$metadataPath = Join-Path $outputDir "release-build-metadata.json"
$metadata = [ordered]@{
  schemaVersion = 1
  artifactKind = "release-build"
  sourceApkFileName = $apks[0].Name
  apkFileName = "release.apk"
  apkSha256 = Get-Sha256 $apkPath
  apkSizeBytes = Get-FileSize $apkPath
  packageName = $packageName
  versionCode = $versionCode
  versionName = $versionName
  minSdk = $minSdk
  targetSdk = $targetSdk
  signingCertificateSha256 = $signingCertificateSha256
  targetCommit = Get-EnvValue "GITHUB_SHA"
  createdByRunId = Get-EnvValue "GITHUB_RUN_ID"
  createdByRunAttempt = Get-EnvValue "GITHUB_RUN_ATTEMPT" $false
}
Write-JsonFile $metadataPath $metadata

Assert-ExactFileSet $outputDir @("release.apk", "release-build-metadata.json")
Write-StepOutput "artifact_dir" $outputDir
Write-Host "Release build artifact staged for $packageName $versionName ($versionCode)."
