# Changelog

## v0.4.0-phase4 — Storage + Productization

- 增加 READ / WRITE / MIXED app-private Storage Stress 和 128/256/512 MB 档位。
- 增加 10% 可用空间上限、2 GiB reserve、临时文件停止清理与异常退出恢复。
- 将 Storage 接入统一 Controller、实时指标和 Session Result；Extreme 默认保持 Storage OFF。
- 增加持久化 History、详情、20–50 条限制和确认清除。
- 增加 JSON/诊断日志导出、只读分享 provider、Device/AI capability 与 Settings 页面。
- 增加可选环境变量 release signing、Git 元数据和 Phase 4 JVM 单元测试。

## v0.3.0-phase3 — Extreme Combined Stress

- CPU、Vulkan GPU 和 Memory 统一状态机、任意组合、Session 计时与峰值。
- 增加 Thermal SEVERE 自动 STOP、20 次启停与真机 Extreme 回归。

## v0.2.0-phase2 — Vulkan GPU

- 增加 Vulkan Compute pipeline、GPU capability、workload activity 和安全资源释放。

## v0.1.0-phase1 — CPU + Memory

- 增加 native CPU/Memory Stress、设备与内存监控、基础 Dashboard 和生命周期清理。
