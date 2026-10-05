[CmdletBinding()]
param(
    [ValidateSet('app-image', 'msi')][string]$Type = 'app-image',
    [string]$Version = '0.9.0',
    [string]$WhisperModel = '',
    [string]$WhisperExecutable = '',
    [string]$LlamaExecutable = ''
)
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT' -or -not [Environment]::Is64BitOperatingSystem) {
    throw 'Build on Windows 10/11 x64 with JDK 21, CMake and Visual Studio C++ tools.'
}
$Root = Split-Path $PSScriptRoot -Parent
$Build = Join-Path $Root 'build\windows-package'
$InputDir = Join-Path $Build 'input'
$OutputDir = Join-Path $Build 'output'
function Invoke-Checked([string]$Command, [string[]]$Arguments) {
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Command failed with exit code $LASTEXITCODE" }
}
foreach ($tool in @('java', 'jpackage', 'cmake')) { Get-Command $tool -ErrorAction Stop | Out-Null }
$javaVersion = (& java --version | Out-String)
if ($javaVersion -notmatch '(?m)^(openjdk|java) 21(?:[.\s])') { throw 'JDK 21 must be first on PATH.' }
if (Test-Path $InputDir) { Remove-Item $InputDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $InputDir, $OutputDir | Out-Null
Push-Location $Root
try {
    Invoke-Checked '.\gradlew.bat' @('--no-daemon', ':app:installDist', '-PjavafxPlatform=win')
    Invoke-Checked 'cmake' @('-S', 'capture-windows', '-B', "$Build\capture", '-G', 'Visual Studio 17 2022', '-A', 'x64')
    Invoke-Checked 'cmake' @('--build', "$Build\capture", '--config', 'Release')
    Invoke-Checked 'ctest' @('--test-dir', "$Build\capture", '-C', 'Release', '--output-on-failure')
    Copy-Item "$Root\app\build\install\app\lib\*" $InputDir -Recurse
    Copy-Item "$Build\capture\Release\meetscribe-capture.exe" $InputDir
    Copy-Item "$Root\templates" $InputDir -Recurse
    Copy-Item "$PSScriptRoot\THIRD_PARTY_NOTICES.txt" $InputDir
    Copy-Item "$PSScriptRoot\third-party-licenses" "$InputDir\licenses" -Recurse
    # MSI numeric version 0.9.0 corresponds to beta source version 0.9.0-beta.1.
    # Supply the reviewed third-party notices directory alongside runtime assets.
    # Optional files are supplied locally. No model or runtime downloads take place here.
    if ($WhisperExecutable) {
        $whisperDir = Join-Path $InputDir 'native\whisper'
        New-Item -ItemType Directory -Force $whisperDir | Out-Null
        Copy-Item $WhisperExecutable "$whisperDir\whisper-cli.exe"
        Get-ChildItem (Split-Path $WhisperExecutable -Parent) -Filter '*.dll' | Copy-Item -Destination $whisperDir
    }
    if ($LlamaExecutable) {
        $llamaDir = Join-Path $InputDir 'native\llama'
        New-Item -ItemType Directory -Force $llamaDir | Out-Null
        Copy-Item $LlamaExecutable "$llamaDir\llama-completion.exe"
        Get-ChildItem (Split-Path $LlamaExecutable -Parent) -Filter '*.dll' | Copy-Item -Destination $llamaDir
    }
    if ($WhisperModel) {
        New-Item -ItemType Directory -Force "$InputDir\models" | Out-Null
        Copy-Item $WhisperModel "$InputDir\models\whisper.bin"
    }
    $mainJars = @(Get-ChildItem $InputDir -Filter 'app-*.jar')
    if ($mainJars.Count -ne 1) { throw 'Expected exactly one app-*.jar in distribution.' }
    $packageArgs = @('--type', $Type, '--name', 'MeetScribe', '--app-version', $Version,
        '--vendor', 'MeetScribe contributors', '--input', $InputDir, '--dest', $OutputDir,
        '--main-jar', $mainJars[0].Name, '--main-class', 'io.meetscribe.app.Launcher',
        '--java-options', '-Dmeetscribe.capture=$APPDIR/meetscribe-capture.exe')
    if ($WhisperExecutable) { $packageArgs += @('--java-options', '-Dmeetscribe.whisper=$APPDIR/native/whisper/whisper-cli.exe') }
    if ($WhisperModel) { $packageArgs += @('--java-options', '-Dmeetscribe.sttModel=$APPDIR/models/whisper.bin') }
    if ($LlamaExecutable) { $packageArgs += @('--java-options', '-Dmeetscribe.llama=$APPDIR/native/llama/llama-completion.exe') }
    if ($Type -eq 'msi') {
        $packageArgs += @('--win-per-user-install', '--win-menu', '--win-shortcut',
            '--win-upgrade-uuid', '83bfa4bb-bfa7-409f-8f22-ab85bcfa6fb1')
    }
    Invoke-Checked 'jpackage' $packageArgs
    Write-Host "Unsigned development package created in $OutputDir"
} finally { Pop-Location }
