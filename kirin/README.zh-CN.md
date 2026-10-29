# DroidDeck · Kirin 9010 (Maleoon 910) 适配分支

[English](README.md) | **简体中文**

一组改动：让 DroidDeck 正确识别 Kirin 9010 / Maleoon 910，而不是当成未知 Adreno；如实告诉用户
这颗芯片上的会话能做什么；并且能导入 CPU 渲染驱动。这个硬件上**不可能**做 GPU 渲染（没有 Linux
驱动），软件渲染路径已经接好，并验证到 gamescope 初始化 Vulkan 这一步。

真机环境：**Kirin 9010 / Maleoon 910 / HarmonyOS（Android 12, API 31）/ 16 GB**

---

## 0. 适合谁，不适合谁

| 你想要的 | 这个分支 |
|---|---|
| 让麒麟设备上别再误判 GPU、别再引导用户装用不上的运行时 | ✅ 正是做这个的 |
| 给非 Adreno 的 GPU 加支持（Mali / PowerVR / 其它） | ✅ 框架就是为此设计的，加一个文件即可 |
| 修"导入的驱动在列表里看不见"这类通用 bug | ✅ 见第 4 节 |
| 在麒麟上玩 3D 游戏 | ❌ 硬件没有 Linux GPU 驱动，做不到 |
| 在麒麟上玩 2D 游戏 | ❌ 目前也做不到，原因见 5.2 |
| 一个开箱即用的补丁 | ❌ 它改的是行为与架构，不是加功能 |

如果你只是想让启动器在某台新设备上不崩，第 2.1 节的框架是重点；如果你关心麒麟能不能玩游戏，直接看第 5 节。

---

## 1. 这个分支解决什么

原版把"不是 Adreno"的 GPU 一律当成未知设备，然后**继续走 Turnip 路径**。在麒麟上表现为四件事同时错：

| 现象 | 后果 |
|---|---|
| 探测把 Maleoon 当 Adreno | 去加载 Turnip，必然失败 |
| 界面仍写"可以游玩" | 用户按提示装完运行时才发现跑不起来 |
| 进程数限制误报 | 明明已关闭，界面仍提示要处理 |
| 驱动包只认单文件 Turnip | 带依赖的 CPU 渲染驱动根本装不进去 |

这个分支把上面四条都修了，并且**没有改动 Adreno 路径的任何行为**。

---

## 2. 改动清单

一共 40 个文件（27 个新增）。按可独立提交的顺序：

### 2.1 GPU 探测框架（新增 `gpu/probe/`）

| 文件 | 作用 |
|---|---|
| `ProbeEnv.kt` | 探测只依赖这个接口（属性读取 + 文件存在性），因此可以在 JVM 里用 `FakeEnv` 测 |
| `AndroidProbeEnv.kt` | 真实实现 |
| `RenderBackend.kt` | 一个渲染后端：`id` / `appliesTo` / `probe` / `verdict` |
| `backends/TurnipBackend.kt` | Adreno + 导入的 Turnip |
| `backends/LavapipeBackend.kt` | CPU 渲染（llvmpipe） |
| `backends/PanVkKbaseBackend.kt` | Mali + PanVK/Kbase |
| `backends/VendorBridgeBackend.kt` | 厂商驱动直通 |
| `BackendRegistry.kt` | 注册表：**加一个厂商 = 加一个实现 + 注册一行** |
| `GpuVendor.kt` / `GpuProbe.kt` | 厂商识别与探测结果 |
| `HarmonyOs.kt` | HarmonyOS 识别（决定哪些路径可用） |
| `KirinCatalog.kt` | 麒麟型号目录（9010 → Maleoon 910） |
| `KbaseInterface.kt` | Mali Kbase 接口版本探测 |
| `DeviceProfiles.kt` | 机型画像：渲染比例、刷新率上限、线程数 |
| `SessionGpu.kt` | 把探测结果变成**会话真正执行的计划**（见 2.2） |

### 2.2 会话真正读计划（`SessionGpu.kt` + `SessionService.kt`）

`SessionGpu.plan()` 是纯函数，输入探测结果，输出：

