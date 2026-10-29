# DroidDeck · Kirin 9010 (Maleoon 910) support branch

**English** | [简体中文](README.zh-CN.md)

A set of changes that make DroidDeck recognise a Kirin 9010 / Maleoon 910 instead of treating it as
an unknown Adreno, tell the user the truth about what a session can do on that chip, and import a
CPU Vulkan driver. GPU rendering is **not possible** on this hardware — no Linux driver exists — and
the software path is wired up and verified up to the point where gamescope initialises Vulkan.

Tested on: **Kirin 9010 / Maleoon 910 / HarmonyOS (Android 12, API 31) / 16 GB**

---

## 0. Who this is for — and who it is not

| What you want | This branch |
|---|---|
| Stop the launcher misreading the GPU on Kirin, stop sending users to install a runtime they cannot use | ✅ that is exactly what it does |
| Add support for a non-Adreno GPU (Mali / PowerVR / others) | ✅ the framework is built for this — one file |
| Fix general bugs like "an imported driver does not appear in the list" | ✅ see section 4 |
| Play 3D games on a Kirin device | ❌ the hardware has no Linux GPU driver |
| Play 2D games on a Kirin device | ❌ not yet either — see 5.2 |
| A drop-in patch | ❌ it changes behaviour and architecture, it does not add a feature |

If you only want the launcher not to crash on a new device, section 2.1 is the part that matters.
If you care whether a Kirin can play games, go straight to section 5.

---

## 1. What this branch fixes

Upstream treats every GPU that is not an Adreno as unknown and then **keeps going down the Turnip
path**. On a Kirin that shows up as four things being wrong at once:

| Symptom | Consequence |
|---|---|
| The probe classifies Maleoon as Adreno | It tries to load Turnip, which cannot work |
| The UI still says the device can play | The user installs the runtime first, then finds out |
| The process-limit check reports a problem | It is already disabled; the UI still asks for action |
| The importer only accepts a single-file Turnip pack | A CPU renderer, which is a library plus its dependencies, cannot be installed at all |

This branch fixes all four, and **changes no behaviour on the Adreno path**.

---

## 2. What changed

40 files, 27 of them new. Ordered so each step could be its own commit.

### 2.1 GPU probe framework (new `gpu/probe/`)

| File | Purpose |
|---|---|
| `ProbeEnv.kt` | Probing depends only on this interface (property reads + file existence), so it can be tested in the JVM with `FakeEnv` |
| `AndroidProbeEnv.kt` | The real implementation |
| `RenderBackend.kt` | One rendering backend: `id` / `appliesTo` / `probe` / `verdict` |
| `backends/TurnipBackend.kt` | Adreno with an imported Turnip |
| `backends/LavapipeBackend.kt` | CPU rendering (llvmpipe) |
| `backends/PanVkKbaseBackend.kt` | Mali with PanVK/Kbase |
| `backends/VendorBridgeBackend.kt` | Vendor driver passthrough |
| `BackendRegistry.kt` | Registry: **a new vendor is one implementation plus one line** |
| `GpuVendor.kt` / `GpuProbe.kt` | Vendor identification and probe result |
| `HarmonyOs.kt` | HarmonyOS detection (decides which paths are available) |
| `KirinCatalog.kt` | Kirin model catalogue (9010 → Maleoon 910) |
| `KbaseInterface.kt` | Mali Kbase interface version probe |
| `DeviceProfiles.kt` | Per-device profile: render scale, refresh cap, thread count |
| `SessionGpu.kt` | Turns a probe result into **a plan the session actually executes** (see 2.2) |

### 2.2 The session actually reads the plan (`SessionGpu.kt` + `SessionService.kt`)

`SessionGpu.plan()` is a pure function: probe result in, this out.

