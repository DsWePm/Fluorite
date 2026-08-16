# 进入 ReSTIR 前的全项目 review

**目的**：在换渲染架构之前把账结清 —— 修掉能由证据确认的 bug，删掉已经完成使命的诊断，纠正说谎的注释和文档。对应 `DEVELOPMENT.md` §8.8。

**约束（来自 `b5a558f`，本文件把它带进 main）**：
> 不得仅因探针沉默就宣称 bug 已消失（F19/F23/F25）。

这条对下面 B 节的每一个「删/留」裁决都有效。一个探针没有报警，只说明它没有在被观察的那些帧里命中，不说明它守的故障不存在了。

---

## 0. 本轮的范围与边界

**盘子**：Java 136 文件 / 43,450 行，shader 74 文件 / 19,790 行。

**测试基线**（本轮开工前，`review/pre-restir-inventory` @ `0720332`）：

```
:fabric:test --rerun  →  154 tests, 34 classes, 0 failures, 0 errors, 0 skipped
```

注：`:common:test` 不是有效任务名 —— `common` 不是 gradle 子项目，是被 `:fabric` / `:neoforge` 共享的源码集。测试跑在 `:fabric:test`，且**默认 UP-TO-DATE**，必须加 `--rerun` 才真的执行。第一次跑的时候我漏了这点，`| tail` 又把退出码吞了，得到一个假的「绿」。记在这里免得下次再犯。

**这一轮做了什么**：§8.8 点名的条目 + 可机械枚举/可机械验证的面（关闭档耦合、诊断面、悬空引用、采样器生命周期）。

**这一轮没做什么**：63k 行的逐行通读。shader 注释密度极高，过期注释的密度大概率也最高，但那需要独立一轮（见 §E）。所以下面的「已核查为干净」只对**列出的那些检查**成立，不是对全仓库成立。

---

## A. 静态清点

### A1. 关闭档违规（铁律 8）——本轮最重要的发现

铁律 8 是「每个开关的关档必须等于已发布行为」。真正会破这条的不是「开关没接上」，而是**一个开关掐掉了另一个特性也在消费的烘焙**。按这个形状筛，找到一处违规，以及一处同文件内的正确范本。

#### ✗ 违规：体积雾开关会改变水的外观

链路：

| 位置 | 行为 |
|---|---|
| `RtComposite.java:211` | `visibilityGridOrigin()`：`ENABLED` 为假 → 返回 `w = 0` |
| `RtComposite.java:3055` | 烘焙 dispatch 被同一条件掐掉 |
| `volume_visibility.slang:89` | `cell <= 0.0` → 返回 `(1, 1)`「完全受光」 |
| 消费者 | **水介质读的是同一个 `sampleVolumeLightVisibility`** |

于是：关掉体积雾 → 洞穴里的水变亮。一个雾开关改了水的外观，A/B 因此无意义。这就是 #41 的已知残留，`volume_visibility.slang:72-75` 已经写下了这件事，本轮把它的**完整链路和坐标**钉死。

#### ✓ 正确范本：云影烘焙（同一个文件，`RtComposite.java:3021`）

云影 bake 是**无条件 dispatch** 的，注释把理由写得很清楚：

> bake 测的是同一个 bit 30，关档时写入晴空 —— 所以这张图**永远**是一个合法的透射率，没有任何消费者需要问它能不能信。在这里 gating 只省下一个「只存常量」的 dispatch，却要让每一个未来的读者都正确地复现一遍这个条件。

**两者的差别就是修复方向**：云影的关档发布**有效的中性数据**，可见性网格的关档**什么都不发布、让消费者自己编一个 1.0**。#41 的修法应当是让可见性网格照抄云影的形状，而不是在消费侧再加一个特例分支。

> **待裁决**：这个修法会让 `ENABLED=false` 时仍然付一次 131k 光线的烘焙（`visibilityGridOrigin` 的注释明确记过，这正是当初加 gating 要省掉的东西）。可选的第三条路是把水介质的可见性查询与雾开关解耦，代价是水要有自己的可见性来源。**动工前请示。**

其余 59 个 boolean 开关未逐个走完，但按「是否掐掉共享烘焙」这个形状筛过一遍，只有可见性网格命中。

### A2. 能确认的 bug

