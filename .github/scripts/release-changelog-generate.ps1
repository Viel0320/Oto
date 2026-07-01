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

function Write-Utf8NoBomFile {
  param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)][string]$Content
  )

  $parent = [IO.Path]::GetDirectoryName($Path)
  if (-not [string]::IsNullOrWhiteSpace($parent)) {
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
  }
  [IO.File]::WriteAllText($Path, $Content, [Text.UTF8Encoding]::new($false))
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

<#
  Resolves changelog bootstrap state inside the changelog job so release notes
  can be generated in parallel with version-tag-gate instead of waiting on it.
#>
function Get-ReleaseList {
  $output = & gh release list --limit 1000 --json tagName,isDraft,isPrerelease 2>&1
  if ($LASTEXITCODE -ne 0) {
    $joined = ($output | Out-String).Trim()
    throw "Failed to query GitHub Releases for changelog bootstrap detection.`n$joined"
  }

  $json = ($output | Out-String).Trim()
  if ([string]::IsNullOrWhiteSpace($json)) {
    return @()
  }
  return @($json | ConvertFrom-Json)
}

function Get-LatestStableReleaseTag {
  $repo = Get-EnvValue "GITHUB_REPOSITORY"
  $output = & gh api "repos/$repo/releases/latest" 2>&1
  if ($LASTEXITCODE -eq 0) {
    $json = ($output | Out-String).Trim()
    if ([string]::IsNullOrWhiteSpace($json)) {
      return ""
    }

    $release = $json | ConvertFrom-Json
    if ($release.draft -or $release.prerelease) {
      throw "GitHub Latest release must point to a published stable release for auto changelog generation."
    }
    return [string]$release.tag_name
  }

  $joined = ($output | Out-String).Trim()
  if ($joined -match "HTTP 404" -or $joined -match "Not Found") {
    return ""
  }
  throw "Failed to query GitHub Latest release for changelog baseline.`n$joined"
}

function ConvertTo-SafeMarkdownListText {
  param([Parameter(Mandatory = $true)][string]$Value)
  return (($Value -replace "&", "&amp;") -replace "<", "&lt;") -replace ">", "&gt;"
}

<#
  Keeps release-body links constrained before they are published verbatim.
  Bare links are allowed only when they point at GitHub release pages or assets.
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

function Test-MergeNoiseTitle {
  param([Parameter(Mandatory = $true)][string]$Title)

  $value = $Title.Trim()
  return ($value -match "(?i)^Merge pull request #\d+ from .+$" -or
          $value -match "(?i)^Merge branch .+$" -or
          $value -match "(?i)^Merge remote-tracking branch .+$" -or
          $value -match "(?i)^Merge tag .+$" -or
          $value -match "(?i)^Merge commit .+$")
}

function Test-GitHubLogin {
  param([Parameter(Mandatory = $true)][string]$Value)
  return $Value -match "^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$"
}

function Get-MaintainerLogin {
  $owner = Get-EnvValue "GITHUB_REPOSITORY_OWNER" $false
  if ([string]::IsNullOrWhiteSpace($owner)) {
    $repo = Get-EnvValue "GITHUB_REPOSITORY"
    $parts = $repo -split "/", 2
    if ($parts.Count -ne 2 -or [string]::IsNullOrWhiteSpace($parts[0])) {
      throw "Failed to resolve maintainer login from GITHUB_REPOSITORY: $repo"
    }
    $owner = $parts[0]
  }

  $login = ($owner.Trim() -replace "^@", "")
  if (-not (Test-GitHubLogin $login)) {
    throw "Resolved maintainer login has an invalid GitHub username format: $login"
  }
  return $login
}

function Normalize-ContributorHandle {
  param(
    [Parameter(Mandatory = $true)][string]$Handle,
    [Parameter(Mandatory = $true)][string]$MaintainerLogin
  )

  $handle = ($Handle.Trim() -replace "^@", "")
  if ([string]::IsNullOrWhiteSpace($handle)) {
    return ""
  }
  if ([string]::Equals($handle, $MaintainerLogin, [StringComparison]::OrdinalIgnoreCase)) {
    return ""
  }
  if (-not (Test-GitHubLogin $handle)) {
    throw "Resolved contributor handle has an invalid GitHub username format: $handle"
  }
  return "@$handle"
}

