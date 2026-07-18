# AETHER Ratchet WASM

Thin browser adapter over the same `core/ratchet-core` engine used by native clients.

Build from the repository root (`wasm-bindgen-cli` must match the crate version):

```sh
cargo build --manifest-path web/ratchet-wasm/Cargo.toml --target wasm32-unknown-unknown --release
wasm-bindgen web/ratchet-wasm/target/wasm32-unknown-unknown/release/aether_ratchet_wasm.wasm \
  --out-dir web/vendor/ratchet --target web --no-typescript
```