```kotlin
SessionGpuPlan(
    keepAdrenoDefaults: Boolean,   // true = output is byte-identical to before
    icdPath: String?,              // which Vulkan ICD the session uses
    libraryDir: String?,           // driver directory, goes on the guest LD_LIBRARY_PATH
    env: Map<String, String>,      // rendering environment
    width/height/refreshCap,       // output size and refresh cap
    missingSoftwareDriver: Boolean // a software driver still has to be imported
)
```

The key design: **when `keepAdrenoDefaults` is true, `SessionService` emits exactly the three env
lines it emitted before** (`MESA_LOADER_DRIVER_OVERRIDE=zink` and friends) and `env` is empty. Adding
vendor support therefore cannot disturb the Adreno path that already works.

How `SessionService` is wired:

- The plan is computed once and passed into `addClientEnvironment`, so size and environment come
  from the same decision
- Software sessions skip `BL_VK_DRIVER` (it would override the ICD we chose)
- Software sessions skip `GAME_TU_DEBUG` (that is Turnip's)
- `BL_WIDTH/HEIGHT` are scaled from the device profile and rounded to even values (a compositor
  upscale otherwise leaves a half-pixel seam), with a floor of 320

### 2.3 Driver packs: from "one file" to "a directory" (`LinuxVulkanDriverManager.java`)

Upstream only accepted a `.so` whose name started with `libvulkan_freedreno`, and copied exactly
**one** file. A CPU renderer is a main library plus the libraries it links against, so it could not
be installed at all.

| Change | Why |
|---|---|
| Copy every `.so` / `.so.N` | Dependencies have to sit next to the main library |
| `meta.json.library` | A pack can declare its own entry point |
| `pickEntry(libs, declared)` | Entry selection; falls back to `libvulkan_freedreno*`, then any `libvulkan_*` |
| `libraryOf(dir)` | "Is it installed" now reads `library_path` from `icd.json`, falling back to the historical fixed name |
| `apiVersion(declared)` | **`api_version` is no longer hard-coded to `1.1.274`**; it comes from `meta.json.apiVersion` |

> The last two were found on real hardware. See section 4.

### 2.4 Software driver selection (`SessionGpu.pickSoftwareDriver`)

With two software packs installed, upstream took the first one in **alphabetical order** and ignored
what the user picked on the Components screen. Now: **the user's choice wins**, then the first CPU
renderer; a choice that is not a CPU renderer (Turnip, say) is ignored for a software session.

### 2.5 UI (`FrontEndSetup.kt` + 4 `strings.xml`)

- GPU card: on Kirin it reads "software rendering only — Maleoon 910 has no Linux driver; the
  session will render on the CPU and games will be slow"
- Header: shows the chip correctly, and "process limit disabled"
- Install dialog: says this chip has no Linux GPU driver, instead of implying it might work

### 2.6 Tests

```
app/src/test/java/com/droiddeck/launcher/gpu/
├── DriverPackEntryTest.kt          # entry selection / install check / ICD version
├── KirinSupportTierTest.kt         # support tier
└── probe/
    ├── FakeEnv.kt                  # fake environment for probing
    ├── GpuProbeTest.kt
    ├── BackendSelectionTest.kt
    ├── DeviceProfilesTest.kt
    ├── HarmonyOsTest.kt
    ├── KirinCatalogTest.kt
    ├── Kirin9010RealDeviceTest.kt  # property snapshot captured from hardware
    └── SessionGpuPlanTest.kt
```

**187 tests, all passing.**

---

## 3. What was verified on hardware

| Layer | Status | Evidence |
|---|---|---|
| Probe / backend selection | ✅ | `SessionGpu: gpu: lavapipe [SAFE] · software (icd ...) · 60% · 60 Hz cap` |
| Resolution scaling | ✅ | `size=974x432` (60% of 1624×720) |
| Driver pack import (18 files) | ✅ | everything landed under `files/linux_vulkan_drivers/lavapipe_Mesa_26.2_/` |
| ICD version declared | ✅ | generated manifest reads `"api_version": "1.4.0"` |
| The chosen driver is used | ✅ | session log `icd=...lavapipe_Mesa_26.2_/icd.json` |
| lavapipe loads | ✅ | loader reports no error |
| gamescope accepts the device | ✅ | `selecting physical device 'llvmpipe (LLVM 23.1.3, 128 bits)'` |
| First frame rendered | ❌ | see section 5 |

---

## 4. Two defects only real hardware exposed (worth separate PRs)

### 4.1 `api_version` was hard-coded

```java
icdBody.put("api_version", "1.1.274");   // before
```

True for every Turnip pack, false for a CPU renderer: lavapipe in Mesa 26.2 is a **Vulkan 1.4**
driver. Declaring 1.1 makes the loader treat it as 1.1, so a compositor that requires 1.3 reports
"no physical device" — while the driver is loaded and its device is enumerable.

After the fix:

```json
{"ICD": {"library_path": ".../lavapipe_Mesa_26.2_/libvulkan_lvp.so", "api_version": "1.4.0"}}
```

### 4.2 "Installed" was decided by a fixed file name

`isInstalled()` required `libvulkan_freedreno.so` in the directory. A successfully imported CPU
renderer was therefore **invisible in the driver list** — files present, manifest present, not shown.

It now follows `library_path` from `icd.json`, falling back to the historical name.

> Both are the same class of mistake: the import path was changed and the check next to it was not.
> Unit tests have neither a second driver pack nor a real loader negotiating versions, so only
> hardware could surface them.

---

## 5. Known limits (important)

### 5.1 There is no Linux GPU driver for Kirin

Maleoon 910 has no usable Linux DRM/KGSL driver. Huawei ships only an Android-side closed blob
(`/vendor/lib64/hw/vulkan.hvgr_v200.so`, built against bionic), which cannot be loaded in a glibc
environment.

**GPU rendering is therefore impossible on this platform**; CPU rendering is the only path.

### 5.2 CPU rendering stops at the first memory allocation

```
[gamescope] [Info]  vulkan: selecting physical device 'llvmpipe (LLVM 23.1.3, 128 bits)': queue family 0
[gamescope] [Info]  wlserver: [backend/headless/backend.c:60] Creating headless backend
[gamescope] [Error] vulkan: vkAllocateMemory failed (VkResult: -2)
gamescope: rendervulkan.cpp:3170: vulkan_create_flat_texture(...): Assertion `bRes' failed
```

The allocation is a **974×432 B8G8R8A8 texture, about 1.7 MB**, so it is not asking for too much.

Ruled out:

| Hypothesis | Check | Result |
|---|---|---|
| Resolution too large | Read gamescope's source to confirm the allocation size | ruled out |
| Out of memory | 15.2 GB RAM / 7.6 GB available, both caps at default | ruled out |
| `/dev/shm` not writable | `LinuxRuntime` binds it to app-private storage | ruled out |
| `/proc` not bound | `LinuxRuntime` line 193 binds `/proc` | ruled out |
| Tuning variables I added | Same failure with `LP_NUM_THREADS` and the shader cache disabled | ruled out |
| Leftover libraries in the pack | Same failure with a clean two-library pack | ruled out |

The most likely remaining cause is **lavapipe failing to allocate a dma-buf / anonymous file under
proot**. That is an environment problem the app cannot fix; getting past it means changing the
lavapipe or gamescope build, i.e. rebuilding the runtime.

### 5.3 The driver must match the runtime

The `linuxfs r9` runtime ships **Mesa 26.2 + loader 1.4.357 + libLLVM 22**, but **no lavapipe**.

The combination that worked:

| Component | Version | Source |
|---|---|---|
| `libvulkan_lvp.so` | Mesa 26.2.5 (`vulkan-swrast 1:26.2.5-1`) | Arch Linux ARM |
| `libLLVM.so.23.1` | `llvm-libs 23.1.4-1.1` | Arch Linux ARM |

> Note: Arch's current `vulkan-swrast` is built against **LLVM 23** while the runtime carries
> LLVM 22, so LLVM 23.1 has to travel with the pack. Debian bullseye's Mesa 20.3 does not work — it
> only supports Vulkan 1.1.

---

## 6. How the debugging went (short version)

Eight walls between "the probe is right" and "gamescope uses llvmpipe". Each one was cleared by
**getting the exact error first, then changing one thing** — no guessing.

| # | Symptom | Root cause | Fix |
|---|---|---|---|
| 1 | Kirin classified as unknown, took the Turnip path | everything non-Adreno fell through to the default | added the backend registry, split by vendor |
| 2 | The plan was computed but the session ignored it | env was hard-coded inside `addClientEnvironment` | compute once, pass as a parameter |
| 3 | A pack with dependencies would not install | importer accepted one fixed file name | copy every `.so`, honour `meta.json.library` |
| 4 | Progress bar flashed and vanished | `Cannot download DirectAudio`: the diagnostic APK had no add-on packs | supplied the content-addressed `directaudio.tar.zst` |
| 5 | Catalog fetch timed out | `raw.githubusercontent.com` unreachable | downloader walks a mirror list (`gh-proxy.com` works) |
| 6 | `vkCreateInstance -9` | missing `libbsd.so.0` — only direct dependencies were resolved | switched to the **full ELF dependency closure** |
| 7 | `failed to find physical device` | Mesa 20.3 is Vulkan 1.1, gamescope needs 1.3 | switched to Arch ARM `vulkan-swrast 26.2.5` |
| 8 | `libLLVM.so.23.1` missing | Arch's build targets LLVM 23, the runtime has 22 | added `llvm-libs 23.1.4` |

Items 6, 7 and 8 are a chain: **each layer only became visible once the one before it was fixed**.
Item 7 is the important one — the packaging was not wrong, **the Mesa version was**. Using the
runtime's own distribution makes the versions line up by construction.

After item 8, gamescope accepted the device for the first time:

```
[gamescope] [Info] vulkan: selecting physical device 'llvmpipe (LLVM 23.1.3, 128 bits)': queue family 0
[gamescope] [Info] wlserver: [backend/headless/backend.c] Creating headless backend
```

and then stopped at the first memory allocation (see 5.2).

Item 4 deserves its own mention: it exposes the trap that **a build which skips runtime inputs cannot
install the runtime**. Section 8 has the fix that does not require rebuilding.

---

## 7. How to apply

> On this branch the changes are already applied. This section is for putting them on a
different base.

### Option A — apply the patch

```bash
cd /path/to/DroidDeck
git checkout -b kirin-gpu
git apply /path/to/droiddeck-kirin-gpu.patch
```

### Option B — copy the files

Copy the tree under `files/` onto the repository root; paths match one to one.

### ⚠️ One file deliberately excluded

`gradle/wrapper/gradle-wrapper.properties` is **not** in the patch — the local `distributionUrl`
points at an offline mirror (`file:///...`), which would break everyone else's build.

---

## 8. Building and testing

```bash
export JAVA_HOME=/path/to/jdk17
export GRADLE_USER_HOME=/path/to/gradle-home

./gradlew :app:testDebugUnitTest --tests "com.droiddeck.launcher.gpu.*"
./gradlew :app:assembleDebug
```

### A trap worth knowing

Building with `-PskipRust=true -x verifyRuntimeInputs` to save time produces an APK that **cannot
install the Linux runtime**:

```
E LinuxRuntimeInstaller: java.io.IOException: Cannot download DirectAudio
```

Why: `app/build.gradle` hashes `app/build/packs/<name>.tar.zst` into `BuildConfig`. If the file is
absent the hash is empty, `AddonPack.available` is false, and `await()` fails immediately.

Fix, without compiling anything:

1. Find the content-addressed release in `Droid-Deck/DroidDeck-Components`
   (tag looks like `pack-directaudio-<first 16 hex of the digest>`), download `directaudio.tar.zst`
   into `app/build/packs/`
2. proot needs no action: `tools/packs/proot/release.env` already pins it, and a source build uses
   that pin

---

## 9. Open questions

Two known gaps, recorded so nobody has to rediscover them. **Neither affects the changes in
section 2.**

### Q1. Why can lavapipe not allocate 1.7 MB under proot?

`vkAllocateMemory` returns `VK_ERROR_OUT_OF_DEVICE_MEMORY` while memory is plentiful and the
allocation is tiny. Six causes are ruled out (see 5.2); the most likely remainder is dma-buf /
anonymous-file allocation failing under proot, but **the root cause was not confirmed**.

Anyone continuing needs a way to run `vulkaninfo` inside the guest. The runtime ships
`/usr/bin/vulkaninfo`, but the session script is re-issued by the APK on every start, so replacing
it on the device gets overwritten; adding a diagnostic branch to
`assets/linuxfs/usr/local/bin/droiddeck-session` did not take effect either (the script has another
delivery path). **The next step is to find that delivery path**, then read the heap sizes lavapipe
reports.

The decision point: if the heap is 0, `os_get_available_system_memory()` cannot read
`/proc/meminfo` inside the guest, and that is fixable on the app side. If the heap is normal, it is
the allocation mechanism, and the fix is a different driver or compositor build.

### Q2. The PanVK-kbase CSF/JM split has never run on real hardware

`PanVkKbaseBackend` splits Mali by Kbase interface into CSF (experimental) and JM (no usable
driver). That judgement comes from public documentation and interface version numbers; the logic is
unit-tested but **has never run on a Mali device**. If you try it on one, the thing to check first is
whether `probe.kbase` classifies correctly.

---

## 10. Origin and licence

- Based on [`Droid-Deck/DroidDeck`](https://github.com/Droid-Deck/DroidDeck), under the upstream
  **GPL-3.0** (see `LICENSE`)
- Changes are provided both as a patch and as complete files, so either route to a PR works
- The pack script downloads **unmodified** Arch Linux ARM packages; `meta.json` records the package
  names and versions

If you want to merge this upstream, three PRs is the natural split:

1. Probe framework + backend registry (new files, touches no existing logic)
2. Multi-file driver packs + `api_version` + `isInstalled` (independent bug fixes, nothing to do
   with Kirin)
3. Session wiring + UI text (depends on the first two)

The second is the easiest to land on its own — it fixes general defects and needs no discussion
about whether to support Kirin.

---

## 11. Layout

```
kirin/
├── README.md                        # English (this file)
├── README.zh-CN.md                  # Simplified Chinese
├── patch/
│   └── droiddeck-kirin-gpu.patch    # the same work as a patch (40 files / 27 new)
└── scripts/
    └── build-lavapipe-pack.py       # reproduces the CPU renderer pack (not committed)

app/src/main/java/com/droiddeck/launcher/gpu/probe/   # the framework
docs/architecture/                                    # framework design + Kirin roadmap
```

The `kirin-gpu` branch already has these changes applied, so the per-file copies are
not duplicated here. The patch is kept so the same work can be applied to a different
base, and so the two halves of the change can be reviewed separately.

---

## 12. Design trade-offs

**Why a backend registry instead of if-else.** A new vendor is one `RenderBackend` implementation
plus one registration line, touching no existing code — and each backend can be unit-tested on its
own.

**Why probing depends only on `ProbeEnv`.** Probing is entirely "read a property, check whether a
file exists". Behind an interface, a complete Kirin scenario runs in the JVM through `FakeEnv` with
no hardware. `Kirin9010RealDeviceTest` replays a property snapshot captured from a real device.

**Why Adreno has to stay byte-identical.** That path is already verified. Adding vendor support
should not put existing devices at regression risk, so a boolean keeps the old behaviour intact
rather than "refactoring it more elegantly".