#### `RtComposite.lutSampler` 泄漏（§8.8 点名项，已确认）

`lutSampler` 在 `RtComposite.java:3785` 懒创建，**在整个文件里没有任何 `vkDestroySampler`**。同一个清理块（`:3687`–`:3699`）依次销毁了 `atlasSampler`、`tilingSampler`、`environmentSampler`、`environmentTransferSampler`、`highCloudPatchSampler` —— 五个兄弟采样器都有，就它没有。

它被绑在 9 个描述符槽上（transmittance / multiScatter / skyView / aerialPerspective / visibilityGrid / waterSimHeight / rainExposureDepth / cloudShadow）。每次设备重建泄漏一个 sampler 句柄。

修法直白：在那个清理块里补一段同形状的。**这是本轮唯一一个可以直接改、不需要请示的**（无方向性选择、无性能取舍、无物理近似）。

### A3. 过期文档与注释

| # | 位置 | 问题 |
|---|---|---|
| 1 | `docs/DEVELOPMENT.md:67` | §1.4 仍把「云向地面/水面投影阴影，以及焦散读取二维云太阳透射率图」列为**未完成**。同文件 `:44` 和 §8.10 都记 D176 已落地。自相矛盾。 |
| 2 | `CLAUDE.md:21` | 写「debug view 8–25」，实际到 **27**（26 = 降雨材质/胶片/曝光，27 = 水洼存量/汇水/结果）。 |
| 3 | `world.rgen.slang` / `world_primary.rgen.slang` | view 26/27 是**裸数字字面量**，8–25 全部有 `DEBUG_VIEW_*` 具名常量。同一个数字在两个文件里各写一遍，改一个忘一个不会有任何报错。 |

### A4. 已核查为干净

- **悬空 shader 文件引用：0 处**。把全仓库注释里出现的每一个 `*.slang` 文件名抽出来对 `shaders/` 逐一验存，全部命中（两个「missing」是正则把 `world.rgen.slang` 切成 `rgen.slang` 的产物，非真实引用）。
- **契约测试全绿**：154 / 34 类 / 0 失败。ABI 的钉子（`RtPathSegmentLayoutTest`、`RtMaterialLayoutTest`、`RtSkyMediumLayoutTest` 等）都还在守着。
- 两个隔离开关都在，注释完整、理由清楚：`water.scatter-source`（`FluoriteConfig:2102`）与 `volumetrics.segment-source`（`:1056`）。两者都只静音 in-scatter、不动 extinction，把「太亮」和「太不透明」分开 —— 这个设计是对的，保留。

---

## B. 诊断盘点

**规模**：28 个 debug view（0–27）· 2 个隔离开关 · 172 处 LOGGER（38 文件）· 25 个 frame-stats 阶段 · 15 个 GPU 计时区 · 诊断 UI 只暴露 3 项。

### B1. debug view 全表

| # | 标签 | 归属 pass | 常量 |
|---|---|---|---|
| 0 | Off | — | — |
| 1–7 | Normals / Albedo / Depth / Roughness / Motion / Specular / Specular Motion | A（guide buffer） | 裸数字（`guides.slang:284+`） |
| 8–10 | Volume In-Scatter / Segment / Sun Visibility | B | ✓ |
| 11 | Water Caustic | B | ✓ |
| 12–15 | Transmittance / Multi-scatter / Sky-view LUT / Sky-view vs march | B | ✓ |
| 16 | Celestial light dye | B | ✓ |
| 17 | Aerial perspective froxel | B | ✓ |
| 18–19 | Volume visibility grid / Visibility profile (grid vs ray) | B | ✓ |
| 20–21 | Camera-prefix in-scatter / Composite prefix A/B | B | ✓ |
| 22 | Cloud chain probe | B | ✓ |
| 23–24 | Water simulation field / Water sim reach | B | ✓ |
| 25 | Fog density stages | B | ✓ |
| 26–27 | Rain material/film/exposure · Puddle storage/basin/result | **A** | ✗ 裸数字 |

全部 view 都在 `debugView != 0` 之后，关档零成本。**本轮不删任何一个** —— 18/19（可见性网格 vs 光线）和 25（雾密度阶段）正是 #41 未结部分要用的，23/24 是 #20 的，22 是 M11 测量欠账的。

