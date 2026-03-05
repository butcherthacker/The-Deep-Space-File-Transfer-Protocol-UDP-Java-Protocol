# DS-FTP Receiver Test Script
# Usage: .\run_receiver.ps1 <output_file> <RN>

param(
    [Parameter(Mandatory=$true)]
    [string]$OutputFile,
    
    [Parameter(Mandatory=$true)]
    [int]$RN
)

$receiverPath = Join-Path $PSScriptRoot "Receiver"
Set-Location $receiverPath

Write-Host "Starting Receiver..." -ForegroundColor Green
Write-Host "Output: $OutputFile, RN: $RN" -ForegroundColor Cyan

java Receiver 127.0.0.1 4321 1234 $OutputFile $RN