function Get-CommitContributorHandles {
  param(
    [Parameter(Mandatory = $true)][string]$BaseRef,
    [Parameter(Mandatory = $true)][string]$HeadRef,
    [Parameter(Mandatory = $true)][string]$MaintainerLogin
  )

  $repo = Get-EnvValue "GITHUB_REPOSITORY"
  $output = & gh api --paginate "repos/$repo/compare/$($BaseRef)...$HeadRef?per_page=100" --jq '.commits[] | [.sha, (.author.login // .committer.login // "")] | @tsv' 2>&1
  if ($LASTEXITCODE -ne 0) {
    $joined = ($output | Out-String).Trim()
    throw "Failed to resolve contributor handles for changelog range '$BaseRef...$HeadRef'.`n$joined"
  }

  $handles = @{}
  foreach ($row in @($output)) {
    $line = ([string]$row).Trim()
    if ([string]::IsNullOrWhiteSpace($line)) {
      continue
    }
    $parts = $line -split "`t", 2
    if ($parts.Count -eq 0 -or [string]::IsNullOrWhiteSpace($parts[0])) {
      throw "Failed to parse contributor handle row: $line"
    }

    $sha = $parts[0].Trim()
    $rawHandle = if ($parts.Count -eq 2) { $parts[1].Trim() } else { "" }
    $handle = Normalize-ContributorHandle $rawHandle $MaintainerLogin
    if (-not [string]::IsNullOrWhiteSpace($handle)) {
      $handles[$sha] = $handle
    }
  }
  return $handles
}

function Get-CommitCategory {
  param([Parameter(Mandatory = $true)][string]$Title)

  $value = $Title.Trim()
  $lower = $value.ToLowerInvariant()
  if ($lower -match "^(feat|feature)(\([^)]+\))?!?:" -or $lower -match "\b(add|new)\b") { return "New Features" }
  if ($lower -match "^(perf|improve|improvement|enhance|enhancement)(\([^)]+\))?!?:" -or $lower -match "\b(improve|improves|improved|enhance|enhances|enhanced|optimize|optimise)\b") { return "Improvements" }
  if ($lower -match "^(fix|bugfix|hotfix)(\([^)]+\))?!?:" -or $lower -match "\b(crash|bug|fix)\b") { return "Fixes" }
  if ($lower -match "^(docs?|documentation|i18n|l10n|translation|translations|locale|locales)(\([^)]+\))?!?:" -or $lower -match "\b(doc|docs|documentation|translation|translations|locale|locales)\b") { return "Docs & Translations" }
  return "Changes"
}

$outputDir = Get-EnvValue "RELEASE_CHANGELOG_OUTPUT_DIR"
$channel = Get-EnvValue "RELEASE_CHANNEL"
$manualChangelog = Normalize-Text (Get-EnvValue "RELEASE_MANUAL_CHANGELOG" $false)
$highlights = Normalize-Text (Get-EnvValue "RELEASE_HIGHLIGHTS" $false)
$maintainerLogin = Get-MaintainerLogin

$manualTrimmed = $manualChangelog.Trim()
$highlightsTrimmed = $highlights.Trim()
if ($channel -ne "stable" -and $channel -ne "prerelease") {
  throw "Invalid release channel for changelog generation: $channel"
}

$releaseList = Get-ReleaseList
$hasNewFormatStable = @($releaseList | Where-Object { -not $_.isDraft -and $_.tagName -match "^stable-\d{2}\.\d{2}\.\d{2}-\d+$" }).Count -gt 0
$isStableManifestBootstrap = -not $hasNewFormatStable

# Stable bootstrap intentionally has no trusted automatic release-note baseline yet.
if ($isStableManifestBootstrap -and $manualTrimmed.Length -eq 0) {
  throw "Stable manifest bootstrap requires manual_changelog."
}
if ($isStableManifestBootstrap -and $channel -eq "prerelease") {
  throw "The first manifest release must be stable; prerelease cannot bootstrap the update manifest."
}
if ($manualTrimmed.Length -gt 0 -and $highlightsTrimmed.Length -gt 0) {
  throw "highlights must be empty when manual_changelog is provided."
}