### B2. 探针 vs 故障检测器 —— 这两个不是一回事，裁决也不同

本轮把水介质诊断拆成两档，之前它们混在一起谈：

| | `logWaterMediumContradiction` | `logWaterMediumProbe` |
|---|---|---|
| 位置 | `RtComposite.java:957` | `:1546` |
| 触发 | **每帧无条件** | `WATER_MEDIUM_TRACE` 开才跑 |
| 成本 | 几次 float 比较，无回读 | **GPU buffer 回读**（`invalidate` + map） |
| 性质 | 故障检测器（只在矛盾时说话） | 探针（持续采样） |
| 裁决 | **保留** | **保留**，但见下 |

`logWaterMediumContradiction` 常驻是对的：它有 300 帧退避、静默时零日志、成本可忽略，而它守的五个矛盾里有四个是历史上真出过的（F22/F26）。

`logWaterMediumProbe` 的 **`simPlane` lane 作为假设检验已经用完了** —— 它测出 `simPlane − surfaceY = 0.222`，99 个样本**零方差**。一个恒定的差值不可能产生间歇性症状，所以「两个水面不一致导致过亮」这个假设被排除了。

**但探针整体不删**，理由正是本文开头那条：#20 未结，探针仍然覆盖真实着色路径。假设被排除 ≠ 故障被修好。`simPlane` 那一路的**结论**已经记进 issue #20，lane 本身留着不额外要钱（关档不跑）。

### B3. 日志面

172 处 LOGGER 里 45 处在 `VulkanDiagnostics`、15 处在 `RtDeviceBringup` —— 即 35% 集中在设备启动/诊断这两个文件，属于正常的一次性启动日志，不是热路径。逐条审计留给第二轮（§E）。

---

## C. 仓库卫生

- **孤儿 commit `b5a558f`**（`codex/pre-restir-review-notes`）是本轮 review 的**任务书本身**，不在 main。本 PR 一并带回。同分支另外 3 个 commit 按 subject 已在 main。
- **过期 worktree**：`.claude/worktrees/suspicious-blackburn-276b7e`，detached @ `3325b57`（PR #11，M10 粒子时代）。可清理。
- 13 个本地分支，其中 8 个上游标 `[gone]`（已合并、远端已删）。`codex/water-surface-diagnostic` 的有用 commit `4871f0a` 已 cherry-pick 进 main。

---

## D. 待裁决清单（给用户）

1. ~~**#41 可见性网格关档的修法**~~ —— **已裁决并落地（D179）**。两个候选都没被采纳：真正的问题不是「关档该发布什么」，而是**这个开关不该归雾管**。`VISIBILITY_CELL_SIZE` 本来就是网格自己的关闭档，把 `Volumetrics.ENABLED` 从发布与烘焙条件里移除即可，不需要新可见性来源、也不需要无条件烘焙的特例。见 A1 与 [D179](M13-fog-weather.md)。
2. `DEVELOPMENT.md` 原有四项：M17 体积 MIS 与默认档 · M18 S3 死工作 · M20.3 粒子 mask 成本 · M11 切片②天空遮蔽近似。本轮未触碰。
3. 降水粒子密度是否拆成雨/雪两个旋钮（上轮遗留）。

## E. 第二轮范围（本轮未覆盖）

1. **shader 注释逐行核查**（19,790 行）——过期密度预计最高，需独立一轮。
2. **172 处 LOGGER 逐条分级**（启动一次性 / 热路径 / 已完成使命）。
3. **59 个 boolean 开关逐个走关档**（本轮只按「共享烘焙」形状筛过）。
4. **§8.11 测量欠账**——需要用户在游戏内跑，与本轮静态清点互不阻塞。

---

## 本轮直接产出

- [x] 修 `lutSampler` 泄漏（A2）—— PR #51。配了 `RtSamplerLifetimeTest`，查的是**整个类**而不是这一个句柄：任何 `private long *Sampler` 缺 `vkDestroySampler` 就构建失败。
- [x] 修正 `DEVELOPMENT.md:67`、`CLAUDE.md:21`（A3-1、A3-2）—— PR #51。
- [x] 给 view 26/27 具名常量（A3-3）—— PR #51。
- [x] 带回 `b5a558f` 的任务书段落（C）—— PR #49。
- [x] 修 #41 关档耦合（A1）—— PR #52，见上文待裁决 1 与 [D179](M13-fog-weather.md)。

