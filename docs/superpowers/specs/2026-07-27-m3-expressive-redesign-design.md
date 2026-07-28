# M3 Expressive Redesign — Refract Keyboard

**Date:** 2026-07-27
**Revision:** 3 — codebase replaced with AOSP LatinIME; revisions 1 and 2 are void
**Scope:** M3 Expressive theming and Material component adoption across all three UI surfaces

## Revision history

- **rev 1** — Compose/Material3 redesign of a FlorisBoard fork; planned to unify the Snygg
  keyboard stylesheet with the app `ColorScheme`.
- **rev 2** — same codebase, but deleting the Snygg theming subsystem outright (~11,000 LOC).
- **rev 3 (this)** — **the FlorisBoard codebase was thrown away and replaced with AOSP
  LatinIME.** Revisions 1 and 2 describe code that no longer exists. Nothing in them
  applies: there is no Compose, no `androidx.compose.material3`, no Snygg, no jetpref, no
  materialkolor, no `FlorisScreen`, no wrapper zoo. This revision starts from the AOSP
  codebase as committed at `fb98cc8`.

## Current codebase (verified)

| Property | Value |
|---|---|
| Base | AOSP LatinIME, `namespace com.android.inputmethod.latin` |
| Sources | 234 Java files, 27 Kotlin files, 50 XML layouts |
| Build | Groovy Gradle, AGP 9.3.1, Java 17, minSdk 34 / targetSdk 37 / compileSdk 37 |
| Kotlin | No Kotlin plugin applied — AGP 9's **built-in Kotlin support** compiles `src/main/kotlin`. Verified: 70 Refract classes in `build/intermediates/built_in_kotlinc/`. |
| UI deps | **None for design.** `androidx.core:core:1.2.0` (2020), `androidx.viewpager`, gson, zxing, gms code-scanner, litertlm AAR. No Material library, no AppCompat, no Compose. |
| Baseline | `:compileDebugJavaWithJavac` passes. `:testDebugUnitTest` = **36 tests, 0 failures, 9 classes.** |

### Three UI surfaces, different in kind

**1. Keyboard key grid — `Canvas` + `Paint`.** `KeyboardView.onDrawKeyBackground()` and
`onDrawKeyTopVisuals()` draw keys directly, reading 217 themed attrs from `attrs.xml`.
**There is no component to replace here** — "use Material components" cannot apply to a key
drawn with `drawText` onto a bitmap. M3 for this surface means new themed attr values plus
edits to the drawing path for expressive shape and state layers.

`KeyboardTheme.DEFAULT_THEME_ID = THEME_ID_KLP` — the keyboard defaults to **KitKat-era
(2013) styling**. Available themes are ICS (0), KLP (2), LXX_Light (3), LXX_Dark (4).
Changing this default is the single largest visual improvement available.

The theme-picker arrays (`keyboard_theme_names`, `keyboard_theme_ids` in
`keyboard-themes.xml`) are unreachable — there is no settings UI that displays them.

**2. In-keyboard Refract panel — `java/res/layout/main_keyboard_frame.xml`.** Plain
`Button` and `TextView` styled with `?attr/suggestionWordStyle` (suggestion-*word* styles
applied to buttons), `android:background="@android:color/transparent"` hacks, hardcoded
`96dp` widths and `15sp`/`12sp` text sizes, `android:textAllCaps="false"` workarounds.
Driven by `RefractImeController.kt` (272 LOC) via `findViewById`.

**3. `RefractSettingsActivity.kt` — 600 LOC of hand-built platform Views**, constructed
programmatically: `LinearLayout`, `ScrollView`, `TextView`, `Button`, `CheckBox`,
`RadioGroup`, `EditText`, `AlertDialog`. Self-described as "built from platform Views,
independent of LatinIME settings UI." **This is where component replacement genuinely
applies.**

## Verified technical constraints

- **`com.google.android.material:material:1.14.0` is stable** and ships full Expressive
  support for the View toolkit. Confirmed present in the AAR:
  - Themes: `Theme.Material3Expressive.DayNight.NoActionBar`,
    `Theme.Material3Expressive.DynamicColors.*`
  - Classes: `MaterialToolbar`, `MaterialButtonToggleGroup`, `MaterialSplitButton`,
    `MaterialCardView`, `MaterialSwitch`, `MaterialAlertDialogBuilder`,
    `FloatingToolbarLayout`, `LoadingIndicator`, `MaterialShapes`, `Slider`
  - Expressive widget styles include `MaterialButtonGroup`, `Slider`, `SearchBar`,
    `Toolbar`, `LinearProgressIndicator`, `CircularProgressIndicator`.

  This is **strictly better than rev 1's plan**, which required `material3:1.5.0-alpha23`.
  Same design language, stable dependency, no alpha risk.

- **Material themes require an AppCompat lineage.** `RefractSettingsActivity` extends
  `android.app.Activity`, so it must become `AppCompatActivity`; `androidx.appcompat` gets
  added and `androidx.core` bumped from 1.2.0.

