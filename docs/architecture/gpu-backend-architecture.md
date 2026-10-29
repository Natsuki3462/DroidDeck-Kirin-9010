# DroidDeck GPU 后端架构设计

> 目标读者：后续维护 DroidDeck 的工程师
> 状态：已落地（`app/src/main/java/com/droiddeck/launcher/gpu/probe/`）
> 关联：[麒麟支持路线图](kirin-roadmap.md)

---

## 1. 问题：一个等价关系被当成了架构

上游把两个问题合并成了一个：

```
"这台设备是 Adreno 吗？"  ==  "这台设备能跑 Linux 会话吗？"
```

在只有高通设备的年代这个等价关系是成立的，于是它被写进了四个地方：

| 位置 | 形式 |
|---|---|
| `core/DeviceSupport.adreno()` | 两个路径的 `File.exists()` |
| `gpu/GpuInfo.Family.NOT_ADRENO` | 非 Adreno 一律 `Support.UNSUPPORTED` |
| `runtime/LinuxRuntime.vulkanIcd()` | 优先找 `freedreno`（Turnip）的 ICD |
| `FrontEndActivity:2434` | 非 Adreno 直接弹"不支持"卡片并 return |

要支持第二、第三个 GPU 厂商，必须先把这个等价关系拆开。**加 `if` 不行**：每来一颗芯片就多一个分支，分支之间互相不知道对方存在，而"某型号能跑"会被误读成"这个厂商能跑"。

---

## 2. 约束（来自实机与社区证据）

这些不是假设，是必须尊重的硬约束：

1. **Turnip 只驱动 Adreno**。它是 Mesa 的 freedreno Vulkan 驱动，与 Mali/Maleoon 无关。
2. **Maleoon 没有开源驱动**。华为 Vulkan 驱动是 bionic 链接的闭源 blob，在 glibc 的 proot 容器里 `dlopen` 失败。麒麟 9000S / 9010 / 9020 属于这一类。
3. **kbase 分 CSF 与 JM 两代**。Mali-G615 是 CSF，Mali-G78（麒麟 9000）是 JM。CSF 专用构建驱动 JM 必然失败。
4. **GPU 驱动崩溃的代价不是"卡"，是整机重启**。社区 fork 的审计文档记录了一次实际发生的冻结+重启，且没有留下 last_kmsg。
5. **不可审计的二进制不可默认分发**。fork 的 21.5 MB 后端无源码，且其自证材料对应的 hash 与实际发布的不是同一个。

---

## 3. 设计目标

| 目标 | 验收方式 |
|---|---|
| Adreno 行为零变化 | 既有 `GpuDetectionTest` 全部通过；`familyOf(adreno, model)` 签名不变 |
| 新增 GPU 厂商只加一个类 | 新后端实现 `RenderBackend`，注册进 `BackendRegistry.backends` |
| 检测与选择可在无真机时验证 | 全部走 `ProbeEnv`，测试用 `FakeEnv` 描述设备 |
| 默认永不把未验证的 GPU 交给 guest | `GpuPolicy.AUTO` 只选 `Risk.SAFE` |
| 失败必须可解释 | `BackendPlan.reason` 是必填字段 |

---

## 4. 分层

```
┌─────────────────────────────────────────────────────────────┐
│ 表现层  FrontEndActivity / DriverMenus / DeviceReport        │
│         只问"用什么后端、风险是什么"，不判断厂商              │
├─────────────────────────────────────────────────────────────┤
│ 门面    GpuBackends.probe() / decide(context) / policy()     │
├─────────────────────────────────────────────────────────────┤
│ 策略    BackendRegistry.select(probe, policy)                │
│         偏好序 + 风险排序，纯函数                             │
├─────────────────────────────────────────────────────────────┤
│ 契约    RenderBackend  ──>  BackendPlan                      │
│         Turnip │ PanVK-kbase │ lavapipe │ vendor-bridge      │
├─────────────────────────────────────────────────────────────┤
│ 事实    GpuProbe.probe(env)  ──>  GpuProbeResult             │
│         KirinCatalog / DeviceProfiles                        │
├─────────────────────────────────────────────────────────────┤
│ 环境    ProbeEnv（文件 / 属性 / API / 厂商 / Vulkan 设备名）  │
│         AndroidProbeEnv（真机） │ FakeEnv（测试）             │
└─────────────────────────────────────────────────────────────┘
```

