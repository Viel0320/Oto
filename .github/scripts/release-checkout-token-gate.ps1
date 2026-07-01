$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Assert-FileDoesNotContainTokenMaterial {
  param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)][string]$Description
  )

  if (-not (Test-Path -LiteralPath $Path)) {
    return
  }

  $content = Get-Content -LiteralPath $Path -Raw
  if ($content -match "(?i)x-access-token|github_pat_|ghp_|gho_|ghu_|ghs_|ghr_|oauth|authorization:") {
    throw "$Description contains persisted credential material: $Path"
  }
}

$gitConfigPath = Join-Path (Get-Location) ".git/config"
Assert-FileDoesNotContainTokenMaterial $gitConfigPath ".git/config"

$gitCredentialsPath = Join-Path (Get-Location) ".git-credentials"
Assert-FileDoesNotContainTokenMaterial $gitCredentialsPath ".git-credentials"

Write-Host "Checkout token gate passed."
