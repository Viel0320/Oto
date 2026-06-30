$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Assert-Condition {
  param(
    [Parameter(Mandatory = $true)][bool]$Condition,
    [Parameter(Mandatory = $true)][string]$Message
  )

  if (-not $Condition) {
    throw $Message
  }
}

function Get-JobBlock {
  param(
    [Parameter(Mandatory = $true)][string]$WorkflowText,
    [Parameter(Mandatory = $true)][string]$JobName
  )

  $escapedName = [regex]::Escape($JobName)
  $match = [regex]::Match($WorkflowText, "(?ms)^  ${escapedName}:`r?`n(?<body>.*?)(?=^  [A-Za-z0-9_-]+:`r?`n|\z)")
  Assert-Condition $match.Success "Missing workflow job: $JobName"
  return $match.Groups["body"].Value
}

$workflowPath = Join-Path (Get-Location) ".github/workflows/release-publish.yml"
$workflow = Get-Content -LiteralPath $workflowPath -Raw

Assert-Condition ($workflow -match "(?ms)^permissions:`r?`n  contents: read`r?`n") "Workflow-level permissions must stay contents: read."
Assert-Condition ($workflow -notmatch "(?ms)^permissions:`r?`n  contents: write`r?`n") "Workflow-level contents: write is forbidden."
Assert-Condition ($workflow -notmatch "(?m)^      tag_name:") "Manual tag_name input is forbidden."
Assert-Condition ($workflow -notmatch "(?m)^      release_name:") "Manual release_name input is forbidden."
Assert-Condition ($workflow -notmatch "(?m)^      generate_changelog:") "generate_changelog input is forbidden."

$metaJob = Get-JobBlock $workflow "workflow-meta-check"
$buildJob = Get-JobBlock $workflow "build-verify"
$publishJob = Get-JobBlock $workflow "publish"

Assert-Condition ($metaJob -match "runs-on: ubuntu-latest") "workflow-meta-check must run on ubuntu-latest."
Assert-Condition ($buildJob -match "runs-on: ubuntu-latest") "build-verify must run on ubuntu-latest."
Assert-Condition ($publishJob -match "runs-on: ubuntu-latest") "publish must run on ubuntu-latest."

Assert-Condition ($buildJob -match "(?ms)permissions:`r?`n      contents: read") "build-verify must use contents: read."
Assert-Condition ($publishJob -match "(?ms)permissions:`r?`n      contents: write") "publish must use contents: write."

Assert-Condition ($buildJob -match "persist-credentials: false") "build-verify checkout must disable persisted credentials."
Assert-Condition ($buildJob -match "fetch-depth: 0") "build-verify checkout must use full history."
Assert-Condition ($publishJob -match "persist-credentials: false") "publish checkout must disable persisted credentials."

Assert-Condition ($buildJob -match "Source Ref Gate") "build-verify must keep Source Ref Gate."
Assert-Condition ($buildJob -match "KEYSTORE_BASE64") "build-verify must own signing secret injection."
Assert-Condition ($publishJob -notmatch "KEYSTORE_|KEY_ALIAS|KEY_PASSWORD|SIGNING_CERTIFICATE|gradlew|assembleRelease|apksigner") "publish job must not access signing secrets, Gradle, Android build, or APK signing verification."

Assert-Condition ($buildJob -match "release-build-verify\.ps1") "build-verify must use release-build-verify.ps1."
Assert-Condition ($publishJob -match "release-publish-transaction\.ps1") "publish must use release-publish-transaction.ps1."

Write-Host "Release workflow static invariants passed."
