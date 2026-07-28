# Refract development guidance

## UI design system

- Treat the existing Refract Material 3 Expressive theme and adjacent UI as the source of truth.
- Before adding or changing UI, inspect the relevant theme, styles, layout, and neighboring
  components. Match their component family, tokens, spacing, shapes, typography, and interaction
  patterns.
- Do not assume that applying a Material 3 theme to an Activity automatically upgrades framework
  widgets or dialogs to Material 3 components.
- Every newly created Refract UI element must use the appropriate Material 3 component when one
  exists. Do not introduce framework or legacy AppCompat widgets as substitutes.
- Build dialogs with
  `com.google.android.material.dialog.MaterialAlertDialogBuilder`. Never create Refract dialogs
  with `android.app.AlertDialog.Builder` or `androidx.appcompat.app.AlertDialog.Builder`.
- Use themed Material components and theme attributes instead of hard-coded colors, shapes,
  typography, elevations, or button styling.
- Preserve Material 3 accessibility behavior, including touch targets, contrast, state semantics,
  and content descriptions.
- After UI changes, search the changed Refract scope for newly introduced legacy widgets or dialog
  builders, then validate the result on the target device.

Inherited AOSP keyboard code may retain platform components when it is untouched. Any newly created
or substantially redesigned Refract-facing surface must follow the rules above.
