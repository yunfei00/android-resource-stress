# Android Resource Stress

Android Resource Stress 是一个完全离线运行的 Android 真机资源压力、热稳定性和资源活动观察工具。它主动调动 CPU、Vulkan Compute GPU、内存和可选的 app-private 存储 I/O；它不是 Benchmark，不计算综合分数，也没有排行榜或云服务。

当前产品验收版本为 `0.4.0` / `v0.4.0-phase4`。

## Purpose

本项目用于观察真机在持续、可控负载下的资源活动、发热、Android Thermal 状态、停止行为和资源释放。所有压力测试都必须由用户主动开始；应用启动和重启后始终保持 `IDLE`。

## Features

- Native C++17 CPU Stress：25% / 50% / 75% / 100% duty cycle
- Native C++17 Memory Stress：256 MB / 512 MB / 1 GB / Auto-safe
- Vulkan Compute GPU Stress：真实 compute pipeline、bounded submission、timestamp query
- Storage Stress：READ / WRITE / MIXED，128 / 256 / 512 MB working set
- CPU、GPU、Memory、Storage 任意组合与统一 START/STOP 状态机
- BALANCED、HIGH、EXTREME 和 CUSTOM 配置；EXTREME 默认不启用 Storage
- Battery Temp、Android Thermal Status 和 `SEVERE` 自动停止保护
- 最近一次 Session Result、本地历史、详情页和历史上限
- 单次结果 JSON 导出、Android `ACTION_SEND` 分享、应用自身诊断日志导出
- Device Information、Vulkan capability 和保守的 AI/NPU capability 信息
- 原生 Android View Dashboard 与 Settings；无 Compose、无网络依赖

## Screens / Usage

1. 在 Dashboard 选择 Preset、资源、目标和 Duration。
2. Storage 默认关闭；首次主动启用时阅读闪存磨损提示并确认。
3. 点击 START。`STARTING` 和 `RUNNING` 期间不能重复启动，`STOP ALL` 走统一释放路径。
4. 观察各资源活动、温度和明确的文字状态。进入后台会立即停止所有资源。
5. Session 完成后查看 Recent Result，或从 History 打开详情并导出 JSON。

Settings 可设置默认 Preset/Duration、运行时保持屏幕点亮、20–50 条历史上限和 Storage 确认提示。Thermal Protection 始终开启且不能关闭。

## CPU Stress

每个逻辑 CPU 默认对应一个 native worker。worker 混合整数、浮点和小数组访问，结果写入 atomic sink 以避免优化消除。低于 100% 的档位采用约 100 ms duty cycle，100% 持续计算但频繁检查停止标志。

```text
Core Equivalent = process CPU time delta / wall time delta × 100
App CPU Load     = Core Equivalent / logical CPU count
```

这些值只表示本应用的活动。CPU Target 控制 CPU stress worker 的 duty cycle，不等于系统总 CPU utilization。

## Vulkan GPU Stress

GLSL compute shader 在构建时由 NDK `glslc` 编译并嵌入 native library。worker 重复处理 64 MB storage buffer，执行 FP32、向量读写和 checksum 回读。一个可复用 command buffer 和 fence 保证最多一个 submission in flight，不会 flood queue。支持时使用 timestamp query 报告 GPU Work Time。

GPU Target 是 workload duty-cycle 档位，不是厂商系统 GPU utilization 百分比。不支持 Vulkan Compute 的设备会明确显示 `UNSUPPORTED`，其他资源仍可使用。

## Memory Stress

内存以 8 MB private anonymous mappings 分块分配，按页触碰以提交物理页；native worker 持续读、改写和复制 1 MB chunk。STOP 时 join worker 并 `munmap`。

Auto 目标为 `min(Available RAM × 20%, 1536 MB)`。启动和分块分配过程持续保留 Android low-memory threshold 或 256 MB（取较大值）；系统报告 low memory 或安全空间不足时拒绝启动。Memory Activity 是 workload 处理速率，不是内存跑分。

## Storage Stress

Storage 只操作 `cacheDir/storage_stress/stress.bin`，不会读取照片、文档或任何用户文件，也不申请广泛存储权限。

- `READ`：准备 working file 后顺序读取
- `WRITE`：顺序重写 working file
- `MIXED`：顺序写入后读取并循环，默认模式
- `LOW / MEDIUM / HIGH`：目标 working set 为 128 / 256 / 512 MB
- I/O chunk：1 MiB；准备 buffer：4 MiB；每轮写入后一次合理的 `force(false)`，不按小块 fsync

实际 working set：

```text
min(configured target, available app-volume space × 10%, available space - 2 GiB)
```