```kotlin
SessionGpuPlan(
    keepAdrenoDefaults: Boolean,   // true 时输出与改动前逐字符相同
    icdPath: String?,              // 会话用哪个 Vulkan ICD
    libraryDir: String?,           // 驱动目录（进 guest 的 LD_LIBRARY_PATH）
    env: Map<String, String>,      // 渲染相关环境变量
    width/height/refreshCap,       // 输出尺寸与刷新率上限
    missingSoftwareDriver: Boolean // 需要先导入软件驱动
)
```

关键设计：**`keepAdrenoDefaults` 为 true 时，`SessionService` 输出与改动前完全相同的那三行
env（`MESA_LOADER_DRIVER_OVERRIDE=zink` 等），`env` 映射为空。** 这样"加厂商"不会动到已经
验证过的 Adreno 路径。

`SessionService` 的接线：

- 计划只算一次，作为参数传给 `addClientEnvironment`，保证尺寸与环境用同一个决策
- 软件会话跳过 `BL_VK_DRIVER`（否则它会覆盖我们选的 ICD）
- 软件会话跳过 `GAME_TU_DEBUG`（那是 Turnip 的）
- `BL_WIDTH/HEIGHT` 按画像缩放，取偶数（避免合成器放大后半像素接缝），下限 320

### 2.3 驱动包：从"单文件"到"一个目录"（`LinuxVulkanDriverManager.java`）

原来只认文件名以 `libvulkan_freedreno` 开头的 `.so`，只拷**一个**文件。CPU 渲染驱动是
"主库 + 依赖库"的一整组，装不进去。

| 改动 | 说明 |
|---|---|
| 拷贝所有 `.so` / `.so.N` | 依赖库必须与主库同目录 |
| `meta.json.library` | 包可以声明自己的入口库名 |
| `pickEntry(libs, declared)` | 入口选择；未声明时回退 `libvulkan_freedreno*`，再回退任意 `libvulkan_*` |
| `libraryOf(dir)` | 判断"是否已安装"改为读 `icd.json` 的 `library_path`，回退历史固定名 |
| `apiVersion(declared)` | **ICD 的 `api_version` 不再写死 `1.1.274`**，从 `meta.json.apiVersion` 读 |

> 后两条是真机上踩出来的，见第 4 节。

### 2.4 软件会话的驱动选择（`SessionGpu.pickSoftwareDriver`）

装了两个软件驱动包时，原逻辑按**字母序**取第一个，用户在组件页选的被忽略。
改为：**用户的选择优先**，其次才是第一个 CPU 渲染器；用户选的若不是 CPU 渲染器（例如
Turnip）则忽略。

### 2.5 界面（`FrontEndSetup.kt` + 4 套 `strings.xml`）

- GPU 卡片：麒麟显示"只能软件渲染 / Maleoon 910 没有 Linux 驱动；会话将用 CPU 渲染，游戏会很慢"
- 顶部状态：正确显示芯片与"进程限制已关闭"
- 安装确认框：说明"此芯片没有 Linux GPU 驱动"，不再暗示可以玩

### 2.6 测试

```
app/src/test/java/com/droiddeck/launcher/gpu/
├── DriverPackEntryTest.kt          # 入口选择 / 安装判定 / ICD 版本
├── KirinSupportTierTest.kt         # 支持等级
└── probe/
    ├── FakeEnv.kt                  # 探测用假环境
    ├── GpuProbeTest.kt
    ├── BackendSelectionTest.kt
    ├── DeviceProfilesTest.kt
    ├── HarmonyOsTest.kt
    ├── KirinCatalogTest.kt
    ├── Kirin9010RealDeviceTest.kt  # 真机抓下来的属性快照
    └── SessionGpuPlanTest.kt
```

**187 个用例全部通过。**

---

## 3. 真机验证结果

| 层次 | 状态 | 证据 |
|---|---|---|
| 探测 / 后端选择 | ✅ | `SessionGpu: gpu: lavapipe [SAFE] · software (icd ...) · 60% · 60 Hz cap` |
| 分辨率缩放 | ✅ | `size=974x432`（由 1624×720 按 60% 缩放） |
| 驱动包导入（18 文件） | ✅ | `files/linux_vulkan_drivers/lavapipe_Mesa_26.2_/` 全部落地 |
| ICD 版本声明 | ✅ | 生成的清单为 `"api_version": "1.4.0"` |
| 用户选择的驱动生效 | ✅ | 会话日志 `icd=...lavapipe_Mesa_26.2_/icd.json` |
| lavapipe 加载 | ✅ | loader 无报错 |
| gamescope 接受设备 | ✅ | `selecting physical device 'llvmpipe (LLVM 23.1.3, 128 bits)'` |
| 第一帧渲染 | ❌ | 见第 5 节 |

