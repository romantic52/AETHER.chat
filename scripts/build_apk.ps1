$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptRoot
Set-Location $projectRoot

Write-Host "=== Starting Android Build (APK) ==="

$androidDir = Join-Path $projectRoot "android"
if (-not (Test-Path $androidDir)) {
    Write-Error "Android project directory not found at $androidDir"
    exit 1
}

Set-Location $androidDir

Write-Host "1. Running gradlew assembleDebug..."
cmd.exe /c "gradlew.bat assembleDebug"

if ($LASTEXITCODE -ne 0) {
    Write-Error "Gradle build failed!"
    exit 1
}

Write-Host "2. Locating compiled APK..."
$apkPath = Join-Path $androidDir "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apkPath)) {
    Write-Error "APK file not found at $apkPath"
    exit 1
}

$destPath = Join-Path $projectRoot "secure-messenger-debug.apk"
Write-Host "3. Copying APK to project root: $destPath"
Copy-Item -Path $apkPath -Destination $destPath -Force

Write-Host "4. Finished successfully!"
Write-Host "Android APK is located at: $destPath"
