# Android Resource Stress

Android Resource Stress 是一个面向 Android 真机的资源压力工具，用于主动调动 CPU、RAM 和 Vulkan Compute GPU，观察设备在持续高负载下的发热、降频和稳定性。

它不是跑分软件：项目不计算综合分数，界面中的 CPU Load、Memory Activity、Dispatch Rate 和 Compute Activity 仅用于观察当前压力工作量。

## 当前阶段

当前版本为 **Phase 3 / `v0.3.0-phase3`**，复用 Phase 1/2 已验证的 native CPU、Memory 和 Vulkan GPU 核心，并提供：

- 设备、系统、CPU 核心数和内存信息
- C++17 CPU Stress（25% / 50% / 75% / 100% duty cycle）
- C++17 Memory Stress（256 MB / 512 MB / 1 GB / Auto）
- App CPU Load 与 Core Equivalent 实时采样
- Total/Available/System Used RAM、App PSS、Native PSS 实时采样
- Native 实际分配量与 Memory Activity 实时采样
- Vulkan physical device 与 compute capability detection
- C++17 Vulkan compute stress（25% / 50% / 75% / 100% duty cycle）
- GPU Dispatch Count/Rate、timestamp Work Time、Workgroups/s 和输出 checksum
- CPU、GPU、Memory 任意一种或任意组合的资源选择
- BALANCED（50/50/512 MB）、HIGH（75/75/Auto）、EXTREME（100/100/Auto）预设与 Custom 参数
- 30 秒、1/2/5/10/30 分钟及 Continuous Session Timer
- Memory → GPU → CPU 有序启动、逐步验证和部分失败全量回滚
- `IDLE / STARTING / RUNNING / STOPPING / THERMAL_LIMITED / ERROR` 统一状态机
- 单一 `STOP ALL` 路径、Session 峰值/停止原因与最近一次错误
- Battery Temp 的 Start / Current / Peak / Delta 与完整 Android Thermal Status
- `MODERATE` 升温提示；`SEVERE` 或更高状态自动停止所有压力资源
- RUNNING 时使用 `FLAG_KEEP_SCREEN_ON`，停止后恢复系统屏幕策略
- 切入后台立即停止，不使用 CPU WakeLock 或后台服务

本阶段不包含 Storage Stress、NPU Stress、综合跑分、排行榜或云端服务。

## 架构

```text
app/src/main/
├── AndroidManifest.xml
├── java/com/androidresourcestress/
│   ├── MainActivity.kt          # 原生 View Dashboard、参数选择、状态观察
│   ├── CombinedStressController.kt # 统一状态机、资源生命周期、热保护
│   ├── StressSession.kt         # 配置、Duration、Session 与 StopReason
│   ├── StressController.kt      # Phase 1 控制器（核心回归保留）
│   ├── GpuController.kt         # Phase 2 控制器（核心回归保留）
│   ├── GpuMonitor.kt            # Vulkan capability 与 activity 格式化
│   ├── NativeStress.kt          # 集中的 JNI 接口
│   ├── DeviceMonitor.kt         # 设备、电池温度、Thermal Status
│   ├── CpuMonitor.kt            # 进程 CPU 时间采样
│   ├── MemoryMonitor.kt         # 系统内存、PSS、Memory Activity
│   └── ByteFormatter.kt         # 统一的人类可读容量格式
├── cpp/
│   ├── native_stress.cpp        # JNI_OnLoad + RegisterNatives
│   ├── cpu_stress.cpp/.h        # CPU worker 与 duty cycle
│   ├── memory_stress.cpp/.h     # 分块提交、持续访问、释放
│   ├── gpu_stress.cpp/.h        # GPU worker 与 duty cycle
│   ├── vulkan_context.cpp/.h    # Vulkan context、pipeline 与同步
│   ├── shaders/stress.comp      # FP32 + vec4 + storage buffer shader
│   └── CMakeLists.txt
└── res/                         # XML 布局、主题和 Launcher 图标
```

应用启动只加载 native library、读取监控数据并检测 Vulkan 能力。CPU/GPU worker、压力内存和 64 MB GPU storage buffer 只会在用户主动点击 START 后创建。MainActivity 不分别管理 native 状态；所有组合都由 `CombinedStressController` 拥有一个 Session，并通过同一个停止路径释放。

## Extreme Combined Mode

默认 EXTREME 会选择 CPU + GPU + Memory，并设置 CPU/GPU Target 为 100%、Memory 为 Auto-safe、Duration 为 5 分钟。用户可以单独关闭资源，支持 CPU-only、GPU-only、Memory-only、CPU+GPU、CPU+Memory、GPU+Memory 和三项全开共七种组合。手动改变资源或 Target 后预设显示为 CUSTOM。

联合启动先检查 Thermal、Android `lowMemory` 和 Vulkan Compute capability，然后严格按 Memory → GPU → CPU 启动并验证 native 状态。任一步失败都会进入 ERROR，停止并释放已启动的所有模块，再回到 IDLE，不会留下半运行状态。用户停止、计时结束、严重热状态、Activity 进入后台和运行时错误也都调用相同的 `stopAll()`。

Session 仅保存在内存中，记录配置、开始和经过时间、CPU/Core Equivalent、App/Native PSS、Memory Activity、GPU Dispatch Rate、电池温度和最高 Thermal Status 的峰值，以及 `USER`、`DURATION_COMPLETED`、`THERMAL`、`ACTIVITY_STOPPED` 或 `RESOURCE_ERROR` 停止原因。

## CPU Stress

