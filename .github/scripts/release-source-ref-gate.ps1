$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$allowedRefs = @("refs/heads/main", "refs/heads/master")
if (-not ($allowedRefs -contains $env:GITHUB_REF)) {
  throw "Release publishing is only allowed from refs/heads/main or refs/heads/master. Current ref: $env:GITHUB_REF"
}

if ($env:GITHUB_EVENT_NAME -ne "workflow_dispatch") {
  throw "Release publishing must be started by workflow_dispatch. Current event: $env:GITHUB_EVENT_NAME"
}

Write-Host "Source ref gate passed for $env:GITHUB_REF."