**依赖方向严格向下**：`RenderBackend` 不认识 Android，`GpuProbe` 不认识策略，策略不认识 UI。所以整条链路能在 JVM 单测里跑。

---

## 5. 关键决策与权衡

### 5.1 风险三档，而不是"支持/不支持"

```kotlin
enum class Risk { SAFE, EXPERIMENTAL, BLOCKED }
```

- `SAFE`：已在目标硬件上验证过的路径（Adreno+Turnip、lavapipe）。
- `EXPERIMENTAL`：可能能跑，但需要用户显式导入驱动并承担风险（Mali CSF + PanVK）。
- `BLOCKED`：**不是"没试过"，是"不可能"**（Maleoon 硬件加速、Mali JM 上的公开 PanVK 构建）。

> 权衡：为什么不用布尔值 `supported`？因为布尔值无法表达"现在不行但将来行"，而 Maleoon 恰恰需要这个状态——它必须能被用户看到（"你的 GPU 我们认识，但暂时没有驱动"），而不是被折叠进"未知设备"。

### 5.2 默认策略只允许 SAFE

```kotlin
GpuPolicy.AUTO -> usable.firstOrNull { it.risk == Risk.SAFE }
```

后果：Mali 和 Maleoon 默认都落到 **lavapipe（CPU）**。

> 权衡：这意味着 Mali 设备默认**不用**它的 GPU，性能损失巨大。这是刻意的。理由来自约束 4：默认开启一个可能让手机重启的驱动，是不可接受的默认值。想要性能的用户在设置里显式选择 `PREFER_HARDWARE`。

### 5.3 硬件请求不会被 CPU 后端"抢走"

```kotlin
GpuPolicy.PREFER_HARDWARE -> usable
    .filter { it.driverRequirement != DriverRequirement.CPU_SOFTWARE }
    .minByOrNull { rank(it.risk) }
```

> 权衡：如果按风险全局排序，SAFE 的 lavapipe 永远赢过 EXPERIMENTAL 的 PanVK，"请求硬件"就成了空操作。所以硬件策略先过滤掉 CPU 后端，再按风险排。

### 5.4 CSF 与 JM 是两个判定，不是一个

`PanVkKbaseBackend.evaluate()` 对 `KbaseInterface.CSF` 返回 `EXPERIMENTAL`，对 `JM` 返回 `BLOCKED`。

> 权衡：JM 上标记 BLOCKED 会让麒麟 9000（Mate 40 等主力机型）只能软件渲染。这是当前事实——公开的 PanVK-kbase 构建都是 CSF 的。给一个装不上的驱动入口比不给更糟：用户会导入、会启动、会得到黑屏，然后无法判断是自己操作错了还是驱动不兼容。

### 5.5 不把 `/dev/mali0` 伪装成 DRM 节点

```kotlin
binds = listOf("/dev/mali0:/dev/mali0")
```

> 权衡：社区 fork 把 `mali0` 映射成 `/dev/dri/renderD<minor>`，并伪造 `/sys/dev/char/.../drm` 树让 gamescope 和 MangoHud 相信有 DRM 主节点。代价是：任何打开 `renderD*` 的软件都会拿 DRM ioctl 去打 `mali_kbase`。虽然 type/magic 不同、大概率 `-ENOTTY`，但"大概率"不是对内核 `unlocked_ioctl` 分发的保证。本设计选择让 PanVK 自己打开 kbase 节点。

### 5.6 探测用证据，不用品牌

