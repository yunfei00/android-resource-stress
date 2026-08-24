# Architecture

## Components

```text
Localized Native View UI
Main / History / Detail / Device / Settings
                         │
                         ▼
             CombinedStressController
       configuration + serialized state machine
       timer + thermal policy + session metrics
          │          │          │          │
          ▼          ▼          ▼          ▼
        CPU         GPU       Memory     Storage
        JNI    Compute/Visual    JNI     FileChannel
          │          │          │       app cache
          └──────────┴──────────┘
                 C++17 / Vulkan

Monitoring
HardwareMonitorService (background cached snapshot)
          │
          ▼
GenericAndroidMonitor
   ├── RootHardwareMonitor
   ├── QualcommMonitor
   └── MediaTekMonitor
          │
          ├── CPU cpufreq policies
          ├── thermal_zone scanner
          ├── KGSL/devfreq capability discovery
          └── BatteryManager / power_supply fallback framework

Session → versioned JSON history → Result export / ACTION_SEND
Events  → private diagnostic log → ACTION_SEND
```

The stable CPU, Vulkan Compute and Memory cores remain isolated behind `NativeStress`. Storage and Vulkan Visual are resources owned by the same controller, not independent global state systems.

## State Machine

```text
IDLE → STARTING → RUNNING → STOPPING → IDLE
          │          │
          └→ ERROR ←─┘
                     └→ THERMAL_LIMITED → STOPPING
```

START is accepted only in IDLE. A generation token cancels stale queued operations and one executor serializes native lifecycle changes. UI configuration is disabled while active, so repeated clicks cannot create duplicate workers.

## Resource Lifecycle

Start validates configuration and rejects only an already-CRITICAL-or-higher thermal state. It resolves safe Memory/Storage sizes, then starts Memory → Storage → GPU Compute/Visual → CPU. Visual-only creates a bounded Vulkan graphics worker; Mixed runs it alongside the existing Compute worker. Each resource must report RUNNING before start continues.

Cleanup stops and joins CPU, Vulkan Compute, Vulkan Visual, Memory and Storage. Storage closes its scoped file/channel and removes `cacheDir/storage_stress`. The foreground `GpuVisualStressView` removes Choreographer callbacks on STOP/detach. Partial start failures, Activity `onStop()`, user STOP, duration completion, resource errors and Thermal protection all use the same cleanup path.

## Thermal Protection

```text
NONE / LIGHT    continue
MODERATE        continue + warning + event
SEVERE          continue + red warning + event
CRITICAL+       THERMAL_LIMITED → STOP ALL
```

`ThermalEvent` is appended only when Android's raw status changes and contains elapsed time, raw status and battery temperature. Session analysis derives Time to MODERATE/SEVERE. The policy is a pure tested function and has no disable switch.

## Hardware Monitoring

`HardwareMonitorService` owns a scheduled background thread and publishes an immutable cached `HardwareSnapshot` about once per second. Dashboard reads are therefore non-blocking.

`GenericAndroidMonitor` discovers rather than assumes nodes. It scans cpufreq policies, readable thermal zones and GPU-related devfreq/KGSL paths, validates units/ranges, and reads BatteryManager observations. Missing/denied nodes become Unsupported. Root detection runs once with a timeout; vendor backends currently provide safe discovery extension points for future real engineering-device profiles.

## Session Data

Each run creates one mutable session. Periodic samples update CPU/Core Equivalent, App/Native PSS, Memory Activity, Compute Dispatch/Work Time, Visual FPS/Frame Time, Storage totals/rates, battery temperature, Thermal Timeline, CPU frequency start/min/peak/end and start/end/peak battery-power observations.

Finish freezes `StressSessionSnapshot`, saves schema-versioned JSON and trims newest-first history to 20/30/50. The reader supplies safe defaults for earlier Phase 4 schema. Preferences persist configuration/language only; runtime state is never persisted, so restart is always IDLE.

## Localization

Default English resources live in `values/strings.xml`; Simplified Chinese lives in `values-zh-rCN/strings.xml`. `LocalizedActivity` applies the persisted app locale before resource inflation. API 33+ also uses framework `LocaleManager`; Follow System uses an empty app locale list.

## Export and Permissions

Result JSON includes app/device/session/cpu/gpu/memory/storage/thermal/power and CPU-frequency data. Export and diagnostic files stay under `cacheDir/exports`. A non-exported, read-only provider validates canonical paths and grants temporary ACTION_SEND URI access. The manifest requests no network, root or storage permissions.

## Release Workflow

The single `release-apk.yml` workflow runs on `v*`, installs pinned Android build components, creates a clean debug APK, generates `SHA256SUMS.txt`, and uploads both to the matching Release. Optional production signing uses environment variables only; no signing material is committed.
