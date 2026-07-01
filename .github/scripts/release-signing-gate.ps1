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

function Normalize-Sha256 {
  param([Parameter(Mandatory = $true)][string]$Value)
  return ($Value -replace "[:\s]", "").ToLowerInvariant()
}

<#
  Detects Android debug signing independently from the configured release
  certificate hash. This prevents a misconfigured SIGNING_CERTIFICATE_SHA256
  variable from turning the debug certificate into an accepted release signer.
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

$apkPath = Get-EnvValue "RELEASE_APK_PATH"
$outputPath = Get-EnvValue "RELEASE_SIGNING_METADATA_PATH"
$expectedSha256 = Normalize-Sha256 (Get-EnvValue "RELEASE_EXPECTED_SIGNING_CERT_SHA256")

if ($expectedSha256 -notmatch "^[0-9a-f]{64}$") {
  throw "RELEASE_EXPECTED_SIGNING_CERT_SHA256 must be a 64-character SHA-256 hex digest."
}
if (-not (Test-Path -LiteralPath $apkPath)) {
  throw "APK does not exist: $apkPath"
}

$apksigner = Find-AndroidTool "apksigner"
$certOutput = Invoke-CheckedCommand $apksigner @("verify", "--print-certs", $apkPath) "APK signing verification failed."
$certText = ($certOutput | Out-String).Trim()
$certLine = @($certOutput | Where-Object { $_ -match "SHA-256 digest:\s*([0-9A-Fa-f:]+)" } | Select-Object -First 1)
if ($certLine.Count -eq 0 -or $certLine[0] -notmatch "SHA-256 digest:\s*([0-9A-Fa-f:]+)") {
  throw "Failed to parse APK signing certificate SHA-256.`n$certText"
}

$actualSha256 = Normalize-Sha256 $Matches[1]
Assert-NotDebugSigningCertificate $certText $actualSha256
if ($actualSha256 -ne $expectedSha256) {
  throw "APK signing certificate SHA-256 does not match expected certificate."
}

$metadata = [ordered]@{
  signingCertificateSha256 = $actualSha256
}
[IO.File]::WriteAllText($outputPath, (($metadata | ConvertTo-Json -Depth 4) + "`n"), [Text.UTF8Encoding]::new($false))
Write-Host "Signing gate passed for $actualSha256."
