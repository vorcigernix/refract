# Refract Keyboard

Refract Keyboard is a private writing layer built into a lean Android keyboard.

**Website:** [vorcigernix.github.io/refract](https://vorcigernix.github.io/refract/)

You compose the real message inside the keyboard. Refract encrypts it and uses
an on-device language model to generate ordinary-looking carrier text. Only the
carrier is inserted into the host application; the private draft never enters
its text field.

## What we are building

- A familiar Android keyboard based on AOSP LatinIME
- An isolated private composer inside the keyboard
- In-person X25519 pairing using two public-key QR codes and five safety words
- Conversation keys wrapped by Android Keystore
- Authenticated, ordered sender state
- Local Gemma carrier generation on CPU or GPU
- No internet or network-state permission

The current prototype implements pairing and the sending path. Receiving,
decoding, and recovery after a missed or deleted carrier are not implemented
yet.

## Install the test build

The [v0.1.0 debug release](https://github.com/vorcigernix/refract/releases/tag/v0.1.0-debug)
targets arm64 devices running Android 14 or newer and includes the local model.
About 2.59 GB of the 2.66 GB APK is the bundled Gemma 4 E2B model. Refract
uses it to generate ordinary-looking carrier text entirely on the phone; the
keyboard and runtime account for the remaining roughly 72 MB.

GitHub hosts the APK as two parts. Download both parts and `SHA256SUMS`, then
run:

```sh
cat RefractKeyboard-v0.1.0-debug.apk.part-* > RefractKeyboard-v0.1.0-debug.apk
shasum -a 256 -c SHA256SUMS
adb install -r RefractKeyboard-v0.1.0-debug.apk
```

This is a debug-signed experimental build. Plan for roughly 6 GB of installed
storage, plus temporary space while Android installs it.

## Build

Requirements:

- JDK 17
- Android SDK 37
- Android NDK `29.0.14206865`
- An Android 14 or newer device (`minSdk 34`)
- A licensed `gemma-4-E2B-it.litertlm` model at
  `refract-keyboard/java/assets/models/gemma-4-E2B-it.litertlm`

```sh
cd refract-keyboard
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written to:

```text
refract-keyboard/build/outputs/apk/debug/RefractKeyboard-debug.apk
```

Debug builds use `app.refract.keyboard.debug`; release builds use
`app.refract.keyboard`.

## Repository layout

```text
refract-keyboard/       Active Android application and tests
docs/architecture.md    Security boundary and validation notes
```

## Origins and licensing

The active implementation combines two source lines:

1. **[Conversation Stenography](https://github.com/nethical6/conversation-steganography)**,
   the original research prototype. It established the encryption,
   authenticated conversation chain, carrier encoding, and pairing protocol.
2. **[AOSP LatinIME](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/tags/android-17.0.0_r1)**,
   the Android keyboard engine, layouts, and native dictionary/proximity code.
   The imported baseline is Android 17 tag `android-17.0.0_r1`, commit
   `afe0d5c261c3a0c88b38894981a7b332da93e7dd`.

The repository-level project is distributed under the [GNU GPL v3](LICENSE).
Imported AOSP files retain their Apache License 2.0 notices in
`refract-keyboard/NOTICE`.
