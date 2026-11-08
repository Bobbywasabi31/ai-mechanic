# AI Mechanic

Phone-first, evidence-based vehicle digital twin — an Android prototype.

Point the phone at an engine bay, tap a real part to drop a 3D pin on it,
label it, and save the session locally. Pins and claims always carry a
provenance tag (**observed / documented / inferred**) so a guess never
silently becomes a measurement. Ask "prove it" later — the data model
already stores the evidence edges; the evidence UI is a later milestone.

- Initial test device: Samsung Galaxy S22 Ultra (SM-S908U1)
- Initial test vehicle (seeded profile): 2022 Toyota Corolla XLE, 1.8L, CVT
- Original Nreal Air glasses: optional future HUD display only. They have
  no camera and no 6DoF tracking; all sensing is on the phone.

## Feature status (honest matrix)

| Feature | Status |
|---|---|
| Live ARCore camera view | **Working** (M0/M1) |
| Tracking state + center-depth readout (HUD) | **Working** |
| Runtime diagnostics: device, ARCore availability, Depth API support, camera permission | **Working** |
| Tap-to-place 3D arrow pin (hit test; depth-assisted when available) | **Working** |
| Pin label / rename / confirm / delete | **Working** |
| Vehicle profile (seeded Corolla; editable; VIN optional + local) | **Working** |
| Save/load sessions + pins on-device (JSON, versioned schema) | **Working** |
| Project ZIP export (VIN can be excluded) | **Working** |
| Delete all local data | **Working** |
| Reopening a session re-aligns pins to the real car automatically | **Not implemented** — reopened pins are inspection data in their original session frame; alignment is planned (M2/M5) |
| RGB/depth frame capture for later reconstruction | Planned (M2) |
| Point-cloud fusion / meshing | Planned (M2, behind a flag, with accuracy warnings) |
| Manual import with page-level citations | Planned (M3) |
| "Prove it" evidence drawer | Data model present; UI planned (M3) |
| AI part identification | Planned (M4), off by default, opt-in for any upload |
| Nreal Air mirrored instruction HUD | Planned (M5) |

Nothing in the app invents manufacturer facts. There are no built-in
service-manual quotations, torque specs, or jump-start ground points.

## Build

Requirements: JDK 17, Android SDK platform 35 + build-tools 35.

```sh
./gradlew :app:assembleDebug     # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest # JVM unit tests for the data layer
```

This repo was first built in a Linux sandbox with a user-local toolchain:

```sh
export JAVA_HOME=~/workspace/tools/jdk17
export ANDROID_HOME=~/workspace/tools/android-sdk
```

On a normal dev machine, Android Studio or a standard SDK install works
the same way; `local.properties` with `sdk.dir=...` is the alternative.

### Verified build (2026-11-07, sandbox)

- `:app:assembleDebug` — **succeeded**. `app-debug.apk` (~56 MB),
  package `com.aimechanic.app`, versionName `0.1.0`, targetSdk 35,
  debug-signed; signature verified with `apksigner`, badging checked
  with `aapt2` (CAMERA permission, ARCore native libs for arm64-v8a
  included).
- Data-layer unit tests — **6/6 pass** (`ProjectStoreTest`: seed
  profile, JSON round-trip, session save/load, provenance defaults,
  ZIP export with and without VIN). In the sandbox they were executed
  directly with JUnitCore because the sandbox's network policy blocks
  the Gradle test-worker channel; on a normal machine
  `./gradlew :app:testDebugUnitTest` runs them.
- Sandbox note: that environment also blocks JVM networking and the
  Gradle daemon, so dependencies were staged into a local file repo
  with curl and Gradle ran in-process. None of that affects the app
  or a normal build.
- **Not yet verified on a physical device.** ARCore behavior, depth
  availability, and pin stability need a real S22 Ultra run — install
  the APK, open Diagnostics, then try a scan.

## Permissions

- **Camera** — required for the AR scan; requested at runtime before a
  scan starts. No photos or video leave the device.
- No network permission is used by the MVP. No accounts, no analytics,
  no cloud upload. Projects live in app-scoped storage and leave the
  phone only through the explicit ZIP export (share sheet).

## Data layout (on device and in exports)

```
project.json
sessions/<sessionId>/session.json   (pins live here, with provenance)
readme.txt                          (exports only)
```

Pin coordinates are meters in the ARCore world frame **of their own
session**. That frame does not persist across app restarts — see the
feature matrix before assuming a reopened pin points at the real part.

Example export content lives in `examples/example-project/` (illustrative
positions, clearly marked as example data, not a real scan).

## Design rules (from the project spec)

1. Observed, documented, and inferred information stay separate.
2. Never relabel guessed geometry as scanned geometry.
3. The UI stays minimal; evidence expands on demand ("Prove it").
4. Vehicle-specific procedure steps require a real, applicable source
   document; without one, the app must say so.
5. Local-first and exportable; cloud AI is opt-in only.

## License

Not yet chosen by the project owner. Until a license file appears here,
all rights are reserved by the author.

## Roadmap

M0/M1 (this release) → M2 scan capture + viewer + manual alignment →
M3 document import + evidence drawer → M4 assisted identification →
M5 glasses HUD + session relocalization. See the issue list in the
project handoff for the full breakdown.