- **Dynamic color insertion point is exact:** `KeyboardSwitcher.java:107`
  `mThemeContext = new ContextThemeWrapper(context, keyboardTheme.mStyleId)` becomes a
  `DynamicColors.wrapContextIfAvailable(...)` call, so the IME picks up wallpaper colors.

## Decisions taken

| # | Decision | Rationale |
|---|---|---|
| 1 | **Material Components for Views on all three surfaces** | One theme system (XML attrs) drives keyboard and settings, so they cannot drift. Choosing Compose for settings would reintroduce the dual-theme problem that motivated this work. |
| 2 | Add `material:1.14.0`, `androidx.appcompat`, bump `androidx.core` | Required for M3 themes and components. |
| 3 | **One keyboard theme, following the system** | New `Theme.Refract.Keyboard` on M3 Expressive tokens + dynamic color. Legacy ICS/KLP/LXX themes deleted along with their colors, icons, and drawables. |
| 4 | `RefractSettingsActivity` → `AppCompatActivity` with Material components | The 600-line hand-built View tree is the core "replace custom controls" ask. |
| 5 | Split `RefractSettingsActivity` into focused files | 600 lines mixing pairing protocol flow, QR rendering, model import, and view construction. |
| 6 | Rewrite the in-keyboard panel with Material components | Removes the `?attr/suggestionWordStyle`-on-buttons abuse. |
| 7 | No keyboard theme picker | Follows the system; the existing picker arrays are already unreachable. |

## Accepted losses

- **Keyboard theme choice is removed** (ICS / KLP / LXX Light / LXX Dark). Appearance
  becomes a function of the system light/dark setting and wallpaper-derived dynamic color.
  Since the picker UI was already unreachable, no working feature is lost in practice — but
  users on API < 31 or with dynamic color unavailable get the static M3 fallback palette
  rather than a choice of four themes.
- **`androidx.core` bump and AppCompat addition** grow the APK. Acceptable for a keyboard;
  Material Components adds roughly 1–2 MB before shrinking, and `minifyEnabled true` is
  already set for release.

## Design

### Theme foundation

New `java/res/values/themes-refract.xml`:

- `Theme.Refract` — parent `Theme.Material3Expressive.DayNight.NoActionBar`, for
  `RefractSettingsActivity`.
- `Theme.Refract.Keyboard` — parent `Theme.Material3Expressive.DayNight`, carrying the
  keyboard attr overrides. This is the style `KeyboardTheme` points at.

Both get dynamic color applied at runtime rather than via a `DynamicColors.*` theme parent,
so a single theme definition serves both the dynamic and static-fallback cases:
`DynamicColors.applyToActivitiesIfAvailable(app)` for the Activity, and
`DynamicColors.wrapContextIfAvailable()` at `KeyboardSwitcher.java:107` for the IME.

`values-night/` is unnecessary — `DayNight` parents resolve automatically.

### Keyboard surface

`KeyboardTheme.java`:
- Add `THEME_ID_REFRACT = 5` with style `R.style.KeyboardTheme_Refract`, `minApiVersion`
  matching `minSdk 34`.
- Set `DEFAULT_THEME_ID = THEME_ID_REFRACT`.
- Reduce `KEYBOARD_THEMES` to that single entry; delete the ICS/KLP/LXX entries and the
  `KLP_KEYBOARD_THEME_KEY` migration path (it only applies to `sdkVersion <= KITKAT`, which
  `minSdk 34` makes dead code).

Key attr values map onto M3 roles: key background `?attr/colorSurfaceContainerHigh`,
functional keys `?attr/colorSurfaceContainerHighest`, accent/enter key
`?attr/colorPrimaryContainer`, key text `?attr/colorOnSurface`, hint/inactive text
`?attr/colorOnSurfaceVariant`, gesture trail and highlights `?attr/colorPrimary`.

New key background drawables replace `btn_keyboard_key_klp`: M3 corner radii with a
`ripple` state layer, plus pressed/checked states expressed through the drawable rather
than the Canvas code where possible.

Deleted: `keyboard-icons-holo.xml`, `keyboard-icons-lxx-dark.xml`,
`keyboard-icons-lxx-light.xml` (replaced by one M3 icon set), the `*_holo` / `*_klp` /
`*_lxx_*` color entries in `colors.xml`, legacy key drawables, and the
`keyboard_theme_names` / `keyboard_theme_ids` arrays.

### In-keyboard Refract panel

`main_keyboard_frame.xml` panel section rebuilt:
- Container → `MaterialCardView` with M3 shape and `?attr/colorSurfaceContainer`.
- Draft field → `TextView` with `?attr/textAppearanceBodyLarge`, no transparent-background
  hack.
- Status line → `TextView` with `?attr/textAppearanceLabelMedium` and
  `?attr/colorOnSurfaceVariant`; gains `accessibilityLiveRegion="polite"` so generation
  status is announced (it currently is not).