### 清单之外冒出来的

**debug view 27 一直是坏的。** 具名化 26/27 的时候才发现：pass A 画两张降雨图，pass B 只对 26 让路。pass B 的分发以 `>= DEBUG_VIEW_SKY_TRANSMITTANCE` 开头、27 满足，而没有任何三元支认领 27 —— 于是落到默认的 `froxelDebug`，**pass B 每帧把 froxel 覆写在 pass A 的水洼图上**。这个 view 自诞生起就错，而它显示的是一张真实、合理的图，只是**另一个诊断**，所以盯着看永远看不出来。

这条值得记在方法论上：**两个裸字面量分居两个无法互相 import 的文件，中间没有任何东西约束它们一致。** 具名化本身只是整洁，但它让不对称第一次可见了。已配契约测试钉住两 stage 一致并禁止退回裸字面量。

---

# 第二轮：重复、耦合与边界条件

第一轮查的是「说谎的东西」（过期文档、错的关档、能确认的 bug）。第二轮按用户要求查**代码本身的性质**：耦合过高、违反编程原则、重复、以及没考虑边界条件的地方。

## 先说核查为干净的

列在前面，是为了让第三轮不必重做这些。**每一条只对列出的检查成立**，不是对全仓库的泛泛断言。

| 检查 | 方法 | 结果 |
|---|---|---|
| shader 数学定义域 | 抽出全部 `sqrt`/`log`/`acos`/`asin`，逐个看守卫 | **全部有守卫**。`fresnelDielectric` 的 `sinT2 >= 1.0` 提前返回、`raySphere` 的 `disc < 0.0`、`volumeExtinction` 的 `clamp(…, 1e-3, 1)`、`skyViewLutUv` 的 `clamp(dot, -1, 1)` |
| shader 注释机械新鲜度 | 抽出注释里 550 个标识符候选，对代码验存 | **0 处悬空**（两个「未命中」是散文里的大写强调词 `REBASE space`、`colour ATTACHMENT`） |
| 烘焙关档 | 枚举全部 `record*Bake`，逐个查 gate 与消费者 | **D179 之后全部正确**。可见性网格是唯一一处，已修 |
| 水仿真关档 | 查 `waterSimHeightTex` 的全部读点 | **正确**：`waterSimSample` 在域退化时返回 false，消费者返回 0 位移 —— 关档 = 已发布行为 |
| 日志限流 | 逐个查每帧路径上的 `LOGGER.*` 有无节流 | **正确**。水色日志是量化 + 1 秒间隔，且代码里留着一条注释记录**已修复的**每帧日志问题（「一个在被测量时才消耗渲染线程时间的仪器，比没有仪器更糟」） |

**注意**：注释的「机械新鲜度」不等于语义新鲜度。注释可以提到全部真实存在的符号，却在描述已经变了的行为。后者无法机械检测，本轮没做。

## 重复

### R1. SPIR-V 载入器复制了 13 份

`SHADER_DIR` 常量 13 处、`vkDestroyShaderModule` 14 处、整段「读资源 → 建 shader module」逐字复制 13 份，**只有局部变量名不同**（`info`/`smci`、`out`/`pModule`）。

涉及：`RtOverlayPipelines`、`RtAces2Luts`、`RtBloomFlarePipeline`、`RtCreativeGradingLut`、`RtDepthOfFieldPipeline`、`RtDisplayPipeline`、`RtExposurePipeline`、`RtFilmGrainNoise`、`RtHdrCompositePipeline`、`RtLensPipeline`、`RtPipeline`、`RtSdrPresentPipeline`、`RtSky`。

### R2. `check()` 的弱重实现 × 3 —— **这一条有行为后果**

`RtContext.check` 在 `VK_ERROR_DEVICE_LOST` 时会调 `VulkanDiagnostics.reportDeviceLost(instance.device, what)`。以下三处各自重新实现了一个**只抛异常、不取证**的版本：

- `rt/material/RtMaterialPageTexture.java:150`
- `rt/sky/RtEnvironmentTextures.java:341`
- `rt/sky/RtHighCloudTextures.java:214`

