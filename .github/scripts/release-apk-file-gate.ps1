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

$inputDir = Get-EnvValue "RELEASE_APK_INPUT_DIR"
$outputDir = Get-EnvValue "RELEASE_APK_OUTPUT_DIR"

if (-not (Test-Path -LiteralPath $inputDir)) {
  throw "APK input directory does not exist: $inputDir"
}

$items = @(Get-ChildItem -LiteralPath $inputDir -Force)
foreach ($item in $items) {
  if ($item.PSIsContainer) {
    throw "APK artifact contains a directory: $($item.Name)"
  }
  if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw "APK artifact contains a reparse point or symlink: $($item.Name)"
  }
}

$apks = @($items | Where-Object { -not $_.PSIsContainer -and $_.Name -like "*.apk" })
if ($apks.Count -ne 1) {
  throw "Expected exactly one release APK artifact, found $($apks.Count)."
}

New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$outputPath = Join-Path $outputDir "release.apk"
Copy-Item -LiteralPath $apks[0].FullName -Destination $outputPath -Force

Write-StepOutput "apk_path" $outputPath
Write-Host "APK file gate passed for $($apks[0].Name)."
