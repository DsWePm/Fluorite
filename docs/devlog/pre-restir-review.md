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

1. **#41 可见性网格关档的修法**：照抄云影范本（关档仍付 131k 光线烘焙）vs 把水介质与雾开关解耦（水需要自有可见性来源）。见 A1。
2. `DEVELOPMENT.md` 原有四项：M17 体积 MIS 与默认档 · M18 S3 死工作 · M20.3 粒子 mask 成本 · M11 切片②天空遮蔽近似。本轮未触碰。
3. 降水粒子密度是否拆成雨/雪两个旋钮（上轮遗留）。

## E. 第二轮范围（本轮未覆盖）

1. **shader 注释逐行核查**（19,790 行）——过期密度预计最高，需独立一轮。
2. **172 处 LOGGER 逐条分级**（启动一次性 / 热路径 / 已完成使命）。
3. **59 个 boolean 开关逐个走关档**（本轮只按「共享烘焙」形状筛过）。
4. **§8.11 测量欠账**——需要用户在游戏内跑，与本轮静态清点互不阻塞。

---

## 本轮直接产出

- [ ] 修 `lutSampler` 泄漏（A2，无需请示）
- [ ] 修正 `DEVELOPMENT.md:67`、`CLAUDE.md:21`（A3-1、A3-2）
- [ ] 给 view 26/27 具名常量（A3-3）
- [ ] 带回 `b5a558f` 的任务书段落（C）
