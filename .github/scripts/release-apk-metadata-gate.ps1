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

$apkPath = Get-EnvValue "RELEASE_APK_PATH"
$outputPath = Get-EnvValue "RELEASE_APK_METADATA_PATH"

if (-not (Test-Path -LiteralPath $apkPath)) {
  throw "APK does not exist: $apkPath"
}

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

$metadata = [ordered]@{
  packageName = $packageName
  versionCode = $versionCode
  versionName = $versionName
  minSdk = $minSdk
  targetSdk = $targetSdk
}

[IO.File]::WriteAllText($outputPath, (($metadata | ConvertTo-Json -Depth 4) + "`n"), [Text.UTF8Encoding]::new($false))
Write-Host "APK metadata gate passed for $packageName $versionName ($versionCode)."
