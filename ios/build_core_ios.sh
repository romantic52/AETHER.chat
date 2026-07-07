#!/usr/bin/env bash
#
# Сборка общего Rust-ядра (../core) под iOS + генерация Swift-биндингов (UniFFI).
# ЗАПУСКАЕТСЯ ТОЛЬКО НА macOS (нужны Apple toolchain, lipo, xcodebuild).
#
# Предусловия (один раз):
#   rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios
#   (Xcode 26+ для настоящего Liquid Glass в самом приложении)
#
# Результат:
#   ios/CoreFFI/SmCoreFFI.xcframework       — статическое ядро (device + simulator)
#   ios/AETHER/CoreFFI/Generated/sm_core.swift — высокоуровневые Swift-биндинги
#
# После прогона: cd ios && xcodegen generate && open AETHER.xcodeproj
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CORE="$HERE/../core"
LIB="libsm_core.a"
BUILD="$HERE/.coreffi-build"
XCFRAMEWORK="$HERE/CoreFFI/SmCoreFFI.xcframework"
GEN="$HERE/AETHER/CoreFFI/Generated"

echo "== таргеты Rust под iOS =="
rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios

cd "$CORE"
echo "== cargo build (release) для device + simulator =="
cargo build --release --target aarch64-apple-ios          # iPhone (arm64)
cargo build --release --target aarch64-apple-ios-sim      # симулятор на Apple Silicon
cargo build --release --target x86_64-apple-ios           # симулятор на Intel

rm -rf "$BUILD"; mkdir -p "$BUILD/sim" "$BUILD/device" "$BUILD/headers"

echo "== fat-библиотека для симулятора (arm64 + x86_64) =="
lipo -create \
  "$CORE/target/aarch64-apple-ios-sim/release/$LIB" \
  "$CORE/target/x86_64-apple-ios/release/$LIB" \
  -output "$BUILD/sim/$LIB"
cp "$CORE/target/aarch64-apple-ios/release/$LIB" "$BUILD/device/$LIB"

echo "== UniFFI: Swift-биндинги из собранной библиотеки =="
cargo run --release --bin uniffi-bindgen -- generate \
  --library "$CORE/target/aarch64-apple-ios/release/$LIB" \
  --language swift \
  --out-dir "$BUILD/bindings"

# UniFFI кладёт три файла: sm_core.swift, sm_coreFFI.h, sm_coreFFI.modulemap.
# Для XCFramework нужен заголовок + module.modulemap в каталоге headers.
cp "$BUILD/bindings/sm_coreFFI.h" "$BUILD/headers/"
cp "$BUILD/bindings/sm_coreFFI.modulemap" "$BUILD/headers/module.modulemap"

echo "== сборка XCFramework =="
rm -rf "$XCFRAMEWORK"; mkdir -p "$HERE/CoreFFI"
xcodebuild -create-xcframework \
  -library "$BUILD/device/$LIB" -headers "$BUILD/headers" \
  -library "$BUILD/sim/$LIB"    -headers "$BUILD/headers" \
  -output "$XCFRAMEWORK"

echo "== высокоуровневый Swift-биндинг -> в таргет приложения =="
mkdir -p "$GEN"
cp "$BUILD/bindings/sm_core.swift" "$GEN/sm_core.swift"

echo ""
echo "Готово."
echo "  XCFramework: $XCFRAMEWORK"
echo "  Биндинги:    $GEN/sm_core.swift"
echo "Далее:  cd ios && xcodegen generate && open AETHER.xcodeproj"
