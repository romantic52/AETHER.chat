# Пересборка нативного ядра под Android + генерация Kotlin-биндингов.
# Требует: Rust (rustup), таргеты aarch64/x86_64-linux-android, cargo-ndk, Android NDK.
#   rustup target add aarch64-linux-android x86_64-linux-android
#   cargo install cargo-ndk
# Запуск:  powershell -ExecutionPolicy Bypass -File core\build_android.ps1
$ErrorActionPreference = "Stop"
$env:Path = "$env:USERPROFILE\.cargo\bin;$env:Path"
$strawberryRoot = "C:\Strawberry\perl"
$gitPerl = "C:\Program Files\Git\usr\bin"
$localeModule = "$strawberryRoot\lib\Locale\Maketext\Simple.pm"
if ((Test-Path "$gitPerl\perl.exe") -and (Test-Path $localeModule)) {
    $perlCompatRoot = Join-Path $env:TEMP "aether-perl5"
    $perlCompatModule = Join-Path $perlCompatRoot "Locale\Maketext"
    $perlCompatExtUtils = Join-Path $perlCompatRoot "ExtUtils"
    $perlCompatPod = Join-Path $perlCompatRoot "Pod"
    New-Item -ItemType Directory -Force -Path $perlCompatModule, $perlCompatExtUtils, $perlCompatPod | Out-Null
    Copy-Item -LiteralPath $localeModule -Destination $perlCompatModule -Force
    Copy-Item -Path "$strawberryRoot\lib\ExtUtils\*" -Destination $perlCompatExtUtils -Recurse -Force
    Copy-Item -Path "$strawberryRoot\lib\Pod\*" -Destination $perlCompatPod -Recurse -Force
    $perlCompatUnix = (& "$gitPerl\cygpath.exe" -u $perlCompatRoot).Trim()
    $makeCompatRoot = Join-Path $env:TEMP "aether-make"
    New-Item -ItemType Directory -Force -Path $makeCompatRoot | Out-Null
    Copy-Item -LiteralPath "C:\Strawberry\c\bin\gmake.exe" -Destination (Join-Path $makeCompatRoot "make.exe") -Force
    $env:Path = "$makeCompatRoot;C:\Strawberry\c\bin;$gitPerl;$env:Path"
    $env:PERL5LIB = $perlCompatUnix
    $env:MSYS2_ENV_CONV_EXCL = "PERL5LIB"
    $env:MSYSTEM = "MINGW64"
} elseif (Test-Path "$strawberryRoot\bin\perl.exe") {
    $env:Path = "$strawberryRoot\bin;$env:Path"
}
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
    if ($LASTEXITCODE -ne 0) { throw "cargo ndk build failed ($LASTEXITCODE)" }


    Write-Host "== uniffi-bindgen (Kotlin) =="
    cargo run --release --manifest-path "tools\uniffi-bindgen\Cargo.toml" -- generate --library "target\x86_64-linux-android\release\libsm_core.so" --language kotlin --out-dir $java
    if ($LASTEXITCODE -ne 0) { throw "uniffi-bindgen failed ($LASTEXITCODE)" }

    Write-Host "OK: .so в jniLibs, биндинги в java\uniffi\sm_core"
} finally {
    Pop-Location
}