CPU 压力运行在 native C++ 层，每个逻辑核心默认对应一个 `std::thread`。worker 混合执行整数变换、浮点运算和小数组访问，并把结果写入 atomic sink，避免计算被编译器消除。

25% / 50% / 75% 档位使用约 100 ms 周期的计算/休眠 duty cycle；100% 档位持续计算，但仍频繁检查停止标志。

实时 CPU 指标来自：

```text
Core Equivalent = delta process CPU time / delta wall time × 100
App CPU Load     = Core Equivalent / logical CPU core count
```

因此 8 个核心接近全满时，Core Equivalent 约为 800%，App CPU Load 约为 100%。该指标只代表本 App，不代表 System CPU Usage。在 Combined Mode 中，App CPU Load 包含 CPU stress、Memory worker、GPU submission、UI 和 monitor 线程的总 CPU 时间；CPU Target 仅控制 CPU stress worker 的 duty cycle，两者不是同一个含义。

## Memory Stress

内存按 8 MB 分块创建 private anonymous mapping，并按 Android native heap 规则标记为 `libc_malloc`。每块映射后按 4 KB stride 写入，并触碰最后一个字节，使虚拟内存页实际提交；STOP 时使用 `munmap` 及时归还页面。分配完成后，一个 native worker 持续按 1 MB chunk 执行读取、校验和、修改和 `memcpy` 回写。

`processedBytes` 记录累计处理量，界面使用相邻采样间的增量计算 Memory Activity。它是压力工作量观测值，不是内存跑分。

Auto 目标为：

```text
min(Available RAM × 20%, 1536 MB)
```

目标按 MiB 向下对齐。Android 报告 `lowMemory` 时拒绝启动；固定档位也会限制在安全可用范围。分配期间每个 8 MB block 都重新读取 `MemAvailable`，至少保留系统 low-memory threshold 或 256 MB（取较大值）。安全阈值或部分 native 分配失败可使实际 Allocated 小于 Target；界面始终显示真实分配量，不会为了凑满目标冒险 OOM。

## Vulkan GPU Stress

GPU 压力使用真实 Vulkan Compute pipeline。GLSL shader 在构建时由 NDK `glslc` 编译并嵌入 native library；每次 dispatch 处理 64 MB storage buffer 中的 `vec4`，重复执行 FP32 FMA、向量轮换、dot coupling、读写与归一化运算，并回读 16 bytes 计算 checksum，证明输出持续变化。

worker 使用一个可复用 command buffer 和一个 fence，任意时刻最多一个 submission in flight，不会 flood queue。支持时通过 timestamp query 测量单次 GPU Work Time。25% / 50% / 75% 使用 100 ms duty cycle，100% 持续进行 bounded submission。GPU Target 是工作负载档位，不代表厂商系统 GPU Utilization 百分比。

STOP GPU 会 join native worker 并释放 buffer、memory、pipeline、descriptor、command、fence 和 query 资源，只保留轻量 instance/device capability context；Activity 销毁时连同 context 一并释放。不支持 Vulkan Compute 的设备会显示 `UNSUPPORTED`，CPU/RAM 功能仍可使用。

## 安全与生命周期

- 首次打开处于全局 `IDLE`，CPU/Memory 为 `OFF`，GPU 为 `STOPPED` 或 `UNSUPPORTED`。
- native library 加载不会自动启动 worker 或申请压力内存。
- START 在后台控制线程执行，不阻塞 Android 主线程。
- STOP ALL 先停止并 join CPU，再等待并清理 GPU queue/worker，最后停止 Memory worker 并 `munmap` 全部分配。
- 重复 START、重复 STOP 和快速 START/STOP 由 generation + 单线程控制队列收敛到单一安全状态。
- `Activity.onStop()` 和 `onDestroy()` 都会触发清理；Phase 3 不允许后台持续施压。
- Thermal Status 达到 `SEVERE`、`CRITICAL`、`EMERGENCY` 或 `SHUTDOWN` 时自动 STOP。
- Continuous 只取消计时限制，不会取消 Thermal Protection。
- Battery Temp 来自 Android 电池广播，不是 CPU 或 SoC 温度；GPU Target 也不是系统 GPU utilization。

高负载测试可能导致快速耗电、设备明显发热、系统降频或应用被系统终止。工具提供 Thermal Protection，但用户仍应保持散热区域无遮挡，不要在高温环境中长时间运行，不要在充电时长时间运行 EXTREME，也不要主动绕过 Thermal Protection。

## 构建环境

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
| C++ | C++17 |

项目使用 AGP 9.3 的内置 Kotlin 支持，不应用旧的 `kotlin-android` 插件，也不依赖 Jetpack Compose 或第三方运行时库。

## Build

准备好上表中的 Android SDK、NDK、Build Tools、CMake 和 JDK 17 后，在仓库根目录执行：

```bash
./gradlew :app:assembleDebug --console=plain
```

Windows PowerShell：

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
```

Debug APK 输出路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 安装

设备开启 USB 调试并通过 `adb devices` 可见后：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

安装完成后，应用会以 **Android Resource Stress** 出现在桌面或应用列表中。

## GitHub Release

push 任意 `v*` tag 会触发 `.github/workflows/release-apk.yml`。workflow 使用 JDK 17 和固定 Android/NDK/CMake 版本重新构建，并从 Git tag 自动生成文件名，在同名 GitHub Release 中发布：

```text
android-resource-stress-<tag>-debug.apk
SHA256SUMS.txt
```

普通 branch push 不会创建 Release，APK 二进制也不会提交进 Git 仓库。

## 后续范围

Storage、NPU、产品化跑分、排行榜和云端能力不属于 Phase 3，本版本不会提前实现这些功能。
