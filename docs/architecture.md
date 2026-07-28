# Refract Keyboard architecture

Status: active Android prototype
Last updated: 2026-07-27

## Product boundary

Refract is an Android input method with an additional private-composition mode.
The active project is `refract-keyboard/`, based on the Android 17 AOSP
LatinIME source with a minimum supported API of 34.

LatinIME owns keyboard rendering, layouts, language switching, suggestions,
emoji, and editor integration. Refract adds a narrow controller around its
existing input events and views; it does not replace the keyboard renderer.

The standalone manifest exposes only:

- the LatinIME input-method service;
- the local carrier-model service; and
- the focused Refract settings activity.

AOSP setup, account, cloud, spell-checker, contacts, downloadable-dictionary,
backup, and legacy settings surfaces are not exposed. The downloadable
dictionary stack and its UI have also been removed; the keyboard uses only its
bundled dictionaries.

## Private composition

- Tapping **Private** diverts text-producing key events to
  `PrivateDraftBuffer`.
- Shift, symbols, language switching, and emoji remain native LatinIME actions.
- Gesture batches are blocked while private mode is active.
- The input-method window uses `FLAG_SECURE` while the private composer is
  visible.
- The host `InputConnection` receives only a completed, validated carrier.
- Sender-chain state advances only after carrier insertion succeeds.
- A failed insertion preserves the private draft and sender-chain state.

## Pairing security

Pairing is an offline two-QR X25519 exchange. Neither QR contains the
conversation key:

1. The initiator shows a 15-minute invitation containing an ephemeral X25519
   public key and non-secret pairing metadata.
2. The responder scans it, derives the shared secret, and shows a response QR
   containing its ephemeral public key and a transcript-bound confirmation
   tag.
3. The initiator scans the response and derives the same secret.
4. Both people compare the same five safety words before either device stores
   the conversation.

HKDF-SHA256 derives the 32-byte conversation root from the X25519 result and
the full exchange transcript. Confirmed keys are wrapped by Android Keystore.
Abandoned sessions clear in-memory key material and are never persisted.

## Trust boundary

```text
LatinIME key events
        |
        v
RefractImeController -> PrivateDraftBuffer
        |
        v
CarrierModelService -> authenticated carrier
        |
        v
Host InputConnection (carrier text only)
```

Private mode consumes unknown editor-affecting commands. The merged manifest
has neither `INTERNET` nor `ACCESS_NETWORK_STATE`.

## Build and test

```sh
cd refract-keyboard
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is:

```text
refract-keyboard/build/outputs/apk/debug/RefractKeyboard-debug.apk
```

The application requires Android 14 or newer (`minSdk 34`). It is compiled
against SDK 37 from the Android 17 source baseline.

## Device validation

Completed on a Pixel 10 Pro:

- Installed the debug APK.
- Enabled and selected the Refract input method.
- Opened the focused Refract settings activity without a process crash.
- Verified the APK contains the arm64 LatinIME and LiteRT native libraries.
- Verified the merged APK requests only `android.permission.VIBRATE`.

Still requiring an unlocked-device interaction pass:

- Type in both normal and private modes.
- Confirm plaintext never reaches the host editor through tap, paste, gesture,
  or physical-keyboard paths.
- Switch language, symbol, emoji, orientation, and application while a private
  draft exists.
- Pair two clean installs, compare safety words, and test cancellation paths.
- Import the pinned Gemma artifact and initialize both GPU and CPU backends.
- Generate, insert, cancel, and retry carriers while checking sender-chain
  recovery.

## Known gaps

- Receiving and decoding are not implemented.
- Sender state commits when cover insertion succeeds, not when the user taps
  the messaging application's Send button.
- Deleting a staged carrier consumes its local sequence number; recovery and
  resynchronization are not implemented.
- The 2.6 GB model path and final carrier insertion have not yet been exercised
  on this AOSP-based build.
- QR scanning uses the Google Play services code scanner.
