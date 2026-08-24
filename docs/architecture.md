# Architecture

## Components

```text
Stress / Monitor / History / Settings UI
                 │
                 │ bind / observe
                 ▼
       StressForegroundService
 notification · timer · WakeLock owner
                 │
                 ▼
       CombinedStressController
 serialized state · thermal · session
      │         │         │         │
     CPU       GPU      Memory    Storage
     JNI   Compute/Vulkan  JNI   app-private I/O
                │
                ├── 3 bounded Compute batches
                └── Phase 4 offscreen backend (retained)

GpuVisualActivity / SurfaceView
                 │ Surface lifecycle only
                 ▼
 ANativeWindow → Vulkan surface/swapchain → onscreen graphics
                 │
                 └── Service Session remains alive when detached

Monitoring
HardwareMonitorService → GenericAndroidMonitor
                         ├── RootHardwareMonitor
                         ├── QualcommMonitor
                         └── MediaTekMonitor

Session → schema-versioned History → JSON export / ACTION_SEND
Events  → private Diagnostic Log   → ACTION_SEND
```

There is one stress state machine. Activities never create a competing controller or own native workers. `StressForegroundService` constructs one `CombinedStressController`, forwards immutable snapshots to bound observers, and returns `START_NOT_STICKY`; a real process restart therefore defaults to `IDLE` and never silently restarts stress.

## State Machine

```text
IDLE → STARTING → RUNNING → STOPPING → IDLE
          │          │
          └→ ERROR ←─┘
                     └→ THERMAL_LIMITED → STOPPING
```

START is accepted only in IDLE. A generation token cancels stale work and one executor serializes resource transitions. Duplicate UI or service START intents do not create a second native worker set. Session termination is limited to user/notification STOP, duration, CRITICAL+, fatal resource/service error, or explicit service termination.

## Activity, Background, and Screen Lifecycle

```text
Activity onStop
→ observer/UI detach
→ onscreen Vulkan Surface stops
→ Service Session continues

Screen off
→ PARTIAL_WAKE_LOCK keeps CPU execution available
→ CPU / Memory / Storage / Compute continue
→ Mixed: Visual pauses, existing Compute continues
→ Visual-only: bounded Compute fallback starts

Screen on + Visual Surface attached
→ onscreen Visual resumes
→ Visual-only Compute fallback stops
```

The Activity never calls `stopAll()` from `onPause()` or `onStop()`. Rebinding reconstructs UI from the Service snapshot, preserving the same session ID and elapsed time. The Surface worker is joined on Surface destroy; its loss cannot create or restart the Compute worker.

`StressWakeLockController` owns a non-reference-counted `PARTIAL_WAKE_LOCK`. RUNNING acquires it; STOP, finish, error, and Service destroy release it. A screen-off session records a wake request/result before cleanup and uses a short public Android wake lock as best effort. It does not unlock the device, use root/device-admin APIs, change Screen Timeout, or make automatic screen control a prerequisite.

## GPU Stress V2

### Compute

The 64 MiB FP32/vector Compute workload records three command buffers, fences, and timestamp query pools. Startup calibration measures up to three attempts and scales workgroups/repetitions toward an 8–20 ms primary batch window (12 ms target); CPU elapsed time is the fallback when timestamps are unavailable.

Steady state primes three bounded submissions, waits only the oldest fence, reads its timestamp/checksum, immediately resubmits that slot, and advances the ring. It never calls `vkQueueWaitIdle()` per batch; `vkDeviceWaitIdle()` is reserved for teardown. 25/50/75 targets add a bounded duty interval and are workload activity settings, not claimed hardware utilization.

### Visual and Mixed

`GpuVisualActivity` dedicates most of the display to a real Vulkan swapchain scene. `VulkanVisualSurfaceView` passes its Surface through JNI to an `ANativeWindow`; native code creates an Android surface, FIFO swapchain, render pass, procedural fragment pipeline, two presentation frames in flight, and actual FPS/Frame Time counters.

The Phase 4 640×640 offscreen renderer remains in the native backend for compatibility, but v0.5 does not stack it with the onscreen scene. Visual uses onscreen graphics while attached and Compute fallback while unavailable. Mixed starts Compute once and combines it with onscreen graphics; after detach only that same Compute worker remains.

## Resource Cleanup

Unified cleanup stops and joins CPU, Compute, the retained offscreen backend, onscreen Surface ownership, Memory, and Storage. Storage flushes/closes its scoped file and removes `cacheDir/storage_stress`. Partial starts and every authorized stop reason share this path. Runtime GPU/Memory/Storage failures are classified into the versioned stop-reason model.

## Thermal Protection

```text
NONE / LIGHT    continue
MODERATE        continue + warning + event
SEVERE          continue + red warning + event
CRITICAL+       THERMAL_LIMITED → STOP ALL
```

`ThermalEvent` is appended only when Android's raw status changes. SEVERE never stops a test; CRITICAL, EMERGENCY, and SHUTDOWN map to distinct stop reasons. The policy has no disable switch.

## Hardware Monitoring

`HardwareMonitorService` publishes a cached immutable `HardwareSnapshot` about once per second. `GenericAndroidMonitor` discovers readable cpufreq policies, thermal zones, KGSL/devfreq nodes, and BatteryManager/power-supply data. Missing or denied data stays Unsupported/Unavailable/Unknown. Root and Qualcomm/MediaTek backends are discovery extension points only; v0.5 adds no DDR, NPU, or private BSP control.

## Session Data and Compatibility

Each run accumulates CPU/Core Equivalent, PSS/Memory Activity, Dispatch/Work Time, Visual FPS/Frame Time, Storage totals/rates, Thermal Timeline, CPU frequency observations, and battery-power observations. Schema 3 adds `screenMode`, off/on timestamps, cumulative screen-off duration, transition count, Compute fallback, wake result/reason, and a sparse Session Event Timeline.

Finish freezes one `StressSessionSnapshot`, saves newest-first history, and trims to 20/30/50. Earlier Phase 4 JSON has safe Screen On / no-event defaults and legacy stop reasons are mapped. Runtime state is never persisted as a restart instruction.

## Localization, Export, and Permissions

English resources live in `values/strings.xml`; Simplified Chinese lives in `values-zh-rCN/strings.xml`; Follow System uses an empty app locale list. Result JSON includes app/device/session/screen/cpu/gpu/memory/storage/thermal/power and CPU-frequency data. Export and Diagnostic files stay under `cacheDir/exports` and use a non-exported read-only provider with temporary ACTION_SEND grants.

The manifest requests only Foreground Service (`specialUse` on Android 14+), notification, and WakeLock permissions. It requests no INTERNET, external-storage, device-admin, root, DDR, or NPU capability.

## Release Workflow

The single `release-apk.yml` workflow runs on `v*`, performs a clean debug build, produces `android-resource-stress-${tag}-debug.apk` plus `SHA256SUMS.txt`, and uploads both to the matching GitHub Release. No APK or signing secret is committed.
