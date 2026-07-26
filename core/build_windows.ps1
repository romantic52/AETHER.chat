# Пересборка нативного ядра под Windows (JVM-десктоп) + генерация Kotlin-биндингов.
# Требует: Rust (rustup, MSVC toolchain). SQLCipher-vendored собирается штатно.
# Запуск:  powershell -ExecutionPolicy Bypass -File core\build_windows.ps1
$ErrorActionPreference = "Stop"
$env:Path = "$env:USERPROFILE\.cargo\bin;$env:Path"

$core    = $PSScriptRoot
$desktop = Join-Path $core "..\desktop"
$natives = Join-Path $desktop "natives"
$kotlin  = Join-Path $desktop "src\main\kotlin"

Push-Location $core
try {
    Write-Host "== cargo build (host cdylib) =="
    cargo build --release

    Write-Host "== sm_core.dll -> desktop\natives =="
    New-Item -ItemType Directory -Force $natives | Out-Null
    Copy-Item "target\release\sm_core.dll" $natives -Force

    Write-Host "== uniffi-bindgen (Kotlin -> desktop) =="
    cargo run --release --bin uniffi-bindgen -- generate --library "target\release\sm_core.dll" --language kotlin --out-dir $kotlin

    Write-Host "OK: sm_core.dll в desktop\natives, биндинги в desktop\src\main\kotlin\uniffi\sm_core"
} finally {
    Pop-Location
}
