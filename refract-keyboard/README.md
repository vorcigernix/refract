# Refract Keyboard for Android

This is the active Refract Keyboard application. It combines Android 17 AOSP
LatinIME with Refract's private composer, two-QR X25519 pairing, Keystore-backed
conversation state, and local carrier generation.

## Source baseline

- AOSP project: `platform/packages/inputmethods/LatinIME`
- Tag: `android-17.0.0_r1`
- Commit: `afe0d5c261c3a0c88b38894981a7b332da93e7dd`
- Minimum Android API: 34

The project deliberately retains LatinIME's native keyboard renderer. AOSP
setup, account, cloud, contacts, spell-checker, backup, downloadable-dictionary,
and legacy settings surfaces are excluded.

## Build

```sh
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written to
`build/outputs/apk/debug/RefractKeyboard-debug.apk`.

The imported AOSP source retains its Apache License 2.0 headers and `NOTICE`.
Refract's project-level license and source acknowledgements are in the
repository root.