优先级：Adreno（kgsl 节点/驱动库，无歧义）→ Maleoon（GPU 自报名/华为驱动库/Maleoon 时代 SoC 且无 mali 节点）→ Mali（kbase 节点/ARM 驱动库）→ 其他厂商。

> 权衡：为什么不先查 SoC 表？因为 `ro.board.platform` 在华为设备上极不统一（`kirin9000` / `hi3660` / 营销名混用），而 `/dev/mali0` 的存在是物理事实。SoC 表用于**补充**（型号、kbase 代次、调优画像），不用于**判定厂商**。

---

## 6. 契约

### 6.1 `ProbeEnv`

探测层唯一允许访问的东西。`AndroidProbeEnv` 读真机，`FakeEnv` 读测试里描述的设备。

```kotlin
interface ProbeEnv {
    fun exists(path: String): Boolean
    fun readText(path: String): String?
    fun property(name: String): String
    val sdkInt: Int
    val hardware: String
    val manufacturer: String
    val vulkanDeviceName: String?
}
```

### 6.2 `RenderBackend`

```kotlin
interface RenderBackend {
    val id: String
    fun evaluate(probe: GpuProbeResult): BackendPlan?   // null = 不是我的设备
}
```

新增一个 GPU 厂商 = 实现这个接口 + 加进 `BackendRegistry.backends`。**不需要改任何既有代码**。

### 6.3 `BackendPlan`

| 字段 | 含义 |
|---|---|
| `risk` | 三档风险 |
| `gpuInGuest` | 是否把 GPU 内核节点交给 guest |
| `driverRequirement` | 需要什么驱动（runtime 自带 / 用户导入 / CPU / 无） |
| `env` | guest 环境变量增量（ICD 路径由调用方组合） |
| `dxvk` | 需要的 `dxvk.conf` 键值 |
| `binds` | `src:dst` 绑定 |
| `reason` | **必填**，一句话说明为什么，供 bug report 引用 |

---

## 7. 失败模式

| 失败 | 表现 | 本设计的处理 |
|---|---|---|
| 未识别的 GPU | 黑屏 + 有声音 | 兜底到 lavapipe，`reason` 说明原因 |
| 用户导入错代次的 PanVK | 启动即失败 | JM/CSF 判定 + `driverRequirement=IMPORTED_ICD` 提示 |
| kbase 驱动 hang | 整机重启 | 默认不交 GPU；硬件路径需显式 opt-in |
| 驱动列表与设备不符 | 装了跑不动 | `GpuInfo.vendor` 参与列表匹配，非 Adreno 不再走 Adreno 列表 |

---

## 8. 验证策略

真机不可得（没有麒麟设备），所以验证靠两层：

1. **JVM 单测**（`app/src/test/.../gpu/probe/`）：用 `FakeEnv` 描述设备，断言探测结果与后端选择。覆盖：Adreno 优先级、Kirin 9000/970/9010、CSF vs JM、Maleoon 三种识别路径、所有策略组合、CPU 后端永不绑 GPU 节点、`AUTO` 永不选 EXPERIMENTAL。
2. **真机灰度**：`DeviceReport` 输出探测结果与所选后端，让用户回传。

> 未覆盖：驱动本身能否真正初始化。这需要真机，见路线图第 2 阶段。

---

## 9. 扩展点（下一个厂商怎么接）

以三星 Xclipse（RDNA）为例：

1. `GpuProbe` 已能识别为 `SAMSUNG_XCLIPSE`。
2. 新建 `backends/XclipseBackend.kt`，实现 `RenderBackend`，在 `evaluate()` 里返回 `EXPERIMENTAL` 或 `BLOCKED`。
3. 加进 `BackendRegistry.backends`。
4. 加单测。

不动 `GpuInfo`、不动 `DeviceSupport`、不动 UI。

---

## 10. 真机校准：设计假设被推翻的两处

**设备**：Kirin 9010，HarmonyOS，Android 12 (API 31)。

设计完成后用 `probe-device.sh` 采集真机事实，结果修正了两条假设。这两条如果不改，检测会在真机上**直接失效**：