三处**全在纹理上传路径上**，也就是设备丢失最可能发生的地方。而这个项目为设备丢失专门养了一个 `VulkanDiagnostics`（45 处日志）。13 个 pipeline 类反而都正确地用了 `RtContext.check`。

这不是风格问题：**它在最需要取证的路径上把取证关掉了。**

### R3. sampler 销毁模板 × 11 —— 这个重复**就是** `lutSampler` 泄漏的机制

`RtComposite` 里 `RtContext ctx = RtContext.currentOrNull();` 出现 11 次，每次都是同一个五行模板。第一轮修的 `lutSampler` 泄漏，根因不是「有人粗心」，而是**这段模板必须被手写第七遍而没有任何东西会提醒**。`RtSamplerLifetimeTest` 是补的守卫，不是根治。

### R4. 水的天空闸点 × 3

`gateP = float3(p.x, p.y + skyDepth + 0.5, p.z)` 出现在三处：`volume.slang:1067`、`volume.slang:1318`、`world.rgen.slang:1366`，各自带一段解释 `+ 0.5` 为什么是「水面上方一掌宽」的近似重复注释。

这正是 #41 刚碰过的表达式。三份拷贝就是三次发散机会，**而发散是静默的** —— 每一份单独看都成立。

### R5. 测试的「找仓库根」helper × 12

12 个测试文件各自复制了同一个 `while (root != null && !Files.isRegularFile(root.resolve("settings.gradle")))` 循环（我第一轮加的 `RtSamplerLifetimeTest` 是第 13 份，一并算账）。

### R6. epsilon 无共同词汇

约 250 个 epsilon 字面量，具名常量近乎为零。`1.0e-3` 与 `0.001` 各出现约 30 次 —— **同一个值两种写法**。

**但不建议统一**：epsilon 在不同语境含义不同（分母守卫 / 距离阈值 / 收敛判据），一刀切会把无关的量绑在一起。值得做的只有「同值不同写法」那一类。

## 耦合

### C1. `RtComposite` 读取配置 245 次

编排器直接认识系统里几乎每一个旋钮。这是 4,344 行那个体量的直接来源，也是第一轮三个发现全部落在这一个文件里的原因。

拆分已裁决放在 ReSTIR 之后（见 §2.0），此处只记账。

### C2.「开关掐掉共享机器」已发生两次

- 水面涟漪求解器曾嵌在 `VISIBILITY_CELL_SIZE > 0 && Volumetrics.ENABLED` 内，关雾静默停掉涟漪
- 可见性网格被雾开关掐掉，关雾点亮洞穴水（#41 / D179）

本轮枚举全部烘焙确认**没有第三处**。但两次同形状说明这不是巧合，而是「gate 写在调用点、与被 gate 的东西的所有权无关」这个结构的产物。

## 边界条件

Java 侧扫了整数除法、未检查的 `.get(0)`、以及除以变量的位置。**绝大多数已守卫，而且守得讲究** —— 例如 `RtDynamicLightAccumulator.finish` 用 `!(effectiveArea > AREA_EPS) || !(centreWeight > 0.0)`，这种否定形式能正确处理 NaN，是刻意写法。

两处**契约在调用方、不是防御式**：

- `RtEntities.particleCenter`：`cx / vc` 无守卫，`vc == 0` 会得到 NaN 质心。调用方在 `if (vertAfter == vertBefore) continue;` 挡住了，所以当前不可达。
- `RtEntities.fitYawTransform`：`1f / vc` 同样依赖调用方保证 `vc > 0`。

不是 bug，但两处都是「删掉调用方一行 if 就变成 NaN 且不报错」的形状。

## 优先级建议

| | 项 | 理由 |
|---|---|---|
| **高** | R2 `check()` 弱拷贝 | 唯一一条**有行为后果**的：在最需要取证的路径上关掉了取证。三个文件，改动小 |
| 中 | R4 水天空闸点 | 在刚修过的代码里，发散静默 |
| 中 | R1 SPIR-V 载入器 | 13 份，纯样板，抽取零风险 |
| 中 | R3 sampler 模板 | 已有测试兜底，但根因未除 |
| 低 | R5 测试 helper | 只影响测试 |
| 低 | R6 同值不同写法 | 纯一致性 |
| 缓 | C1 拆 `RtComposite` | 已裁决在 ReSTIR 之后 |
