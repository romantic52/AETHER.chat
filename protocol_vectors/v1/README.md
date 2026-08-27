# AETHER protocol vectors v1

Each entry is decoded from `input_json`, normalized to `expected_decoded`, and
encoded to `expected_encoded_json`. JSON objects are compared semantically, so
key order is irrelevant. Only `deterministic_crypto` entries require exact
bytes; all keys and nonces in this directory are test-only.

Run the readers from the repository root:

```sh
cargo test --manifest-path core/Cargo.toml --test protocol_vectors
node web/test_wire.js
swiftc ios/AETHER/Core/Wire.swift ios/check_wire_compat.swift -o /tmp/aether-wire-check && /tmp/aether-wire-check
```

The Android instrumentation reader packages this same directory as test assets
and runs with `./android/gradlew -p android connectedDebugAndroidTest`.
