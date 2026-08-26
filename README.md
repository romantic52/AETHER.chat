<p align="center">
  <img src="docs/logo.png" width="120" alt="Æther logo">
</p>

<h1 align="center">Æther</h1>

<p align="center">
  End-to-end encrypted messenger with a Telegram-like UX.<br>
  Rust core · native iOS & Android · web PWA · self-hosted server.
</p>

<p align="center"><a href="README.ru.md">Русская версия</a></p>

Messages are **encrypted on the sender's device** and **decrypted only by the
recipient** — the server sees nothing but ciphertext.

## Philosophy

- **Your own server in 10 minutes.** Official servers exist, but anyone can
  rent a VPS and run their own — clients enter the server URL at login.
  Your conversations live wherever you decide.
- **Minimal data.** An account is a `@username` + password. No phone number,
  no email, no address book. The server stores only public keys and encrypted
  envelopes.
- **Zero tracking.** No analytics, no ad SDKs, no telemetry.
- **Your history stays with you.** The local database is encrypted (SQLCipher);
  the server keeps messages only until delivery. The price of privacy: lose
  the device — lose the history.
- **Public is public, private is private.** Uncompromised E2E for DMs, groups
  and private channels. Public channels (open subscription by @name) are the
  single place where the server knows the key — their content is public by
  the owner's own choice.

## Features

DMs, groups and channels (public with @usernames and one-tap subscription from
search, private by invitation) · post comments · voice messages and video
circles · photos/videos/files with full metadata stripping (EXIF/GPS) ·
audio & video calls (WebRTC, p2p) · reactions, replies, editing · TOFU key
verification · Face ID/PIN lock · multi-account (up to 5) · global search ·
dark/light themes, liquid glass.

## Architecture

| Component | Stack | Purpose |
|-----------|-------|---------|
| `core/` | Rust + UniFFI | Shared core: crypto, wire protocol, network client, local storage (SQLite). Used by Android and iOS through generated bindings |
| `server/main.py` | FastAPI + PostgreSQL | Relay: stores public keys and encrypted messages, never sees plaintext |
| `android/` | Kotlin + Jetpack Compose | Native Android app |
| `ios/` | SwiftUI + Liquid Glass | Native iOS app (see [ios/README.md](ios/README.md)); reuses the same core |
| `web/` | Vanilla JS + WebCrypto/TweetNaCl | Browser client (PWA), protocol-compatible with the other clients |

The single cross-client protocol is described in
[WIRE_PROTOCOL.md](WIRE_PROTOCOL.md) — one account works in the browser, on
Android and on iOS.

### Cryptography
- Direct messages: `crypto_box` (Curve25519, XSalsa20-Poly1305).
- Groups: AES-GCM with a shared key, distributed to members via `crypto_box`.
- Keys: random keypair; the private key is encrypted with the password
  (PBKDF2 100k + AES-GCM) and stored on the server as a backup.
- Media: AES-GCM; ciphertext goes to `/upload`, the key travels inside the
  encrypted message.

## Roadmap

The goal is a lightweight native messenger on every platform with a shared
secure core (the TDLib model):

1. **Shared Rust core** (crypto + protocol + storage), bindings via UniFFI.
2. **Desktop app** on Compose Multiplatform (fully native, not a web wrapper).
3. Web stays a browser client, protocol-compatible through the same core (WASM).

## Self-hosting

You need: a Linux VPS, Python 3.10+, PostgreSQL, and a TLS reverse proxy
(Caddy/nginx).

```bash
git clone https://github.com/romantic52/AETHER.chat && cd AETHER.chat
pip install -r requirements.txt
# Environment variables: DB_NAME/DB_USER/DB_PASS/DB_HOST, ALLOWED_ORIGINS,
# optionally APNS_* for iOS pushes — see the top of server/main.py and server/apns.py.
uvicorn server.main:app --host 127.0.0.1 --port 8000
```

Database migrations run automatically on startup. Expose only through the TLS
proxy. Windows dev scripts: [`scripts/run_server.ps1`](scripts/run_server.ps1).

## Core (Rust)

`core/` is the shared crypto/protocol/storage for Android and iOS. Android
build: [`core/build_android.sh`](core/build_android.sh) on macOS or
[`core/build_android.ps1`](core/build_android.ps1) on Windows (drops `.so`
into `android/app/src/main/jniLibs` and generates Kotlin bindings). iOS build:
[`ios/build_core_ios.sh`](ios/build_core_ios.sh) (macOS — builds the
XCFramework + Swift bindings).

## Android

Open [`android/`](android/) in Android Studio. Run `core/build_android.sh` on
macOS or `core/build_android.ps1` on Windows before the first build, otherwise
the core `.so` and Kotlin bindings are missing. Console APK build:
[`scripts/build_apk.ps1`](scripts/build_apk.ps1) (PowerShell + gradlew; bat/cmd
break on encoding). The emulator reaches the server at `http://10.0.2.2:8765`.
Set your server URL on the login screen.

## iOS

See [`ios/README.md`](ios/README.md) — building requires macOS (Xcode) or the
ready-made GitHub Actions pipeline
([`.github/workflows/ios.yml`](.github/workflows/ios.yml)).

## Web

Static files live in [`web/`](web/) and are served by the server. Protocol
tests: `node web/test_wire.js`.

## Security

See [SECURITY_REVIEW_P0-P2.md](SECURITY_REVIEW_P0-P2.md) and
[P6_TOFU_DESIGN.md](P6_TOFU_DESIGN.md) (TOFU key pinning).
Found a vulnerability — open an issue or contact the repository owner.

## License

[AGPL-3.0](LICENSE) — forks and hosted modified servers must publish their
source. Privacy should never go closed.
