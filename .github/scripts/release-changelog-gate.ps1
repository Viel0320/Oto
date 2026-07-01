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

function Read-JsonFile {
  param([Parameter(Mandatory = $true)][string]$Path)
  return (Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json)
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

<#
  Revalidates raw links at the final changelog gate because both the generated
  CHANGELOG.md and release-body.md are later copied into the publish handoff.
#>
function Assert-AllowedRawLinks {
  param(
    [Parameter(Mandatory = $true)][string]$Value,
    [Parameter(Mandatory = $true)][string]$Description
  )

  $matches = [regex]::Matches($Value, "(?i)\bhttps?://[^\s<>\]\)]+")
  foreach ($match in $matches) {
    $url = $match.Value.TrimEnd([char[]]".,;:!?")
    if ($url -notmatch "^https://github\.com/[^/\s]+/[^/\s]+/releases(?:$|/tag/[^/\s]+$|/download/[^/\s]+/[^/\s]+$)") {
      throw "$Description contains an unsupported raw link: $url"
    }
  }
}

function Assert-HeadingsInOrder {
  param(
    [Parameter(Mandatory = $true)][string]$Text,
    [Parameter(Mandatory = $true)][string[]]$Headings
  )

  $lastIndex = -1
  foreach ($heading in $Headings) {
    $index = $Text.IndexOf($heading, [StringComparison]::Ordinal)
    if ($index -lt 0) {
      throw "Generated changelog is missing required heading: $heading"
    }
    if ($index -le $lastIndex) {
      throw "Generated changelog headings are not in the required order."
    }
    $lastIndex = $index
  }
}

$versionMetadata = Read-JsonFile (Get-EnvValue "RELEASE_VERSION_METADATA_PATH")
$changelogDir = Get-EnvValue "RELEASE_CHANGELOG_DIR"
$changelogPath = Join-Path $changelogDir "CHANGELOG.md"
$releaseBodyPath = Join-Path $changelogDir "release-body.md"

if (-not (Test-Path -LiteralPath $changelogPath)) {
  throw "CHANGELOG.md was not generated."
}
if (-not (Test-Path -LiteralPath $releaseBodyPath)) {
  throw "release-body.md was not generated."
}

$changelog = Normalize-Text (Get-Content -LiteralPath $changelogPath -Raw)
$releaseBody = Normalize-Text (Get-Content -LiteralPath $releaseBodyPath -Raw)
if ([string]::IsNullOrWhiteSpace($changelog)) {
  throw "CHANGELOG.md must not be empty."
}
if ($changelog -ne $releaseBody) {
  throw "Release body does not match CHANGELOG.md."
}
if ($changelog.Length -gt 125000) {
  throw "GitHub Release body exceeds the platform length limit."
}
if ($changelog -match "(?is)<\s*(script|iframe|img)\b" -or $changelog -match "!\[[^\]]*\]\(") {
  throw "Generated changelog contains unsupported raw HTML or image content."
}
Assert-AllowedRawLinks $changelog "CHANGELOG.md"

$manualChangelog = Normalize-Text (Get-EnvValue "RELEASE_MANUAL_CHANGELOG" $false)
$manualTrimmed = $manualChangelog.Trim()
$bootstrapProperty = $versionMetadata.PSObject.Properties["isStableManifestBootstrap"]
$isStableManifestBootstrap = $false
if ($null -ne $bootstrapProperty) {
  $isStableManifestBootstrap = [bool]$bootstrapProperty.Value
}

# Stable bootstrap must be an explicit manual release note decision, not an inferred range.
if ($isStableManifestBootstrap -and $manualTrimmed.Length -eq 0) {
  throw "Stable manifest bootstrap requires manual_changelog."
}
if ($manualTrimmed.Length -eq 0) {
  $previousStableTag = [string]$versionMetadata.previousStableTag
  if ([string]::IsNullOrWhiteSpace($previousStableTag)) {
    throw "Auto changelog requires the GitHub Latest stable baseline tag."
  }
  $targetCommit = [string]$versionMetadata.targetCommit
  if ([string]::IsNullOrWhiteSpace($targetCommit)) {
    $targetCommit = Get-EnvValue "GITHUB_SHA"
  }
  Invoke-CheckedCommand "git" @("rev-parse", "--verify", "refs/tags/$previousStableTag^{commit}") "GitHub Latest stable release tag '$previousStableTag' is missing from checkout." | Out-Null
  Invoke-CheckedCommand "git" @("merge-base", "--is-ancestor", "refs/tags/$previousStableTag^{commit}", $targetCommit) "GitHub Latest stable release tag '$previousStableTag' is not an ancestor of the current release commit." | Out-Null

  if ($changelog -notmatch "(?m)^## What's Changed$") {
    throw "Generated changelog is missing the What's Changed section."
  }
  Assert-HeadingsInOrder $changelog @(
    "## What's Changed",
    "### New Features",
    "### Improvements",
    "### Changes",
    "### Fixes",
    "### Docs & Translations"
  )
  if ($changelog -match "(?m)^### (Features|Dependencies, Build, And Release|Documentation|Tests|Internal Maintenance|Other Changes)$") {
    throw "Generated changelog contains a category heading outside the release plan taxonomy."
  }
}

Write-Host "Changelog gate passed for $($versionMetadata.tagName)."