---

## 4. 真机暴露的两个缺陷（值得单独提交）

### 4.1 ICD 的 `api_version` 被写死

```java
icdBody.put("api_version", "1.1.274");   // 改动前
```

对 Turnip 包成立，对 CPU 渲染驱动不成立：Mesa 26.2 的 lavapipe 是 **Vulkan 1.4** 驱动。
声明成 1.1 后，loader 按 1.1 对待，需要 1.3 的 gamescope 直接报"没有可用设备"——而驱动其实
已经加载、设备也能枚举。

修复后：

```json
{"ICD": {"library_path": ".../lavapipe_Mesa_26.2_/libvulkan_lvp.so", "api_version": "1.4.0"}}
```

### 4.2 "是否已安装"按固定文件名判断

`isInstalled()` 要求目录里存在 `libvulkan_freedreno.so`。导入成功的 CPU 渲染驱动因此**在
驱动列表里看不见**——文件在、清单在，就是不显示。

改为以 `icd.json` 的 `library_path` 为准，回退历史固定名。

> 这两个都属于"改了一处、漏了相邻一处"：导入路径改了，校验路径没改。单元测试里没有第二个
> 驱动包，也没有真实 loader 的版本协商，只有真机能发现。

---

## 5. 已知边界（重要）

### 5.1 麒麟没有 Linux GPU 驱动

Maleoon 910 没有可用的 Linux DRM/KGSL 驱动，华为只提供 Android 侧的闭源 blob
（`/vendor/lib64/hw/vulkan.hvgr_v200.so`，bionic 编译），在 glibc 环境里无法加载。

**所以 GPU 渲染在这个平台上不可能**，只能 CPU 渲染。

### 5.2 CPU 渲染卡在第一次显存分配

