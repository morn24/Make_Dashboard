# Android scroll and color verification

Date: 2026-11-01; app: `com.morn24.dashboard` 1.5 (`versionCode 6`)

## Implemented

- Height planning estimates visible rows without truncating the full todo, today event, or active project lists. Larger two-column widgets and narrow tall widgets use bounded panel lists with separate scroll containers.
- Removed panel-wide web actions while retaining explicit whole-view controls. Existing four accent palette IDs remain; three warm accents were added.
- Apps Script reads event, label, and calendar colors with compatibility fallbacks. `diagnoseCalendarColors()` reports safe aggregate diagnostics; `refreshCalendarColors()` bypasses the in-memory palette cache.
- Android unit tests cover height-planner tiers, palette compatibility/contrast, and shared calendar HEX flowing through the JSON mapper to marker RGB.

## Automated checks

- `gradlew.bat test assembleDebug lintDebug --console=plain` — passed after the final vertical-weight layout fix, including the mapper-to-marker color test.
- `node --test integrations/google-calendar/tests/Code.test.cjs` — 9 tests passed.
- Debug APK package: 1.5 / version code 6. `app-debug.apk` and `Dashboard-scroll-colors-debug.apk` have matching SHA-256 `8A690F8DF652DCA85FE9F1E433418DE08C4D3B355726234B3B099C7E8BB8BC92`.

## Emulator checks

The APK was installed with `adb install -r` on the Pixel 6a API 36 emulator; app data was not cleared. The debug harness saved anonymous `TODO-01..20`, `EVT-01..20`, and `PRJ-01..20` lists. The event fixture cycles its four marker colors across all 20 events.

An initial 4×4 render showed the todo and project panels but omitted the schedule panel. Removing `fillMaxHeight()` from weighted vertical siblings fixed the allocation. After rebuilding and update-installing, the large widget showed all three panels.

| Launcher size | First screen observed |
|---|---|
| 2×2 | Compact summary with one next-event row |
| 4×2 | One todo, one event, and one project line; schedule text is clipped at this short height |
| 4×4 | Two todos, four full event rows plus a partial fifth, and four full project rows plus a partial fifth |

On 4×4, upward and downward swipes inside each panel moved that list independently. The schedule reached `EVT-20`, projects reached `PRJ-20`, and todos reached `TODO-20`. When the todo list was scrolled from a checkbox row, its completed count stayed `0/20`; no checkbox toggled. Other panels retained their positions. The launcher remained on screen; a browser did not open.

The debug fixture uses a deliberately invalid local token, so the widget displays its partial-sync warning while rendering saved fixture data. The warning is test-only and contains no personal account information.

Anonymous captures are in [`docs/android-scroll-colors-screenshots`](android-scroll-colors-screenshots/): `emulator-4x2.png`, `emulator-4x4-first-screen.png`, `emulator-4x4-todo-last-items.png`, `emulator-4x4-project-last-items.png`, and `emulator-4x4-all-last-items.png`. The final capture shows all three lists at their last entries together. The three warm Glance renders are `emulator-4x4-warm-coral.png`, `emulator-4x4-warm-apricot.png`, and `emulator-4x4-warm-butter.png`; the Google-style event dots remain red/blue/green in each image.

## Calendar account status

The connected Google account's Apps Script deployment and private `calendar.json` were not available through the connected read-only sources. Apply the updated `Code.gs` to the existing Apps Script project, run `diagnoseCalendarColors()` and then `refreshCalendarColors()`, and refresh the widget to verify account-specific event HEX values.

## Release artifact

The debug APK is [`android/app/build/outputs/apk/debug/Dashboard-scroll-colors-debug.apk`](../android/app/build/outputs/apk/debug/Dashboard-scroll-colors-debug.apk). Both APK files have the SHA-256 recorded above.