结果按 MiB 向下对齐，小于 16 MiB 时拒绝启动。STOP 会通知并 interrupt worker、最多等待 10 秒 join、关闭 `RandomAccessFile`/`FileChannel`、删除临时目录并归零。Activity 进入后台同样停止；下次启动会清理异常退出遗留文件。

Storage 默认关闭。重复 Flash 写入可能增加闪存磨损，应避免不必要的长时间 Storage 测试。

## Extreme Mode

标准 EXTREME 始终默认：

```text
CPU 100% · GPU 100% · Memory Auto · Storage OFF
```

Storage 只有在用户主动勾选并确认后才加入组合。统一控制器按 Memory → Storage（如启用）→ GPU → CPU 启动，并验证每个资源；任一步失败会释放已启动资源。所有停止原因最终收敛到同一 cleanup 路径。

## Thermal Protection

应用持续读取 Android `PowerManager.currentThermalStatus` 和电池广播温度。`MODERATE` 显示警告，`SEVERE` 或更高状态自动停止全部资源。Continuous 只取消时长限制，不会取消 Thermal Protection。

Battery Temp 是电池温度，不是 CPU/SoC 温度。不要遮挡散热、在高温环境持续运行或尝试绕过系统热保护。

## Session History

每次完成的 Session 以版本化 JSON 存入 app-private files，默认保存最近 20 条，可设为 20–50 条。记录时间、配置、enabled resources、停止原因、CPU/GPU/Memory/Storage 峰值、温度与最高 Thermal 状态。History 按最新优先显示，点击查看完整详情；清除历史需要确认且不会改变设置。

## Export

Recent Result 和 History Detail 可导出结构化 JSON，顶层包含 `version`、`device`、`session`、`cpu`、`gpu`、`memory`、`storage`、`thermal`。文件写入 `cacheDir/exports`，由只读、非导出的自定义 ContentProvider 通过 `ACTION_SEND` 临时授权给分享目标。

Diagnostic Log 只记录本应用的启动、状态、资源启动、Thermal 警告、错误和 Session 停止事件；不会读取系统 logcat，不需要 root。

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

项目使用 AGP 内置 Kotlin 支持、原生 Android View 和 C++17，不依赖 Compose 或第三方运行时库。

`release` buildType 默认不混淆。正式签名通过以下环境变量注入，仓库不保存 keystore 或密码：

```text
ANDROID_RELEASE_STORE_FILE
ANDROID_RELEASE_STORE_PASSWORD
ANDROID_RELEASE_KEY_ALIAS
ANDROID_RELEASE_KEY_PASSWORD
```

四项全部存在时 `assembleRelease` 使用该 signing config；缺失时不影响 Phase tag 的 debug APK 构建。

## Release APK

push `v*` tag 会触发唯一的 `.github/workflows/release-apk.yml`：配置 JDK/Android SDK/NDK/CMake、执行 clean debug build、生成 SHA-256，并创建或更新 GitHub Release。`v0.4.0-phase4` 的资产名为：

```text
android-resource-stress-v0.4.0-phase4-debug.apk
SHA256SUMS.txt
```

## Architecture

Dashboard 和独立页面只负责配置与显示；`CombinedStressController` 独占全局状态机和压力生命周期；CPU/GPU/Memory 通过 JNI 进入 native C++，Storage 使用 app-private File I/O。Session persistence、export 和 diagnostic log 与压力核心分离。详见 [`docs/architecture.md`](docs/architecture.md)。

## Safety

- 默认启动状态永远是 `IDLE`，不会恢复上次运行状态或后台自动施压。
- `onStop()`、Duration、用户 STOP、资源错误和 Thermal 均进入统一 STOP ALL。
- RUNNING 时可选保持屏幕点亮，停止后立即清除 flag；不使用 WakeLock 或后台服务。
- Memory 保留安全 RAM；Storage 保留 2 GiB 且最多使用可用空间 10%。
- Storage 临时文件只在 app-private cache 中，正常停止删除，异常退出后下次启动清理。
- Manifest 不包含 `INTERNET`、外部存储或 `MANAGE_EXTERNAL_STORAGE` 权限。

## Limitations

- GPU Target != system GPU utilization。
- Battery Temp != CPU/SoC temperature。
- NPU Stress is not currently implemented；无法可靠确认 dedicated accelerator 时显示 `Unknown`，不会根据 SoC 名称猜测。
- Performance values are activity indicators, not benchmark scores。
- Android 和厂商可能限制可观察的 Thermal、GPU 或 AI 信息。
- Debug Phase Release 不是面向应用商店的正式签名产物。

## Roadmap

四个正式开发 Phase 已完成。`v0.4.0-phase4` 先用于最终真机体验确认；不会在未经确认时自动 promotion 为 `v1.0.0`。本项目不规划综合分数、排行榜、云服务、用户系统、广告、后台长期烧机或绕过 Thermal Protection。
