# Changelog

## Unreleased — GPU 3D Integration (Phase 5)

- 将 Water Race、Particle、Shader、Geometry、Overdraw 接入统一 GPU Stress 选择与 Foreground Service Session。
- 增加 `MAX GPU Stress`：最高档 3D Overdraw 与 Vulkan Compute 同时运行。
- 增加 Fixed、Traverse Levels、Traverse Scenes 和可配置 step duration，固定场景持续循环，遍历模式按 Session 时长循环执行。
- GPU 3D 复用统一 START/STOP、Duration、Thermal Protection、Screen-Off fallback、Diagnostic Log、History、Result 和 JSON Export。
- Session schema 升级为 4，保存 GPU 3D 配置、峰值/最低 FPS、平均/最大 Frame Time 和场景切换事件，并兼容旧数据。
- 增加 Phase 5 JVM 配置/序列化测试和真机 instrumentation harness；正式稳定性与发布验收留待 Phase 6。

## 0.5.0 — GPU / Background / Screen-Off / UI Upgrade

- GPU Stress V2：三轮启动校准、3 个 bounded in-flight Compute batch，移除 steady-state 全队列 idle。
- 增加专用近全屏 Visual 页面和 SurfaceView / ANativeWindow / Vulkan swapchain 实时场景。
- Mixed 使用持续 Compute + onscreen graphics；Surface detach 后 Compute 不停止或重启。
- 增加持有整个 Session 的 `StressForegroundService`、常驻通知 Open/Stop 和 Android 14+ `specialUse` 声明。
- Home / Activity recreate 不再停止 Session；进程真正重启仍安全回到 IDLE。
- 增加 Screen On/Off 测试、`PARTIAL_WAKE_LOCK`、Visual-only Compute fallback 和 best-effort wake。
- Session schema 增加 screen/wake 字段与稀疏 Event Timeline，并兼容旧 Phase 4 数据。
- 首页升级为无需纵向滚动的紧凑 2×2 Dashboard，增加 Stress / Monitor / History 导航和详细 Monitor 页。
- 保留并回归 Storage、Thermal Timeline、History、Export、Diagnostic、Device Info、Settings 和中英文/跟随系统。

## v0.4.0-phase4 — Final Productization

- 增加 English / 简体中文正规资源国际化、Follow System 和持久化手动语言选择。
- 将 Thermal 策略更新为 MODERATE/SEVERE 继续、CRITICAL+ 立即停止，并增加 Thermal Timeline。
- 增加 CPU policy frequency、Thermal Zone、GPU hardware capability 和电池电压/电流/估算功率监控。
- 增加 Root capability、Generic/Root/Qualcomm/MediaTek backend discovery 框架和无 Root fallback。
- 增加 Vulkan graphics Visual Stress、前台动画、实际 FPS/Frame Time 和 Compute/Visual/Mixed 模式。
- 增加 READ / WRITE / MIXED app-private Storage Stress、128/256/512 MB 档位及空间/清理保护。
- 将 Storage 与 Visual 接入统一 Controller、Session Result、History、JSON 和 Diagnostic Log。
- 增加 Device Information、Settings、20/30/50 条历史上限和 FileProvider 分享。
- 增加 release signing 环境变量、Git 元数据和有价值的 JVM/真机回归测试。
- 明确 NPU Stress 为 Planned / Not implemented；本版本不包含任何 NPU workload 或利用率。

## v0.3.0-phase3 — Extreme Combined Stress

- CPU、Vulkan GPU 和 Memory 统一状态机、任意组合、Session 计时与峰值。
- 增加初版 Thermal 保护、20 次启停与真机 Extreme 回归；Phase 4 将停止阈值修订为 CRITICAL+。

## v0.2.0-phase2 — Vulkan GPU

- 增加 Vulkan Compute pipeline、GPU capability、workload activity 和安全资源释放。

## v0.1.0-phase1 — CPU + Memory

- 增加 native CPU/Memory Stress、设备与内存监控、基础 Dashboard 和生命周期清理。
