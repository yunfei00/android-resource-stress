# Android Resource Stress

Android Resource Stress 是一个完全离线运行的 Android 真机资源压力、热稳定性和工程观测工具。它主动调动 CPU、Vulkan GPU、内存和可选的 app-private Storage I/O；它不是 Benchmark，不计算综合分数，也没有排行榜或云服务。

当前稳定发布版本为 `0.5.0` / `v0.5.0`。`feature/gpu-3d-stress` 分支已完成 GPU 3D Stress Phase 5 集成；正式稳定性验收、版本发布和 Tag 属于后续 Phase 6，本阶段不提前发布。

## Purpose

项目用于观察真机在持续、可控负载下的资源活动、频率、温度、电池侧功率估算、Android Thermal 状态和资源释放。所有压力必须由用户主动开始；安装、升级、重启或进程恢复后始终为 `IDLE`。

## Features

- Native C++17 CPU Stress：25% / 50% / 75% / 100% duty cycle
- Vulkan Compute GPU Stress：启动校准、3 个 bounded in-flight batch、timestamp query
- Vulkan Visual GPU Stress：专用近全屏页面、SurfaceView / ANativeWindow / Vulkan swapchain 和实际 FPS
- OpenGL ES 3 GPU 3D Stress：Water Race、Particle、Shader、Geometry、Overdraw 五类真实场景
- GPU Compute / Video Rendering / 3D scenes / MAX GPU Stress 统一模式
- GPU 3D 固定循环、逐档位遍历、逐场景遍历和可配置 step duration
- Native Memory Stress：256 MB / 512 MB / 1 GB / Auto-safe
- Storage READ / WRITE / MIXED：128 / 256 / 512 MB app-private working set
- CPU、GPU、Memory、Storage 任意组合与统一 START/STOP 状态机
- 新 Thermal 策略、Thermal Timeline、Time to MODERATE/SEVERE
- CPU policy frequency、Thermal Zone、GPU sysfs capability、Battery voltage/current/power 观测
- Generic / Root / Qualcomm / MediaTek 工程监控框架与无 Root 安全 fallback
- 中英文资源国际化、跟随系统语言和手动语言持久化
- Session Result、最近 20/30/50 条 History、JSON 和 Diagnostic Log 导出
- Device Information、Settings、FileProvider + Android ACTION_SEND
- 用户显式 START 后由 Foreground Service 持有 Session，支持 Home / Screen Off 后继续

## Quick Start

1. 在 Dashboard 选择 Preset、资源、GPU 模式、目标和 Duration。
2. Storage 默认关闭；首次启用时阅读 Flash wear 提示。
3. 选择亮屏或灭屏测试，点击 START。`STARTING`/`RUNNING` 期间不能重复启动；STOP ALL 与通知栏 STOP 走统一释放路径。
4. 观察资源活动、CPU/GPU 频率能力、Thermal、传感器和电池侧指标。
5. Session 完成后查看 Recent Result、Thermal Timeline、History 或导出 JSON。

Home、打开其他 App 或 Activity recreate 只会让 UI detach，不结束已经显式启动的 Session。Continuous 只取消时长限制，不会取消 CRITICAL+ Thermal Protection。整个 App 进程真正重启后仍默认 `IDLE`，绝不会偷偷恢复压力。

## CPU Stress

每个逻辑 CPU 对应一个 native worker。worker 混合整数、浮点和小数组访问，atomic sink 防止优化消除；低于 100% 时使用约 100 ms duty cycle。

```text
Core Equivalent = process CPU time delta / wall time delta × 100
App CPU Load     = Core Equivalent / logical CPU count
```

这些值仅表示本 App 的活动。CPU Target 不等于系统总 CPU utilization。

## GPU Compute Stress

GLSL compute shader 在构建时由 NDK `glslc` 编译并嵌入 native library。worker 重复处理 64 MB storage buffer并执行 FP32/向量读写。启动时用 Vulkan timestamp（不支持时使用 CPU wall-clock fallback）最多三轮调整 workgroup / repetition，目标主要 batch 约 8～20 ms。steady state 使用 3 个可复用 command buffer/fence 的 bounded in-flight ring：等待最老 batch 后立即补交该 slot，不使用 `vkQueueWaitIdle()` 形成逐 batch 队列空洞。只有资源释放使用 `vkDeviceWaitIdle()`。

