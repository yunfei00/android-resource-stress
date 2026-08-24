# Changelog

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