- Toggle → `MaterialButton` (tonal), replacing `?attr/suggestionWordStyle` on a `Button`.
- Settings / Generate → `MaterialButton` (outlined / filled) inside a `MaterialButtonGroup`.
- Generation progress → `LoadingIndicator`, replacing the text-only status.

`RefractImeController.kt` updated for the new view types (`MaterialButton` rather than
`Button`) and to drive the `LoadingIndicator`.

### RefractSettingsActivity

Becomes `AppCompatActivity` themed `Theme.Refract`, with an XML layout rather than a
programmatic View tree, split into focused files:

| File | Responsibility |
|---|---|
| `RefractSettingsActivity.kt` | Activity lifecycle, view binding, wiring |
| `ui/ConversationListView.kt` | Paired-conversation list rendering and actions |
| `ui/PairingFlow.kt` | Invite / respond / safety-word confirmation dialog sequence |
| `ui/QrCodes.kt` | QR bitmap generation and the bright-screen effect |
| `ModelImport.kt` | Gemma model file import and validation |
| `res/layout/activity_refract_settings.xml` | The layout |

Component mapping:
- Screen chrome → `MaterialToolbar` in an `AppBarLayout`
- Sections → `MaterialCardView` with `?attr/textAppearanceTitleMedium` headers
- Buttons → `MaterialButton` (filled / tonal / outlined by emphasis)
- GPU/CPU backend → `MaterialButtonToggleGroup` (single-selection), replacing `RadioGroup`
- Preload toggle → `MaterialSwitch`, replacing `CheckBox`
- Alias / display-name entry → `TextInputLayout` + `TextInputEditText`, replacing `EditText`
- All dialogs → `MaterialAlertDialogBuilder`, replacing `AlertDialog.Builder`
- Progress → `LoadingIndicator`, replacing text-only status
- QR framing → `MaterialShapes`

`FLAG_SECURE` handling and the QR bright-screen effect are preserved exactly — they are
security and usability behavior, not styling.

## Implementation phases

0. **Dependencies + theme foundation.** Add Material/AppCompat, bump core, create
   `themes-refract.xml`, apply `Theme.Refract` in the manifest. Build green.
1. **Keyboard theme.** `THEME_ID_REFRACT`, attr mapping, new key drawables, dynamic color
   at `KeyboardSwitcher.java:107`, make it default.
2. **Legacy theme deletion.** Remove ICS/KLP/LXX themes, colors, icon sets, drawables,
   picker arrays.
3. **In-keyboard panel.** Rebuild the `main_keyboard_frame.xml` panel; update
   `RefractImeController.kt`.
4. **Settings rewrite.** `AppCompatActivity`, XML layout, Material components, file split.
5. **Cleanup.** Dead resources, unused strings, lint baseline refresh.

## Verification

- `./gradlew :compileDebugJavaWithJavac` and `:assembleDebug` must pass at every phase
  boundary. Requires `JAVA_HOME` set to a JDK (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`)
  and `ANDROID_HOME` set (`~/Library/Android/sdk`); there is no `local.properties` and no
  JDK on `PATH`.
- `./gradlew :testDebugUnitTest` must stay at **36 tests / 0 failures**. These cover the
  pairing protocol and crypto (`PairingProtocolTest`, `AesSivTest`, `SipHash24`,
  `BucketCarrierCodecTest`, `SenderChainTest`) and are untouched by UI work, so they are the
  regression signal that behavior was not disturbed.
- Phases 1–3 need on-device visual verification: key states (normal, pressed, shifted,
  shift-locked, functional, action), popup/more-keys, gesture trail, emoji palette,
  suggestion strip, and the Refract panel — in both light and dark, with and without
  dynamic color.
- Actual command output gets reported, including failures.

## Risks

- **Phase 1 touches Canvas drawing code**, the most performance-sensitive path in the app.
  `KeyboardView` uses an offscreen bitmap buffer; adding state layers or shape changes can
  cost frame time on key press. Measure before/after on device rather than assuming.
- **Deleting legacy themes is broad resource surgery.** `attrs.xml` has 217 attrs and the
  theme styles are spread across `themes-common.xml`, `keyboard-themes.xml`,
  `platform-theme.xml`, and per-density/per-locale `values-*` directories. Missing a
  reference is a resource-linking failure, which the build catches.
- **AppCompat migration can change Activity behavior** (window insets, action bar,
  configuration changes). `RefractSettingsActivity` sets `FLAG_SECURE` and manipulates
  screen brightness for QR display; both need re-verification after the base class changes.
- **`android.preference` is deprecated** and used by `KeyboardTheme`, `Settings`, and
  `LatinIME`. Not in scope, but Phase 1 edits `KeyboardTheme`, so the deprecation warnings
  will be visible there. Leave the API in place; migrating it is separate work.
