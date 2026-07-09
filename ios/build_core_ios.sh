#!/usr/bin/env bash
# Собирает Rust-ядро в SmCoreFFI.xcframework и генерирует sm_core.swift (UniFFI).
# Запуск на Mac/CI перед `xcodegen generate` и сборкой в Xcode.
#
#   ./build_core_ios.sh            # device + sim (arm64) — обычная разработка
#   FAST=1 ./build_core_ios.sh     # только симулятор arm64 (быстрее)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_DIR="$SCRIPT_DIR/../core"
OUT_DIR="$SCRIPT_DIR/CoreFFI"
GEN_SWIFT_DIR="$SCRIPT_DIR/AETHER/Core/Generated"
LIB_NAME="libsm_core.a"
FFI_MODULE="SmCoreFFI"

export PATH="$HOME/.cargo/bin:$PATH"

# Минимальная версия iOS для статической библиотеки — совпадает с деплой-таргетом
# приложения (17.0), чтобы линкер не ругался на «built for newer version».
export IPHONEOS_DEPLOYMENT_TARGET="17.0"

echo "▸ Rust ядро: $CORE_DIR"
cd "$CORE_DIR"

# Цели.
if [[ "${FAST:-0}" == "1" ]]; then
  TARGETS=(aarch64-apple-ios-sim)
else
  TARGETS=(aarch64-apple-ios aarch64-apple-ios-sim)
fi

for t in "${TARGETS[@]}"; do
  echo "▸ cargo build --release --target $t"
  cargo build --release --target "$t"
done

# UniFFI: генерируем Swift-биндинги из собранной библиотеки (proc-macro режим).
echo "▸ Генерация Swift-биндингов (UniFFI)"
BINDGEN_LIB="target/aarch64-apple-ios-sim/release/$LIB_NAME"
rm -rf "$GEN_SWIFT_DIR" && mkdir -p "$GEN_SWIFT_DIR"
cargo run --release --bin uniffi-bindgen -- generate \
  --library "$BINDGEN_LIB" \
  --language swift \
  --out-dir "$GEN_SWIFT_DIR"

# UniFFI кладёт: sm_core.swift, sm_coreFFI.h, sm_coreFFI.modulemap.
# Для xcframework соберём modulemap в правильном имени и заголовки в отдельную папку.
HEADERS_DIR="$SCRIPT_DIR/CoreFFI/Headers"
rm -rf "$HEADERS_DIR" && mkdir -p "$HEADERS_DIR"
cp "$GEN_SWIFT_DIR"/*FFI.h "$HEADERS_DIR"/
# Clang-модуль ДОЛЖЕН называться sm_coreFFI — именно его импортирует sm_core.swift
# (`import sm_coreFFI`). Имя файла xcframework (SmCoreFFI.xcframework) с этим не связано.
cat > "$HEADERS_DIR/module.modulemap" <<EOF
module sm_coreFFI {
    header "sm_coreFFI.h"
    export *
}
EOF
# sm_core.swift остаётся в Generated/ и компилируется в приложении.
rm -f "$GEN_SWIFT_DIR"/*FFI.h "$GEN_SWIFT_DIR"/*FFI.modulemap "$GEN_SWIFT_DIR"/*.modulemap

# Собираем xcframework.
echo "▸ Сборка $FFI_MODULE.xcframework"
rm -rf "$OUT_DIR/$FFI_MODULE.xcframework"

XCARGS=()
if [[ " ${TARGETS[*]} " == *" aarch64-apple-ios "* ]]; then
  XCARGS+=(-library "target/aarch64-apple-ios/release/$LIB_NAME" -headers "$HEADERS_DIR")
fi
XCARGS+=(-library "target/aarch64-apple-ios-sim/release/$LIB_NAME" -headers "$HEADERS_DIR")

xcodebuild -create-xcframework "${XCARGS[@]}" -output "$OUT_DIR/$FFI_MODULE.xcframework"

echo "✅ Готово:"
echo "   $OUT_DIR/$FFI_MODULE.xcframework"
echo "   $GEN_SWIFT_DIR/sm_core.swift"