```
[gamescope] [Info]  vulkan: selecting physical device 'llvmpipe (LLVM 23.1.3, 128 bits)': queue family 0
[gamescope] [Info]  wlserver: [backend/headless/backend.c:60] Creating headless backend
[gamescope] [Error] vulkan: vkAllocateMemory failed (VkResult: -2)
gamescope: rendervulkan.cpp:3170: vulkan_create_flat_texture(...): Assertion `bRes' failed
```

分配的是 **974×432 的 B8G8R8A8 纹理，约 1.7 MB**，所以不是"要得太多"。

已排除的原因：

| 假设 | 检查 | 结论 |
|---|---|---|
| 分辨率太大 | 读 gamescope 源码确认分配尺寸 | 排除 |
| 内存不足 | RAM 15.2 GB / 可用 7.6 GB，上限均默认 | 排除 |
| `/dev/shm` 不可写 | `LinuxRuntime` 把它绑到 App 私有目录 | 排除 |
| `/proc` 未绑定 | `LinuxRuntime` 第 193 行有 `bind(cmd, "/proc")` | 排除 |
| 我加的调优变量 | 关掉 `LP_NUM_THREADS` / 着色器缓存后同样失败 | 排除 |
| 驱动包残留库 | 换干净的两库包后同样失败 | 排除 |

剩余最可能的原因：**lavapipe 在 proot 下做 dma-buf / 匿名文件分配时失败**。这是运行环境
层面的问题，App 侧改不动。要继续需要换 lavapipe 或 gamescope 版本，即重建运行时。

### 5.3 驱动版本必须与运行时对齐

运行时 `linuxfs r9` 自带 **Mesa 26.2 + loader 1.4.357 + libLLVM 22**，但**不带 lavapipe**。

实测可用的组合：

| 组件 | 版本 | 来源 |
|---|---|---|
| `libvulkan_lvp.so` | Mesa 26.2.5（`vulkan-swrast 1:26.2.5-1`） | Arch Linux ARM |
| `libLLVM.so.23.1` | `llvm-libs 23.1.4-1.1` | Arch Linux ARM |

> 注意：Arch 当前的 `vulkan-swrast` 是用 **LLVM 23** 编译的，而运行时只有 LLVM 22，所以
> LLVM 23.1 必须随包一起带。用 Debian bullseye 的 Mesa 20.3 不行——它只有 Vulkan 1.1。

---

## 6. 排障过程（简要）

从"能探测对"到"gamescope 用上 llvmpipe"，中间踩了 8 个坑。每个都是**先拿到精确报错、再改一处**
推进的，没有靠猜。

| # | 现象 | 根因 | 处理 |
|---|---|---|---|
| 1 | 麒麟被当成未知 GPU，走 Turnip | 非 Adreno 一律归到默认路径 | 加后端注册表，按厂商分流 |
| 2 | 计划算出来了，会话不执行 | 环境变量在 `addClientEnvironment` 里写死 | 计划只算一次，以参数传入 |
| 3 | 带依赖的驱动包装不进 | 导入器只认单文件 `libvulkan_freedreno*` | 拷贝全部 `.so`，支持 `meta.json.library` |
| 4 | 点"安装"进度条一闪而过 | `Cannot download DirectAudio`：诊断版 APK 没有 addon pack | 补上内容寻址的 `directaudio.tar.zst` |
| 5 | 清单拉取超时 | `raw.githubusercontent.com` 不通 | 下载器走镜像链（`gh-proxy.com` 可用） |
| 6 | `vkCreateInstance -9` | 缺 `libbsd.so.0`：只解析了直接依赖 | 改为**完整 ELF 依赖闭包** |
| 7 | `failed to find physical device` | Mesa 20.3 只有 Vulkan 1.1，gamescope 要 1.3 | 换 Arch ARM `vulkan-swrast 26.2.5` |
| 8 | `libLLVM.so.23.1` 缺失 | Arch 当前包用 LLVM 23 编译，运行时只有 22 | 补 `llvm-libs 23.1.4` |

第 6、7、8 条是连锁的：**每修好一层，下一层才暴露出来**。第 7 条最关键——不是打包错了，是
**选错了 Mesa 版本**。用运行时自己的发行版包，版本自然对得上。

修完第 8 条，gamescope 第一次接受了设备：

```
[gamescope] [Info] vulkan: selecting physical device 'llvmpipe (LLVM 23.1.3, 128 bits)': queue family 0
[gamescope] [Info] wlserver: [backend/headless/backend.c] Creating headless backend
```

然后停在第一次显存分配（见 5.2）。

另外第 4 条值得单独说：它暴露了**"跳过资源准备的构建装不上运行时"**这个陷阱，
第 8 节给了不重新编译的补法。

---

## 7. 如何应用

> 本分支已经应用了这些改动。这一节是给「应用到别的基线」用的。

### 方式 A：打补丁

```bash
cd /path/to/DroidDeck
git checkout -b kirin-gpu
git apply /path/to/droiddeck-kirin-gpu.patch
```

### 方式 B：直接覆盖

把 `files/` 下的目录结构整体拷到仓库根目录即可（路径与仓库一一对应）。

### ⚠️ 不要带上的文件

`gradle/wrapper/gradle-wrapper.properties` **没有**包含在补丁里——本地的 distributionUrl
指向离线镜像（`file:///...`），提交上去会破坏别人的构建。

---

## 8. 构建与测试

```bash
export JAVA_HOME=/path/to/jdk17
export GRADLE_USER_HOME=/path/to/gradle-home

./gradlew :app:testDebugUnitTest --tests "com.droiddeck.launcher.gpu.*"
./gradlew :app:assembleDebug
```

### 一个容易踩的坑

如果为了快用 `-PskipRust=true -x verifyRuntimeInputs` 构建，得到的 APK **装不上 Linux 运行时**：

```
E LinuxRuntimeInstaller: java.io.IOException: Cannot download DirectAudio
```

原因：`app/build.gradle` 从 `app/build/packs/<name>.tar.zst` 计算 SHA-256 写进 `BuildConfig`；
文件不存在则 SHA 置空，`AddonPack.available == false`，`await()` 直接失败。

补法（不必自己编译）：

1. 从 `Droid-Deck/DroidDeck-Components` 的 release 里找到内容寻址的包
   （tag 形如 `pack-directaudio-<sha 前 16 位>`），下载 `directaudio.tar.zst` 放到
   `app/build/packs/`
