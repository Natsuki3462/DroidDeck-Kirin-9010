# 麒麟支持路线图

> 关联：[GPU 后端架构设计](gpu-backend-architecture.md)
> 第一优先目标：**麒麟 9010**（Maleoon 910）

---

## 0. 先说清楚 9010 的现实

麒麟 9010 的 GPU 是 **Maleoon 910**，华为自研。这意味着：

| 问题 | 答案 |
|---|---|
| 有 Mesa 开源驱动吗？ | **没有**。Mesa 没有 Maleoon 后端，也不在计划内 |
| 能用华为的 Vulkan 驱动吗？ | **不能**。它是 bionic 链接的闭源 blob，glibc 容器里 `dlopen` 失败 |
| 能用 Turnip 吗？ | 不能，Turnip 是 Adreno 专用 |
| 能用 PanVK 吗？ | 不能，PanVK 只认 ARM Mali 的 kbase |
| 那能跑吗？ | **能，但只能用 CPU 渲染（lavapipe）** |

所以"实配麒麟 9010"的第一阶段目标不是"GPU 加速"，而是：

> **让 9010 上的会话稳定启动、界面可用、并且诚实地告诉用户为什么慢。**

GPU 加速是第 3 阶段的事，且需要一个独立工程（host 侧 Vulkan 桥）。

### 0.1 前置门槛：系统版本（已确认为 HarmonyOS 4.x）

**目标设备运行 HarmonyOS 4.x**，这解决了最关键的可行性问题：

| 系统 | 底层 | 能否安装 Android APK | 结论 |
|---|---|---|---|
| **HarmonyOS 4.x** | Android 12（API 31/32） | **能** | ✅ 本方案适用，且正好落在 9010 的 `minApi=31` 上 |
| HarmonyOS NEXT 5.x | 不再包含 Android 兼容层 | 不能 | ❌ APK 装不上，需鸿蒙原生重写 |

因为 4.x 有完整的 Android 运行时，`proot`、`gamescope`、`SurfaceControl` 这些依赖的 AOSP 行为都成立，本方案不需要为鸿蒙做额外适配。

**但有一处必须处理**：华为的低内存杀手（lmkd）比 AOSP 激进得多。容器内所有进程若都是 `oom_score_adj=0`，一次内存尖峰会把**整个会话**杀掉，而不是杀掉那个游戏。社区 fork 的解法是给 Steam 的 `reaper` 进程树设 `oom_score_adj=200`，让 lmkd 只终止失控的游戏。这一点已写入 `DeviceProfiles` 的 9010 画像说明，阶段 1.4 接线时必须实现。

**版本识别**：`HarmonyOs.detect()` 已实现。注意 EMUI 与 HarmonyOS 的版本号相差 10（EMUI 14 = HarmonyOS 4.0），只报 EMUI 号会让用户困惑，所以做了换算。

### 0.2 目标真机实测档案

已用 `probe-device.sh` 在 Kirin 9010 真机上采集：

| 项 | 实测值 | 对方案的影响 |
|---|---|---|
| 系统 | HarmonyOS 4.2.0，Android 12，API 31 | ✅ 可装 APK，`minApi=31` 正好 |
| SoC | `ro.board.platform=Kirin9010`，`ro.soc.model=Kirin`（无型号） | 型号必须从 `ro.board.platform` 取 |
| GPU | `Maleoon 910`（GLES: `HUAWEI, Maleoon 910, OpenGL ES 3.2`） | 与目录表一致 |
| Vulkan 驱动 | **`/vendor/lib64/hw/vulkan.hvgr_v200.so`** | 名字是 `hvgr` 不是 `maleoon` |
| `/dev/mali0` | **不存在** | Maleoon 无 kbase，PanVK 不可用 |
| `/dev/dri` | **不存在**（`card0`/`renderD128` 全无） | 不阻塞：gamescope 走 `--backend wayland` 嵌套，宿主用 Vulkan 呈现 |
| 内核 | 5.10.43 aarch64，page 4096 | — |
| CPU | 8 核 | 调优取 `8-2=6` 线程 |
| 内存 | MemTotal ≈ 15.2 GiB | 足够跑 Steam + 软件渲染 |
| 面板 | 1260×2844，540dpi，支持 120/90/60/30 Hz | 横屏 2844×1260，60% → 1706×756 |
| GPU 负载节点 | `/sys/class/devfreq/gpufreq` 存在 | 性能面板可读，不依赖 kbase |
| Phantom process 限制 | 未设置（`null`） | 需引导用户在开发者选项开启 |



---

## 阶段 1 — 麒麟 9010 软件渲染可用化（当前优先）

**目标**：9010 设备能装、能起、能用桌面，且用户知道为什么游戏慢。

| # | 工作项 | 交付物 | 验收 |
|---|---|---|---|
| 1.1 | 探测与识别 | `GpuProbe` + `KirinCatalog`（已落地，**已真机回放验证**） | 单测：9010 → Maleoon 910 → BLOCKED 硬件路径 |
| 1.2 | 设备画像 | `DeviceProfiles`：60% 渲染缩放、60Hz 上限、关帧生成/超分、`LP_NUM_THREADS`（已落地） | 单测覆盖 9000S/9010/9020 |
| 1.3 | lavapipe 驱动包 | 提供可导入的 lavapipe ICD zip（arm64），来源可复现 | 真机：`vulkaninfo` 列出 llvmpipe |
| 1.4 | 会话接线 | `SessionService` 按 `BackendPlan` 注入 env、按 `SessionTuning` 改分辨率/刷新率；不绑任何 GPU 节点 | 真机：会话启动，guest 内无 `/dev/mali0`、无 `/dev/kgsl-3d0` |
| 1.5 | 设置页 | "渲染后端：自动 / 优先硬件 / 仅软件"，默认自动 | UI 显示当前后端与 `reason` |
| 1.6 | 设备报告 | `DeviceReport` 增加 Kirin 区块：SoC、GPU、kbase、所选后端、`reason` | 用户可回传，用于灰度 |
| 1.7 | 前置检查 | phantom process 限制未设置时引导开启；lmkd `oom_score_adj` 处理 | 会话不被整容器杀掉 |

