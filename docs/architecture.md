# Architecture

## Components

```text
Native View UI
Main / History / Detail / Device / Settings
                    │
                    ▼
        CombinedStressController
        configuration + state machine
        thermal + timer + session metrics
          │       │       │       │
          ▼       ▼       ▼       ▼
        CPU      GPU    Memory   Storage
        JNI      JNI      JNI    FileChannel
          └───────┴───────┘       app cache
                  │
          C++17 / Vulkan Compute

Session → JSON history → Result export / ACTION_SEND
Events  → private diagnostic log → ACTION_SEND
```

The existing CPU, Vulkan GPU and Memory native cores remain isolated behind `NativeStress`. Phase 4 adds Storage as another resource owned by the same controller rather than a second global state system.

## State Machine

```text
IDLE → STARTING → RUNNING → STOPPING → IDLE
          │          │
          └→ ERROR ←─┘
                     └→ THERMAL_LIMITED → STOPPING
```

START is accepted only in `IDLE`. A generation token cancels stale queued starts/stops, and the controller uses one serialized executor. UI configuration is disabled outside `IDLE`. Repeated START or STOP cannot create duplicate workers or duplicate cleanup.

## Resource Lifecycle

Start validates selected resources and current Thermal state, then resolves safe Memory/Storage sizes. Resources start in order Memory → Storage (if selected) → GPU → CPU, with a running-state check after each step. Partial failure uses the same cleanup path.

Cleanup stops and joins CPU, GPU, Memory and Storage workers. Storage closes its scoped `RandomAccessFile`/`FileChannel`, removes `cacheDir/storage_stress`, and resets counters. `StorageStress` also cleans orphan files in its constructor and before each start.

`Activity.onStop()`, user STOP, duration completion, resource errors and Thermal protection all call `stopAll()`. No stress resource is intentionally allowed to continue in the background.

## Thermal Protection

The controller samples `PowerManager.currentThermalStatus` and battery broadcast temperature with the other metrics. `MODERATE` is logged and shown as a warning. `SEVERE` or higher changes the state to `THERMAL_LIMITED` and invokes the unified stop path. There is no setting or code path to disable this protection.

## Session Data

Each start creates one in-memory mutable session. Periodic samples update CPU/Core Equivalent, App/Native PSS, Memory Activity, GPU dispatch/work time, Storage totals/rates, battery temperature and highest Thermal status. Finish freezes a `StressSessionSnapshot`, saves it as versioned JSON, trims history, updates Recent Result and enables export.

The reader uses tolerant defaults for fields introduced after Phase 3. Preferences store only configuration and product settings; runtime state is never persisted, so process restart always begins at `IDLE`.

## Export and Permissions

Result JSON and the app-owned diagnostic log are written below `cacheDir/exports`. A non-exported, read-only provider exposes only canonical files inside that directory using temporary URI permission granted by `ACTION_SEND`. The manifest requests no network or storage permissions.

## Release Workflow

The single `release-apk.yml` workflow runs on `v*` tags, installs pinned Android build components, creates a clean debug APK, generates `SHA256SUMS.txt`, and publishes both assets to the matching GitHub Release. Optional production signing is provided by four environment variables; no signing material is committed and missing signing secrets do not break Phase releases.