GPU Target 是 workload duty-cycle，不是硬件 GPU utilization。Dispatch Rate 绝不会换算成伪造的利用率。

## GPU Visual Stress

Visual 使用专用 `GpuVisualActivity`。约 80%～90% 屏幕是实际 Vulkan 输出：`SurfaceView` 的 Surface 经 `ANativeWindow` 创建 Android Vulkan surface、FIFO swapchain、render pass、同步对象和持续 procedural fragment scene。用户看到的动画本身就是实时 graphics workload，不是视频、Canvas 假动画或 decoder 负载；底部只保留 FPS、Frame Time、Elapsed、Thermal 和 STOP。

- `Compute`：已有 Vulkan Compute
- `Visual`：onscreen Vulkan graphics；Surface 不可用时自动切 Compute fallback
- `Mixed`：Vulkan Compute + onscreen Vulkan graphics

Visual Surface 在 Activity `onStop()` / Surface destroy 时停止，Service Session 不停止。Mixed 的 Compute 从不因 Surface 生命周期重启；Visual-only 在不可见/灭屏期间切换到 Compute，Surface 恢复后停止 fallback 并恢复 Visual。Phase 4 的 bounded offscreen graphics backend 仍保留，但 v0.5 可见场景不会与它重复叠加。FPS 是 swapchain 实际呈现指标，不是综合跑分。

## GPU 3D Stress

GPU 3D 使用独立 `Gpu3dStressActivity`、`GLSurfaceView` 和 OpenGL ES 3 renderer，不替换现有 Vulkan Compute/Visual 核心。所有模式由同一个 Foreground Service Session 持有，并复用统一 Start、Stop、Duration、Thermal、History、Log 与 Result 管线。

- `3D Water Race`：水面、船体、赛道门和动态相机
- `Particle Stress`：大量动态粒子与透明混合
- `Shader Stress`：提高 fragment shader 计算量
- `Geometry Stress`：提高 mesh/triangle/draw-call 压力
- `Overdraw Stress`：多层透明覆盖与高 fragment fill
- `MAX GPU Stress`：Vulkan Compute 与最高档 Overdraw 同时运行

每个 3D 场景支持 LOW / MEDIUM / HIGH / EXTREME / MAX。`Fixed` 持续循环所选场景和档位；`Traverse Levels` 自动遍历同一场景的五个档位；`Traverse Scenes` 自动遍历五类场景。step duration 只控制遍历切换间隔，整个 Session 仍由统一 Duration 或用户 STOP 结束。结果保存峰值/最低 FPS、平均/最大 Frame Time 和稀疏场景切换事件；这些值是实时渲染活动指标，不是综合跑分。

## Background and Screen-Off

`StressForegroundService` 独占 `CombinedStressController`、Session timer、Thermal protection 和各资源生命周期；Stress / Monitor / Visual Activity 只 bind/observe。Android 14+ 使用声明完整的 `specialUse` Foreground Service，常驻通知展示 Preset、Elapsed、Thermal，并提供 Open / Stop。

Session RUNNING 时持有非引用计数的 `PARTIAL_WAKE_LOCK`，任何 STOP、失败和 Service destroy 都释放。灭屏模式在资源成功进入 RUNNING 后显示 3 秒倒计时；普通 App 没有安全的公共自动熄屏 API，因此明确提示用户手动关屏，不申请 Device Admin、Device Owner、root，也不修改 Screen Timeout。

```text
CPU / Memory / Storage / GPU Compute  screen off 后继续
Mixed                                  Visual pause，Compute 继续
Visual-only                             Compute fallback；亮屏并恢复 Surface 后回到 Visual
3D scene-only                           3D Surface pause，Compute fallback；恢复后回到原场景
MAX GPU Stress                          3D Surface pause，Compute 持续运行
```

