# DS-FTP Sender Test Script
# Usage: .\run_sender.ps1 <input_file> <timeout_ms>

param(
    [Parameter(Mandatory=$true)]
    [string]$InputFile,
    
    [Parameter(Mandatory=$false)]
    [int]$TimeoutMs = 1000
)

$senderPath = Join-Path $PSScriptRoot "Sender"
Set-Location $senderPath

# Resolve input file path relative to Assignment 2 folder
$inputPath = Join-Path $PSScriptRoot $InputFile

Write-Host "Starting Sender..." -ForegroundColor Green
Write-Host "Input: $inputPath" -ForegroundColor Cyan
Write-Host "Timeout: $TimeoutMs ms" -ForegroundColor Cyan

java Sender 127.0.0.1 1234 4321 $inputPath $TimeoutMs
