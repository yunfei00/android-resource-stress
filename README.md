# Android Resource Stress

Android Resource Stress 是一个面向 Android 真机的资源压力工具，用于主动调动 CPU 和 RAM，观察设备在持续高负载下的发热、降频和稳定性。

它不是跑分软件：项目不计算综合分数，界面中的 CPU Load 和 Memory Activity 仅用于观察当前压力工作量。

## 当前阶段

当前版本为 **Phase 1 / `v0.1.0-phase1`**，包含：

- 设备、系统、CPU 核心数和内存信息
- C++17 CPU Stress（25% / 50% / 75% / 100% duty cycle）
- C++17 Memory Stress（256 MB / 512 MB / 1 GB / Auto）
- App CPU Load 与 Core Equivalent 实时采样
- Total/Available/System Used RAM、App PSS、Native PSS 实时采样
- Native 实际分配量与 Memory Activity 实时采样
- 电池温度与 Android Thermal Status
- Thermal Status 达到 `SEVERE` 或更高时自动停止
- 明确的 START / STOP 状态机和 Activity 生命周期清理

本阶段不包含 GPU、Vulkan、Storage 或 NPU 压力。

## 架构

```text
app/src/main/
├── AndroidManifest.xml
├── java/com/androidresourcestress/
│   ├── MainActivity.kt          # 原生 View UI、刷新循环、生命周期
│   ├── StressController.kt      # START/STOP 并发状态机
│   ├── NativeStress.kt          # 集中的 JNI 接口
│   ├── DeviceMonitor.kt         # 设备、电池温度、Thermal Status
│   ├── CpuMonitor.kt            # 进程 CPU 时间采样
│   ├── MemoryMonitor.kt         # 系统内存、PSS、Memory Activity
│   └── ByteFormatter.kt         # 统一的人类可读容量格式
├── cpp/
│   ├── native_stress.cpp        # JNI_OnLoad + RegisterNatives
│   ├── cpu_stress.cpp/.h        # CPU worker 与 duty cycle
│   ├── memory_stress.cpp/.h     # 分块提交、持续访问、释放
│   └── CMakeLists.txt
└── res/                         # XML 布局、主题和 Launcher 图标
```

应用启动只加载 native library 和读取监控数据。CPU worker、内存分配和 memory worker 只会在用户点击 START 后创建。

## CPU Stress

CPU 压力运行在 native C++ 层，每个逻辑核心默认对应一个 `std::thread`。worker 混合执行整数变换、浮点运算和小数组访问，并把结果写入 atomic sink，避免计算被编译器消除。

25% / 50% / 75% 档位使用约 100 ms 周期的计算/休眠 duty cycle；100% 档位持续计算，但仍频繁检查停止标志。

实时 CPU 指标来自：

```text
Core Equivalent = delta process CPU time / delta wall time × 100
App CPU Load     = Core Equivalent / logical CPU core count
```

因此 8 个核心接近全满时，Core Equivalent 约为 800%，App CPU Load 约为 100%。该指标只代表本 App，不代表 System CPU Usage。

## Memory Stress

内存按 8 MB 分块申请。每块申请后按 4 KB stride 写入，并触碰最后一个字节，使虚拟内存页实际提交。分配完成后，一个 native worker 持续按 1 MB chunk 执行读取、校验和、修改和 `memcpy` 回写。

`processedBytes` 记录累计处理量，界面使用相邻采样间的增量计算 Memory Activity。它是压力工作量观测值，不是内存跑分。

Auto 目标为：

```text
min(Available RAM × 20%, 1536 MB)
```

目标按 MiB 向下对齐。Android 报告 `lowMemory` 时拒绝启动；固定档位也必须保留至少系统 low-memory threshold 或 256 MB（取较大值）。部分 native 分配失败会保留已成功的实际分配量并继续安全运行，不会越界或 double free。

## 安全与生命周期

- 首次打开始终是 CPU `STOPPED`、Memory `STOPPED`。
- native library 加载不会自动启动 worker 或申请压力内存。
- START 在后台控制线程执行，不阻塞 Android 主线程。
- STOP 设置原子取消标志，等待 CPU/memory worker `join`，再释放所有 native 内存。
- 重复 START、重复 STOP 和快速 START/STOP 由 generation + latch 状态机收敛到单一安全状态。
- `Activity.onStop()` 和 `onDestroy()` 都会触发清理；Phase 1 不允许后台持续施压。
- Thermal Status 达到 `SEVERE`、`CRITICAL`、`EMERGENCY` 或 `SHUTDOWN` 时自动 STOP。
- 高负载会快速增加耗电与温度。请保持设备通风，并使用 STOP 随时结束测试。

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

## Roadmap

- **Phase 2**：Vulkan GPU Stress
- **Phase 3**：CPU + GPU + RAM Extreme 联合负载、增强稳定性和热保护
- **Phase 4**：Storage、产品化 UI、日志、Release 和完整文档

Roadmap 中的功能尚未在 Phase 1 提前实现。
