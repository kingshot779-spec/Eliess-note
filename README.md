# ELIOS NOTES

An independent Android notes application built with Kotlin, Jetpack Compose and Room.
Local-first, no account, no cloud, no ads.

## Project state (database schema v4)

> **Build status:** this snapshot was written without access to a compiler or the Android SDK.
> It has **not** been built or run yet. The first Gradle sync / build may surface small compile
> errors (imports, API names) that need fixing. Nothing below should be treated as tested
> until it has been built and exercised on a device.

### Implemented (code written, not yet device-tested)
- Library: notes, search (titles + page text), favorites, pinning, archive view, **trash with restore / delete forever / 30-day auto-purge**, folders (create, rename, delete, move notes), sorting (modified / created / name).
- **Data safety:** `upsert` no longer uses `REPLACE` (which cascade-deleted pages and ink). All saves go through one ordered queue and one repository lock; text and ink of a page are saved in a single transaction. Closing the editor flushes unsaved edits.
- Safe migrations 1→2→3→4 (columns are only added if missing). Legacy v1 notes show their old `body` text on page 1.
- **Page model:** individual pages or continuous scroll, A4 portrait/landscape, templates (blank / ruled / grid / dots), page colour, add / delete pages.
- **Ink engine (own implementation):** pen, pencil, brush, highlighter; pressure when the device reports it; stylus vs finger handling (finger can draw or scroll), palm touches ignored while the pen is down; stroke eraser and partial eraser; undo / redo (200 steps); strokes stored as editable, versioned, compact payloads (not images).
- Text layer per page with mixed Arabic / English direction; text and ink coexist on a page.
- **PDF export** (vector text + ink, via the system file picker) with automatic re-open check of the page count.
- Arabic (default) and English strings. On Android 13+ the language can be chosen per app from system settings; older versions follow the system language.

### Not implemented yet
Lasso selection / move / resize / rotate, pinch-zoom, rich-text formatting (bold, lists, tables, links), images and media, PDF import / annotation / merge / split, audio recording, handwriting recognition, tags, backup / restore, note lock & encryption, in-app language switch for Android < 13, pen tilt and S-Pen button support, text that flows across pages (text beyond the bottom of a page is currently clipped).

## Build
Open `ELIOS_NOTES` in a current Android Studio, let Gradle sync, run the `app` configuration.
The archive has no Gradle wrapper JAR; generate one with the version in `gradle/wrapper/gradle-wrapper.properties`.
Library versions in `build.gradle.kts` could not be verified offline and should be checked on first sync.

Unit tests (pure JVM): `./gradlew :app:testDebugUnitTest` (ink codec round-trip, eraser geometry).

Settings: minSdk 23, compile/target SDK 36, Java/Kotlin 17.

## Principles
- Local-first storage; no AI features; independent identity and assets.
- A feature counts as complete only after it has been implemented **and tested**.

## Getting an APK without installing anything
Push this folder to a GitHub repository. The workflow in `.github/workflows/build-apk.yml`
runs the unit tests, builds the debug APK and publishes it under Actions -> Artifacts.
Compile errors, if any, appear in that run's log.