if ($manualTrimmed.Length -gt 0) {
  if ($manualTrimmed -match "(?is)<\s*(script|iframe|img)\b" -or $manualTrimmed -match "!\[[^\]]*\]\(") {
    throw "manual_changelog contains unsupported raw HTML or image content."
  }
  Assert-AllowedRawLinks $manualTrimmed "manual_changelog"
  $changelog = $manualTrimmed.TrimEnd() + "`n"
} else {
  $notes = [System.Collections.Generic.List[string]]::new()

  if ($highlightsTrimmed.Length -gt 0) {
    Assert-AllowedRawLinks $highlightsTrimmed "highlights"
    $notes.Add("## Highlights")
    $notes.Add("")
    $notes.Add($highlightsTrimmed.TrimEnd())
    $notes.Add("")
  }

  $notes.Add("## What's Changed")
  $notes.Add("")
  $previousStableTag = Get-LatestStableReleaseTag
  if ([string]::IsNullOrWhiteSpace($previousStableTag)) {
    throw "Auto changelog requires the GitHub Latest stable baseline tag."
  }

  $targetCommit = Get-EnvValue "GITHUB_SHA"

  Invoke-CheckedCommand "git" @("rev-parse", "--verify", "refs/tags/$previousStableTag^{commit}") "GitHub Latest stable release tag '$previousStableTag' is missing from checkout." | Out-Null
  Invoke-CheckedCommand "git" @("merge-base", "--is-ancestor", "refs/tags/$previousStableTag^{commit}", $targetCommit) "GitHub Latest stable release tag '$previousStableTag' is not an ancestor of the current release commit." | Out-Null
  $notes.Add("Changes since ``$previousStableTag``:")
  $commitRecords = Invoke-CheckedCommand "git" @("log", "$previousStableTag..$targetCommit", "--pretty=format:%H%x1f%s") "Failed to read release changelog commits."
  $contributorHandles = Get-CommitContributorHandles $previousStableTag $targetCommit $maintainerLogin
  $notes.Add("")

  $sectionOrder = @(
    "New Features",
    "Improvements",
    "Changes",
    "Fixes",
    "Docs & Translations"
  )
  $sections = @{}
  foreach ($section in $sectionOrder) {
    $sections[$section] = [System.Collections.Generic.List[string]]::new()
  }

  $commitRecords = @($commitRecords | ForEach-Object { $_.Trim() } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
  foreach ($record in $commitRecords) {
    $parts = $record -split ([string][char]31), 2
    if ($parts.Count -ne 2 -or [string]::IsNullOrWhiteSpace($parts[0]) -or [string]::IsNullOrWhiteSpace($parts[1])) {
      throw "Failed to parse changelog commit record: $record"
    }

    $sha = $parts[0].Trim()
    $title = $parts[1].Trim()
    if (Test-MergeNoiseTitle $title) {
      continue
    }

    $category = Get-CommitCategory $title
    $entry = ConvertTo-SafeMarkdownListText $title
    $handle = if ($contributorHandles.ContainsKey($sha)) { $contributorHandles[$sha] } else { "" }
    if (-not [string]::IsNullOrWhiteSpace($handle)) {
      $entry = "$entry ($handle)"
    }
    $sections[$category].Add("- $entry")
  }

  foreach ($section in $sectionOrder) {
    $notes.Add("### $section")
    $notes.Add("")
    if ($sections[$section].Count -eq 0) {
      $notes.Add("- No changes.")
    } else {
      foreach ($line in $sections[$section]) {
        $notes.Add($line)
      }
    }
    $notes.Add("")
  }

  $changelog = (($notes -join "`n").TrimEnd()) + "`n"
}

Assert-AllowedRawLinks $changelog "CHANGELOG.md"
if ($changelog.Length -gt 125000) {
  throw "GitHub Release body exceeds the platform length limit."
}

$fullChangelogPath = Join-Path $outputDir "release-changelog.full.md"
Write-Utf8NoBomFile $fullChangelogPath $changelog
Copy-Item -LiteralPath $fullChangelogPath -Destination (Join-Path $outputDir "CHANGELOG.md") -Force
Copy-Item -LiteralPath $fullChangelogPath -Destination (Join-Path $outputDir "release-body.md") -Force
Write-Host "Generated changelog for $channel release."