Duration、CRITICAL+ 或资源错误结束灭屏 Session 时，App 使用普通 Android wake capability best-effort 点亮显示；失败时以完成通知兜底，不尝试自动解锁。

## Memory Stress

内存以 8 MB private anonymous mappings 分块分配并逐页触碰；native worker 持续读、改写和复制 1 MB chunk。STOP 时 join worker 并 `munmap`。

Auto 为 `min(Available RAM × 20%, 1536 MB)`；启动和分配始终保留 Android low-memory threshold 或 256 MB（取较大值）。Memory Activity 是 workload 处理速率，不是内存分数。

## Storage Stress

Storage 只操作 `cacheDir/storage_stress/stress.bin`，不会读取照片、Downloads、Documents 或其他用户文件，也不申请外部存储权限。

- READ / WRITE / MIXED，默认 MIXED
- LOW / MEDIUM / HIGH：128 / 256 / 512 MB
- 1 MiB I/O chunk、4 MiB prepare buffer、每轮写入后一次 `force(false)`
- `min(configured, available × 10%, available - 2 GiB)`，小于 16 MiB 时拒绝

STOP 会通知、join、close、删除临时目录并归零；下次启动清理异常退出的 orphan 文件。Storage 默认 OFF，重复 Flash 写入可能增加磨损。

## Extreme Mode

```text
CPU 100% · GPU Compute 100% · Memory Auto · Storage OFF
```

Storage 只有用户主动勾选后才加入。Memory → Storage → GPU → CPU 的启动过程逐项验证，任一步失败都进入同一 cleanup。

## Thermal Monitoring

应用保留 Android 原始状态名，并在中文界面显示“严重（SEVERE）”等双语状态。保护策略不可关闭：

```text
NONE / LIGHT         continue
MODERATE             continue + warning + Thermal Event
SEVERE               continue + red warning + Thermal Event
CRITICAL+            immediate STOP ALL
```

SEVERE 是观察可能降频的重要阶段，不再是停止条件。Session 只在状态变化时追加 `ThermalEvent(elapsedTime, status, batteryTemperature)`，并计算 Time to MODERATE/SEVERE。

Battery Temp 是电池温度，不等于 CPU/SoC 温度。Thermal Zone 名称和可读性由设备 BSP/SELinux 决定。

## Engineering Monitor

后台以约 1 秒周期缓存硬件快照，UI 不在主线程遍历 sysfs：

- CPU：自动发现 `policy*` 或 `cpu*/cpufreq`，读取 current/min/max
- Thermal：扫描全部可读 `thermal_zone*`，识别 °C/m°C、过滤 -40～200 °C 异常值
- GPU：只在可靠 KGSL/devfreq/vendor 节点存在时显示频率或利用率，否则 Unsupported
- Battery：Android API 的 level/status/voltage/current；功率标为 Estimated Battery Power
- SoC：公开 Build 信息、board platform、fingerprint 和 kernel

CPU 频率是观测值；项目不声称变化一定由 Thermal 引起。

## Root Enhancements

`RootCapabilityDetector` 先查找 `su`，存在时才以超时保护执行 `su -c id` 并确认 `uid=0`。无 Root 时不会循环请求 su，所有压力、History 和 Export 仍正常。

```text
HardwareMonitor
├── GenericAndroidMonitor
├── RootHardwareMonitor
├── QualcommMonitor
└── MediaTekMonitor
```

Qualcomm/MediaTek backend 当前负责 vendor discovery 和可用节点框架。BSP 私有节点将在真实 Root 工程机上补充；缺少节点只显示 Unsupported/Unknown，绝不猜值。

## Session History

Session 以向后兼容的版本化 JSON 保存于 app-private files。默认保留最近 30 条，Settings 可选 20/30/50。schema 4 内容包含配置、资源峰值、Thermal Timeline、CPU policy frequency 起始/最低/峰值/结束、电池侧 power observation、停止原因、screen mode/off duration/transitions/fallback/wake、GPU 3D 场景/档位/遍历配置、FPS/Frame Time 范围和稀疏 Session Event Timeline。旧 schema 缺失字段时使用安全默认值。

