#!/usr/bin/env bash
# Пересборка Android-библиотек и Kotlin-биндингов на macOS.
set -euo pipefail

api=24
core_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
jni_dir="$core_dir/../android/app/src/main/jniLibs"
java_dir="$core_dir/../android/app/src/main/java"

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "Этот скрипт предназначен для macOS; на Windows используйте build_android.ps1." >&2
    exit 1
fi

if [[ -z "${ANDROID_NDK_HOME:-}" && -n "${ANDROID_HOME:-}" ]]; then
    export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/26.1.10909125"
fi
if [[ ! -d "${ANDROID_NDK_HOME:-}" ]]; then
    echo "Установите NDK 26.1.10909125 и задайте ANDROID_NDK_HOME." >&2
    exit 1
fi

ndk_bin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin"
if [[ ! -x "$ndk_bin/x86_64-linux-android${api}-clang" ]]; then
    echo "Не найден компилятор NDK 26.1 для x86_64." >&2
    exit 1
fi

cd "$core_dir"

echo "== arm64-v8a =="
cargo ndk -P "$api" -t arm64-v8a -o "$jni_dir" build --release

echo "== x86_64 =="
# Apple Clang понимает инструкции OpenSSL 3.6, которых ещё нет в ассемблере Clang 17 из NDK 26.
ndk_env="$(cargo ndk-env -P "$api" -t x86_64)"
eval "$ndk_env"
export CC_x86_64_linux_android=/usr/bin/clang
export CFLAGS_x86_64_linux_android="--target=x86_64-linux-android${api} --sysroot=$CARGO_NDK_SYSROOT_PATH -D__ANDROID_API__=${api}"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$ndk_bin/x86_64-linux-android${api}-clang"
RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384" \
    cargo build --release --target x86_64-linux-android
mkdir -p "$jni_dir/x86_64"
cp "target/x86_64-linux-android/release/libsm_core.so" "$jni_dir/x86_64/libsm_core.so"

echo "== Kotlin bindings =="
cargo build --release --lib
cargo run --release --bin uniffi-bindgen -- \
    generate --library target/release/libsm_core.dylib \
    --language kotlin --out-dir "$java_dir"

echo "Готово: библиотеки и Kotlin-биндинги обновлены."