| 假设 | 真机事实 | 后果 |
|---|---|---|
| Maleoon 驱动是 `/vendor/lib64/hw/vulkan.maleoon.so` | 实际是 **`vulkan.hvgr_v200.so`**（`hvgr` = Huawei GPU renderer） | 按名字硬编码 → 漏检 |
| `ro.soc.model` 带型号 | 实际只是 **`"Kirin"`**；型号在 `ro.board.platform = Kirin9010` | 只读 soc.model → 拿不到型号 |

**修正方式不是再加一个常量，而是改变探测策略**：`GpuProbe.driverLibs()` 枚举 `/vendor/lib64/hw/` 目录，按文件名模式归类厂商（`adreno` / `hvgr`|`maleoon` / `mali` / `xclipse` / `powervr`）。已知路径只作为快速通道。这样下一个把驱动改名成 `vulkan.foo_v3.so` 的厂商不会再次打穿。

### 10.1 真机还确认了这些"缺失"

| 探测项 | 9010 实测 | 意义 |
|---|---|---|
| `/dev/mali0` | **不存在** | 印证 Maleoon 不走 kbase，PanVK 无从下手 |
| `/sys/class/misc/mali0` | **不存在** | 同上 |
| `/dev/dri`（含 `card0`、`renderD128`） | **不存在** | 见下：**不是阻塞点** |
| `/sys/class/kgsl/kgsl-3d0` | 不存在 | 非高通 |
| `/sys/class/devfreq/gpufreq` | **存在** | GPU 负载可读，性能面板可用（不依赖 kbase） |

### 10.1.1 `/dev/dri` 缺失为什么不阻塞

一开始我据此判断"gamescope 需要 headless 后端"，**这个判断是错的**。读代码后确认：

```
guest（proot / glibc）                      host（Android app 进程）
┌───────────────────────────┐              ┌──────────────────────────────┐
│ gamescope --backend wayland│  wayland    │ compositor.c                 │
│ lavapipe（CPU 渲染）        │ ──────────→ │  take_shm() → 上传 Vulkan 纹理│
│ 产出 wl_shm 缓冲            │   socket    │  vk_present.c → Maleoon 910  │
└───────────────────────────┘              └──────────────────────────────┘
```

- gamescope 的启动参数是 `--backend wayland --expose-wayland`，它是**嵌套**在合成器里，从不直接开 DRM 设备。
- 宿主合成器 `take_shm()`（`compositor.c:715`）完整支持 `wl_shm`，并把像素 `vkp_image_upload_shm()` 上传为 Vulkan 纹理；它甚至统计 `g_stat_shm` / `g_stat_dmabuf` 两种来源。
- 呈现走 `vk_present.c` 的 `vkCreateInstance`，跑在 app 进程里 —— 用的正是 Maleoon 的 `hvgr` 驱动（bionic，Android 侧）。

所以软件渲染是通的：**GPU 负责显示，CPU 负责渲染**。代价是每帧多一次 CPU→GPU 上传（shm 路径无法零拷贝），这会影响帧率但不影响可用性。

> 教训：`/dev/dri` 缺失是"guest 不需要它"，不是"没人能显示"。判断之前必须看 gamescope 的实际参数，而不是假设它直连 DRM。

### 10.2 真机回放测试

`Kirin9010RealDeviceTest`（12 个用例）把上述采集值原样喂给 `GpuProbe`，断言：

- 识别为 `HUAWEI_MALEOON` / `Maleoon 910` / `kirin9010`
- `kernelNode == null`、`kbase == NONE`
- `driverLib == /vendor/lib64/hw/vulkan.hvgr_v200.so`
- 默认策略选 `lavapipe` 且不绑任何 GPU 节点
- 请求硬件时 `vendor-bridge` 为 `BLOCKED`，理由含 `bionic`
- 调优为 60% / 60Hz / 6 线程

这使"检测与选择"从"逻辑上正确"变成"对这台真机正确"，且可回归。