> **原 1.4「无 DRM 节点的合成路径」已撤销**。真机确认 `/dev/dri` 不存在，但读代码后确认 gamescope 用 `--backend wayland` 嵌套运行，宿主合成器 `take_shm()` 支持 `wl_shm` 并用 Vulkan（Maleoon `hvgr`）呈现。软件渲染路径是通的，只是每帧多一次 CPU→GPU 上传。详见架构文档 §10.1.1。

**风险门**：1.4 必须保证"软件模式下 guest 内不存在 `/dev/mali0`、`/dev/kgsl-3d0`、`/dev/dri`"。这是整个方案的安全底线。

**预期性能（要提前告知用户）**：
- 桌面模式（KDE Plasma）：可用
- Steam Big Picture：可用但明显掉帧
- 3D 游戏：基本不可玩

---

## 阶段 2 — Mali 时代麒麟的硬件加速（9000 / 990 / 980 / 970）

**目标**：让 Mali 设备用上 GPU，且不重蹈社区 fork 的覆辙（无源码 blob + 整机重启）。

| # | 工作项 | 说明 |
|---|---|---|
| 2.1 | 可复现构建 | 从可读源码在 CI 构建 PanVK-kbase，产物附 `SOURCE.json` + 构建脚本 + sha256 |
| 2.2 | JM 后端 | 当前公开构建都是 CSF；JM（G78/G76/G72）需要自行构建，这是 9000 能否加速的关键 |
| 2.3 | 驱动包签名与来源 | 导入时校验来源与 hash，拒绝无来源的二进制 |
| 2.4 | 崩溃取证 | 要求设备保留 `last_kmsg`；无法取证则不开放 |
| 2.5 | 灰度开关 | 按 SoC 白名单逐步放开，默认仍为软件 |

**风险门**：没有 CI 可复现构建 + 没有崩溃取证，**不进入默认路径**。

**为什么 9000 值得做**：Mali-G78 MP24 是麒麟里 GPU 最强的一代（Vulkan 1.1），且存量设备（Mate 40 系列）多。它是"能真正玩游戏"的麒麟。

---

## 阶段 3 — Maleoon 硬件加速（9010 / 9020）

**目标**：让 9010 用上 GPU。这是唯一能让 9010 跑游戏的路径。

核心思路：**把 Vulkan 调用搬到 Android 侧执行**。

```
Linux guest (glibc)                    Android host (bionic)
┌──────────────────┐                  ┌──────────────────────┐
│ DXVK / gamescope │                  │ 桥接进程              │
│      ↓           │   IPC (socket)   │   ↓                  │
│ libvulkan_bridge │ ←──────────────→ │ 华为 Vulkan 驱动      │
│  (实现 Vulkan    │   命令流 + 共享  │ (libGLES_maleoon.so) │
│   ICD 接口)      │   内存/dma-buf   │      ↓               │
└──────────────────┘                  │  Maleoon 910/920     │
                                      └──────────────────────┘
```

技术选项（按可行性排序）：

| 方案 | 原理 | 难度 | 备注 |
|---|---|---|---|
| **A. 自建 Vulkan 转发层** | guest 侧实现 ICD，序列化 Vulkan 命令到 host 进程 | 极高 | 类似 Venus/virgl 的思路，需处理同步、内存、fence |
| **B. 用现成 Venus 协议** | virtio-gpu Venus：guest 侧 `vn_renderer`，host 侧 `vn_icd` | 高 | 需要 virtio-gpu 环境；Android 上无现成宿主 |
| **C. bionic 兼容层跑 blob** | 在 glibc 下加载 bionic blob（libhybris 路线） | 极高 | ABI、tls、linker 全要适配；且仍需 Android surface |
| **D. 不做** | 9010 长期软件渲染 | — | 诚实但价值低 |

**建议**：先做 **A 的最小可行版**——只覆盖 DXVK 实际用到的 Vulkan 子集（约 60 个 entrypoint），先跑通"创建 device + 一个三角形"，再逐步补。

**这是独立工程，不应与阶段 1 混在一起排期。**

---

## 阶段 4 — 生态与可持续

| 工作项 | 说明 |
|---|---|
| 设备兼容矩阵 | 按 SoC × 后端 × 风险等级维护一张表，随版本发布 |
| 驱动市场 | 导入的驱动带元数据（适用 SoC、kbase 代次、来源、hash），不匹配则拒绝 |
| 遥测（可选、匿名） | 仅上报 SoC + GPU + 后端 + 失败点，用于灰度决策 |
| 上游贡献 | 把 `gpu/probe` 层作为 PR 提给上游，降低长期维护成本 |

---

## 附：当前进度

| 阶段 | 状态 |
|---|---|
| 架构层（探测 / 契约 / 策略 / 设备画像） | ✅ 已完成，含单测 |
| 阶段 1.3–1.7（驱动包、会话接线、UI、报告） | ⬜ 待做，需真机 |
| 阶段 2（Mali 硬件） | ⬜ 待做 |
| 阶段 3（Maleoon 桥） | ⬜ 待做，需独立立项 |

> 说明：阶段 1.3 之后的工作全部需要麒麟真机验证。本仓库的架构层之所以做成纯逻辑可单测，就是为了在没有真机的情况下也能把检测与选择做对。