## Export

结果 JSON 顶层包含 `app`、`device`、`session`、`screen`、`cpu`、`gpu`、`memory`、`storage`、`thermal`、`cpuFrequencies` 和 `power`。文件写入 `cacheDir/exports`，由非导出、只读 ContentProvider 通过 ACTION_SEND 临时授权。

Diagnostic Log 只记录 App start、Root/capability detection、Session/resource start、Thermal change、warning/error 和 stop，不读取系统 logcat，也不记录 worker loop。

## Localization

英文位于 `res/values/strings.xml`，简体中文位于 `res/values-zh-rCN/strings.xml`。默认 Follow System；Settings 可持久选择跟随系统、简体中文或 English。CPU、GPU、RAM、PSS、Vulkan、JNI、Dispatch、FPS、GB/s、MB/s、ms 等技术缩写保留。

## Build

| Component | Version |
|---|---:|
| compileSdk / targetSdk | 36 |
| minSdk | 29 |
| Android Gradle Plugin | 9.3.0 |
| Gradle | 9.5.0 |
| JDK | 17 |
| Build Tools | 36.0.0 |
| Android NDK | 29.0.14206865 |
| CMake | 3.22.1 |

```bash
./gradlew clean :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

正式 signing 可通过 `ANDROID_RELEASE_STORE_FILE`、`ANDROID_RELEASE_STORE_PASSWORD`、`ANDROID_RELEASE_KEY_ALIAS`、`ANDROID_RELEASE_KEY_PASSWORD` 四个环境变量注入。仓库不包含密钥或密码；缺失 secrets 不影响 Phase debug APK。

## Release

push `v*` Tag 会触发唯一的 `.github/workflows/release-apk.yml`，生成：

```text
android-resource-stress-v0.5.0-debug.apk
SHA256SUMS.txt
```

## Architecture

Stress / Monitor / History 页面只负责配置与显示；`StressForegroundService` 持有 `CombinedStressController` 和当前 Session；`HardwareMonitorService` 缓存工程快照；Session persistence/export/diagnostic 与压力核心分离。详见 [`docs/architecture.md`](docs/architecture.md)。

## Safety

- 只有用户显式 START 才施压；后台延续仅适用于已开始的 Session，进程重启始终 IDLE。
- Activity `onStop()` 只 detach UI / pause Visual Surface；用户 STOP、Duration、资源错误和 CRITICAL+ 才统一 STOP ALL。
- Screen-Off 使用 PARTIAL_WAKE_LOCK，并在所有停止/异常路径释放；自动屏幕控制仅为 capability-dependent best effort。
- Thermal Protection 永远开启，不提供绕过入口。
- Memory 保留安全 RAM；Storage 保留 2 GiB 且最多取可用空间 10%。
- GPU command submission 有界；Visual/Compute worker 均可停止并 join。
- GPU 3D Surface 在 pause/destroy 时同步停止 renderer；Service Session 按模式保留 Compute 或启用安全 fallback。
- Manifest 不申请 INTERNET、外部存储或 MANAGE_EXTERNAL_STORAGE。

## Limitations

- GPU Target != system GPU utilization。
- Battery Temp != CPU/SoC temperature。
- Estimated Battery Power != SoC/CPU/GPU power；current_now 的正负号按原始方向显示，厂商约定可能不同。
- sysfs 节点可能受 SELinux/厂商权限限制；不可读时显示 Unsupported/Unavailable/Unknown。
- Performance values are activity indicators, not benchmark scores。
- `NPU Stress: Planned / Not implemented`。本版本没有 QNN、HTP、MediaTek APU、NeuroPilot、NPU workload/utilization/benchmark。
- Debug Release 不是应用商店正式签名产物。
- 本版本不实现 DDR frequency/lock/control，也不做 Qualcomm/MediaTek 私有 BSP 深度适配。

## Roadmap

v0.5.0 发布后等待用户真机体验反馈。Qualcomm/MediaTek 私有工程监控、DDR 和 NPU 适配仅在后续有真实工程设备与独立范围时考虑；本版本不创建 `v1.0.0`。
