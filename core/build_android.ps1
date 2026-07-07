# Пересборка нативного ядра под Android + генерация Kotlin-биндингов.
# Требует: Rust (rustup), таргеты aarch64/x86_64-linux-android, cargo-ndk, Android NDK.
#   rustup target add aarch64-linux-android x86_64-linux-android
#   cargo install cargo-ndk
# Запуск:  powershell -ExecutionPolicy Bypass -File core\build_android.ps1
$ErrorActionPreference = "Stop"
$env:Path = "$env:USERPROFILE\.cargo\bin;$env:Path"
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
# берём первую установленную версию NDK
$ndk = Get-ChildItem "$env:ANDROID_HOME\ndk" -Directory | Sort-Object Name -Descending | Select-Object -First 1
$env:ANDROID_NDK_HOME = $ndk.FullName
Write-Host "NDK: $env:ANDROID_NDK_HOME"

$core = $PSScriptRoot
$jni  = Join-Path $core "..\android\app\src\main\jniLibs"
$java = Join-Path $core "..\android\app\src\main\java"

Push-Location $core
try {
    Write-Host "== cargo ndk build (.so -> jniLibs) =="
    cargo ndk -t arm64-v8a -t x86_64 -o $jni build --release

    Write-Host "== host cdylib (для генерации биндингов) =="
    cargo build --release

    Write-Host "== uniffi-bindgen (Kotlin) =="
    cargo run --bin uniffi-bindgen -- generate --library "target\release\sm_core.dll" --language kotlin --out-dir $java

    Write-Host "OK: .so в jniLibs, биндинги в java\uniffi\sm_core"
} finally {
    Pop-Location
}