2. proot 不用管：`tools/packs/proot/release.env` 里已有 pin，源码构建会自动使用

---

## 9. 未解决的问题（Open questions）

这两条是明确的空白，写在这里是为了不让后来的人重复踩。**它们都不影响第 2 节的改动可用性**。

### Q1. lavapipe 为什么在 proot 里分配不出 1.7 MB？

`vkAllocateMemory` 返回 `VK_ERROR_OUT_OF_DEVICE_MEMORY`，但内存充足、分配极小。已排除六种原因
（见 5.2），剩余最可能的是 dma-buf / 匿名文件分配在 proot 下失败，但**没有验证到根因**。

想接着查的人需要一个能在 guest 里跑 `vulkaninfo` 的入口。运行时自带
`/usr/bin/vulkaninfo`，但会话脚本每次启动由 APK 重新下发，替换设备上的脚本会被覆盖；
把诊断分支加进 `assets/linuxfs/usr/local/bin/droiddeck-session` 也没生效（脚本另有下发来源）。
**下一步应该是先搞清脚本的实际下发路径**，再拿到 lavapipe 报告的 heap 大小。

判定标准：如果 heap 为 0，说明 `os_get_available_system_memory()` 在 guest 里读不到
`/proc/meminfo`，那是 App 侧能修的；如果 heap 正常，则是分配机制问题，需要换驱动或合成器版本。

### Q2. PanVK-kbase 的 CSF/JM 分流没有真机验证

`PanVkKbaseBackend` 按 Kbase 接口把 Mali 分成 CSF（可实验）和 JM（无可用驱动）。这个判断
来自公开资料和接口版本号，逻辑有单测覆盖，但**没有在任何 Mali 设备上跑过**。如果有人在
Mali 机器上试，最需要确认的是 `probe.kbase` 的判定是否准确。

---

## 10. 来源与许可

- 基于 [`Droid-Deck/DroidDeck`](https://github.com/Droid-Deck/DroidDeck)，遵循上游的 **GPL-3.0**（见仓库 `LICENSE`）
- 所有改动以补丁和完整文件两种形式提供，方便直接提 PR
- 驱动包脚本下载的是 Arch Linux ARM 的**未修改**官方包，包内 `meta.json` 写明了包名与版本

如果你要把它合回上游，建议拆成三个 PR：

1. 探测框架 + 后端注册表（新增文件，不碰既有逻辑）
2. 驱动包多文件支持 + `api_version` + `isInstalled`（独立 bug 修复，与麒麟无关）
3. 会话接线 + 界面文案（依赖前两个）

第 2 个最适合单独提——它修的是通用缺陷，不需要讨论"要不要支持麒麟"。

---

## 11. 目录结构

```
kirin/
├── README.md                        # 英文
├── README.zh-CN.md                  # 简体中文（本文件）
├── patch/
│   └── droiddeck-kirin-gpu.patch    # 同样内容的补丁（40 文件 / 27 新增）
└── scripts/
    └── build-lavapipe-pack.py       # 复现 CPU 渲染驱动包（未随仓库提交）

app/src/main/java/com/droiddeck/launcher/gpu/probe/   # 框架本体
docs/architecture/                                    # 框架设计 + 麒麟路线
```

`kirin-gpu` 分支已经应用了这些改动，所以这里不再重复放一份逐文件拷贝。补丁保留下来，
是为了能应用到别的基线，也方便把两半改动分开 review。

---

## 12. 设计取舍

**为什么用后端注册表而不是 if-else。** 加一个厂商只加一个 `RenderBackend` 实现 + 注册一行，
不改既有代码；而且每个后端可以独立单测。

**为什么探测只依赖 `ProbeEnv`。** 探测逻辑里全是"读属性、看文件在不在"，把这些抽成接口后
可以用 `FakeEnv` 在 JVM 里跑完整的麒麟场景，不需要真机。`Kirin9010RealDeviceTest` 用的就是
真机抓下来的属性快照。

**为什么 Adreno 要"逐字符不变"。** 那是已经验证过的路径。新增厂商支持不应该让老设备承担
回归风险，所以用一个布尔开关把旧行为完整保留，而不是"重构得更优雅"。
