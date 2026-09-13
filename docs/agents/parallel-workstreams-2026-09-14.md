# 三路并行工作流分派（2026-09-14）

> 本文是**任务分派与边界文档**，不是事实源。架构、铁律、待办的唯一权威仍是
> [`docs/DEVELOPMENT.md`](../DEVELOPMENT.md)；历史证据在 [`docs/devlog/`](../devlog/)。
> 三条线结案后：过程与数据归档进各自 devlog，主文档按 §9.2 更新，本文随之作废（只作考古）。
>
> 快照基线：分支 `feat/m28-restir-backbone`，HEAD `7a7d50f`（S3b）。三条工作流都从该提交拉分支。

## 0. 新 agent 怎么用这份文档

1. 读 §1 进度快照和 §2 全体必读（约半小时），建立架构观。
2. 到 §4/§5/§6 找到分派给自己的那条线：读它的「必读」清单，确认「文件所有权」。
3. 严格遵守 §3 的边界与协调协议。**不在所有权白名单里的文件一行都不要改**；需要别线的东西就在自己的 devlog 号段里记档，不要跨界。

方向性决策（物理口径、视觉取向、默认值变更、估计量 re-scope）必须按铁律 1 请示用户：列选项 + 物理差距 + 性能代价。游戏内验收一律**交给用户启动客户端**（构建就绪 + 观察点清单即止，见 §3.6）。

## 1. 进度快照（2026-09-14）

### M28 统一 ReSTIR 骨架（当前主线，[`DEVELOPMENT.md` §8.13](../DEVELOPMENT.md)）

| 切片 | 状态 |
| --- | --- |
| S1 方向性天空场 + 出格钳制 | ✅ 关闭（D209–D212，游戏内验收通过） |
| S1.5 第二级粗网格 | 落地并消除移动闪烁（D213–D215）；**山体旁暗区未解决，挂 [Issue #77](https://github.com/DsWePm/Fluorite/issues/77)**，真值探针设计留在 D215，**本轮不分派**（2026-09-14 让位给 M29，重启时照 D215 做） |
| S2 路径 reservoir + 时域合并 | ✅ 机制 GO（D216–D221）；单样本时域合并在 1spp+RR 下无赢面，`PATH_RESERVOIR_APPLY_MIN_M = 2` 门控应用，视觉收益移交 S3 |
| S3 空间复用 + 决策6 四项技术 | S3a 空间复用落地（D222）；S3b 足迹阈值 + 分级接受落地（D223，`7a7d50f`），**待用户游戏内复验**（s-usable 抬升 / applied > 0 / 静止噪点变细）。剩余三项见工作流 A |
| S4 表面 DI 迁移、M24 退役 | 串行在 S3 验收通过之后，本轮不分派 |
| S5 太阳/天体入池 | 串行在 S4 之后，本轮不分派 |
| S6 雾照明接统一链 + D210 水拆段 | 串行在 S5 之后，本轮不分派 |

**为什么 S4–S6 不并行**：它们依次消费前一切片的产物，且全部落在同两份热点文件（`world.rgen.slang`、`RtComposite.java`）的同一区域。强行并行 = 合并地狱 + 归因污染（D211 的教训：捕获必须自带归因）。

### 主线之外的独立欠账与新增任务（与 ReSTIR 主干文件不相交）

- **Issue #77**：远场可见性暗区，机制未明，下一步探针已在 D215 设计好。**本轮不分派**——2026-09-14 用户指令把 B 线改派给第一人称手部 RT 光照（M29）；重启 #77 时第一件事仍是 D215 的真值对照探针，设计原样有效。
- **[Issue #20](https://github.com/DsWePm/Fluorite/issues/20)**：水面消失/水下曝光闪烁，机制已定位（F29：两个水面把相机夹进 2/9 格歧义带），检测器判据错误导致沉默。**本轮不分派**——2026-09-14 用户指令把 C 线改派给材质视差（M30）；重启时照 F29 的「跨骑判据 + 拿到 fault 行就停」原样做。
- **[Issue #40](https://github.com/DsWePm/Fluorite/issues/40)**：近相机降水粒子拉出斜线，机制已知（守卫只收缩长度不弃实例）。**本轮不分派**，同上；修复方向仍按 §8.1 记档的「最小距离硬弃实例」。
- **M29 第一人称手部 RT 光照（2026-09-14 用户指令）**：vanilla 光栅的手（手臂 + 手持物）改为接受 RT 场景的正确光照与 PBR 材质 → 工作流 B。
- **M30 材质视差 + 雨水坑联动（2026-09-14 用户指令）**：LabPBR 高度通道驱动的视差，且雨水坑吃视差高度——低凹处先淹、水位随雨情涨落，仿佛雨水淹没了视差里的低洼 → 工作流 C。

三条工作流按**子系统**切分：A = ReSTIR 主干（restir/reservoir/rgen 的 restir 段），B = 前端合成与手部（mixin 重定向、overlay 光栅、实体捕获、天空场消费），C = 材质视差与雨天表面（rchit 材质取用、rain_surface、material 管线）。`world.rgen.slang` A/C 各占一段（restir 段 / rain 求值段），其余文件交集只剩 `RtComposite.java` 的分区规则与 `FluoriteConfig.java` 的键前缀。

## 2. 全体必读（按序）

1. [`CLAUDE.md`](../../CLAUDE.md) —— 入口、铁律摘要、工具速查。
2. [`docs/DEVELOPMENT.md`](../DEVELOPMENT.md)：
   - §2 架构地图（一帧如何流动、shader/Java 文件职责、ABI 锚点、§2.8 偏差账）——**必读**；
   - §3 铁律全文；§4 方法论（诊断顺序、铁律 7 性能测量）；
   - §5.1/5.2 构建、运行与基准；§5.3 debug views；§8.1 未结问题；§8.13 M28 计划与已批决策。
3. [`docs/devlog/M28-restir-backbone.md`](../devlog/M28-restir-backbone.md) D209–D223 —— 全体读 D211/D218/D220（注册表三课）与 D219/D221/D222/D223（估计量现状）；各自再精读自己线相关条目。
4. [`docs/devlog/README.md`](../devlog/README.md) —— M*/D*/F*/R* 编号定位表。
5. [`docs/agents/domain.md`](domain.md)、[`docs/agents/issue-tracker.md`](issue-tracker.md)。
6. 仅工作流 A：[`Papers/M28-restir-backbone-survey.md`](../../Papers/M28-restir-backbone-survey.md) 全文（论文提取文本在 `Papers/_txt/`）。B/C 只需 §0 决策快照。

### 全体遵守的铁律与近期教训（摘录，全文见 DEVELOPMENT.md §3）

- 铁律 1：方向性决策先请示。铁律 2：roughness 线性 = GGX alpha，永不平方。铁律 3：`PackedPathSegment` 48 B 钉死，动 ABI 前读对应 layout test。铁律 7：性能结论只认 GPU 时间戳 + 同会话比值法（A→B→A，`frame.csv`）。铁律 8：每个开关的关档 = 已发布行为，逐位等价审的是字面量级别。
- **并行期附加冻结令**（§3.2）：所有共享 ABI（`WorldPush`、`PackedPathSegment`、`PackedPathReservoir`、push-constant 块）布局冻结，layout test 的期望值不得改动。
- D211/D218/D220 三课：**位、名字、步长——跨语言边界的每一份手抄都要有机器检查**。新增 `RtFrameStats` stage/counter 名必须先进注册表数组（否则统计开启首帧运行时抛、composite 回退原版）；`RtFrameStatsNameRegistryTest` 会在 `gradlew test` 抓住遗漏。
- F29 之课：探针沉默 ≠ 故障消失，先检查判据本身（工作流 C 的出发点）。

## 3. 并行边界与协调协议

### 3.1 文件所有权矩阵

| 文件/区域 | A（ReSTIR 主干） | B（前端合成/手部） | C（材质视差/雨天表面） |
| --- | --- | --- | --- |
| `shaders/world/restir_pt.slang` | **写** | — | — |
| `shaders/world/restir_duplication.comp.slang`（新文件，A-③） | **写** | — | — |
| `shaders/world/world.rgen.slang`（restir/合并/重放段） | **写** | — | — |
| `shaders/world/world.rgen.slang`（rain 求值段：`evaluateRainSurface` 调用点一带，与 A 的 restir 段不相邻） | — | — | **写**（rebase 无重叠，预期自动合并） |
| `shaders/world/world_common.slang`（`PackedPathReservoir`，布局冻结） | 只读 | 只读 | — |
| `shaders/world/volume_visibility.slang`（**只增** `volumeSkySurfaceIrradiance`；烘焙/既有消费主链不动） | — | **写** | — |
| `volume_visibility.comp.slang` / `volume_visibility_far.comp.slang` | — | 只读（本轮无主） | — |
| `mixin/GameRendererMixin.java`（手部 WrapOperation + 手部投影捕获） | — | **写** | — |
| `rt/entity/RtHandCapture.java`（新）、`RtEntityCollectorBase.java`（仅 hand no-op 钩子）、手部 submit 捕获 mixin（新） | — | **写**（实体主捕获链不动） | — |
| `rt/RtUiOverlay.java`（手部绘制接缝） | — | **写** | — |
| `rt/overlay/RtOverlayPipelines.java`、`rt/overlay/RtHandFeature.java`（新） | — | **写** | — |
| `shaders/world/hand_lit_common.slang`（新）、`shaders/overlay/hand_lit.vert.slang` / `hand_lit.frag.slang`（新） | — | **写** | — |
| `shaders/world/relief.slang`（新，无绑定模块） | — | — | **写** |
| `shaders/world/rain_surface.slang`（**只增** relief 感知重载与水位折算；`evaluateRainSurface`/`rainPuddleMaskAt` 既有拼写一字不动） | — | — | **写** |
| `shaders/world/world.rchit.slang`（材质纹理取用 + TBN/relief 求值段） | — | — | **写** |
| `shaders/world/world_primary.rgen.slang`（rain 求值段 + debug view 22） | — | — | **写** |
| `shaders/world/trace.slang`（Radiance Payload 增 relief lane；**阴影 payload 冻结**，铁律 4） | — | — | **写** |
| `rt/material/`（_n alpha 通道上传核验/修复 + 新契约测试） | — | — | **写** |
| `shaders/world/water*.slang`、`medium.slang`、`shaders/overlay/rain_streak*`、`rt/overlay/RtRainStreaks.java` | — | — | 无主，默认只读 |
| `rt/RtComposite.java` | 分区①：path reservoir 分配/统计 + duplication 派发（A-③） | 分区②：无改动预期（手部走 UI overlay，不经 RtComposite） | 分区③：无改动预期（无新 WorldPush lane、无新 pass） |
| `rt/RtPathReservoirStats.java`、`FluoriteConfig.java`（`composite.path-*` 键） | **写** | — | — |
| `FluoriteConfig.java`（`composite.hand-*` 键）+ 对应 lang 三语 | — | **写** | — |
| `FluoriteConfig.java`（`material.parallax*` / `weather.puddle-parallax` 键）+ 对应 lang 三语 | — | — | **写** |
| devlog | `M28-restir-backbone.md`，**D224–D233** | `M29-first-person-hand.md`（新），**D234–D243** | `M30-material-parallax.md`（新），**D244–D253** |
| `DEVELOPMENT.md` | §8.13 S3 行 | §8 新增 M29 小节 + §3.7 手部条目 | §8 新增 M30 小节 + §8.5 M21 段补注（「水坑不用视差」边界由 M30 supersede）+ `MATERIAL_FORMAT.md` 视差条目 |

无主文件（`math.slang`、`bsdf.slang` 等）默认只读；确实要动就在自己号段记档理由，并在合并时第一个声明。

### 3.2 冻结令

- 共享 ABI 冻结：`WorldPush`（1296 B）、`PackedPathSegment`（48 B）、`PackedPathReservoir`（48 B）、push-constant 块（120 B）。任何 agent 的设计若「需要动布局」，说明设计错了——S2 已验证重放子循环在 raygen 内联完成、不需要新段类型。**冻结豁免（2026-09-14 增，封闭清单共三条）**：A-① 与 A-③ 的两个 WorldPush **尾部**追加（`pathReplayEnabled`、`pathDuplicationAddr`），以及 C 线的一条 `reliefParams`（uint `reliefSwitches` + float `parallaxDepth`）——尾部追加是位置性的、不改任何既有偏移；A 第一个合并、C 最后一个合并，rebase 成本各只发生一次。硬性要求：codegen（`generateShaderRecords`）+ `RtSkyMediumLayoutTest` 钉子与字段同一 commit；结构中部与既有偏移仍然全冻；**除此之外不再接受任何豁免申请**。
- 默认值冻结：任何开关的默认档保持现状（铁律 8）。新开关一律默认关、关档 = 逐位发布行为（A 线两个新旋钮的「发布行为」= 各自落地前的 on-switch 行为，主开关 `composite.path-reservoir` 默认关已经护住发布帧）。
- 退役令冻结：本轮**不退役任何现有开关/视图/统计列**（那是 S4 的事）。

### 3.3 分支与合并

- 各自 `7a7d50f` 拉分支：A = `feat/m28-s3-finish`，B = `feat/m29-first-person-hand-lighting`，C = `feat/m30-parallax-puddle-link`。
- 合并回 `feat/m28-restir-backbone` 的顺序固定 **A → B → C**；后合并者 rebase 后自行解冲突。`RtComposite.java` 只在各自分区内改，正常情况下 git 可自动合并。
- 每个逻辑片一个 commit（参考既有风格：一句话标题说清「做了什么+为什么」），S3 剩余项之间也分开 commit（归因要求）。

### 3.4 构建与测试门（每个 commit）

1. slangc ≥ 2026.14 编译 + spirv-val 全绿（含 EXT SER 变体，构建自动跑）。
2. `gradlew build` 全绿；触碰区域的 contract/layout 测试全绿；新增 `FRAME.count/stage` 名先注册（`RtFrameStatsNameRegistryTest` 兜底）。
3. 契约测试优先：新机制先加源级钉子再实现（仓库惯例，见 `RtPathReplayContractTest` 先红后绿的先例）。

### 3.5 跨线请求

发现需要别线所有权内的东西（例如 B 的探针想读 A 的统计、C 的修复想动 `medium.slang`）：**不改**。在自己 devlog 号段写清需求与理由，收进本文件的 §7 待协调清单，由用户裁决排期。

### 3.6 验收交接

构建就绪后交给用户启动游戏。给用户的验收说明必须包含（[`feedback-in-game-test-protocol`] 约定）：观察点、预期现象、截图/复现条件、现象→诊断对照表；旋钮写 UI 中文名或 TOML 确切键名；性能验收给 `frame.csv` 的 A→B→A 会话脚本。

## 4. 工作流 A：M28 S3 收尾（ReSTIR 主干）

**目标**：把 S3 剩余的三项决策 6 技术收尾，让「静止 + 邻居≥2」的降噪收益在用户复验中成立且无偏。

**必读**（全体的之外）：`devlog/M28-restir-backbone.md` 全文（重点 D216–D223）；`Papers/M28-restir-backbone-survey.md` §1.1/§2.2（Enhanced 论文细节查 `Papers/_txt/` 的 lin2026restirptenhanced）；`shaders/world/restir_pt.slang` 现状（`evalPathSuffixCandidate`、幸存者抽签、W 恒等式注释）；`world.rgen.slang` 的空间循环与时域块。

**所有权**：见 §3.1 A 行。

### 论文对照检查结论（2026-09-14，Enhanced 全文 vs 当前实现）

对照 `Papers/_txt/lin2026restirptenhanced.txt` 逐条核对论文 §2/§4/§5/§6 与 `restir_pt.slang` / `world.rgen.slang` / `world_common.slang` 的结果。三个实质发现已直接折进下面三个任务的写法；符合项与刻意偏离项列出备查，不再另立任务：

| 论文条款 | 判定 | 处置 |
| --- | --- | --- |
| §2.1/2.2/2.3 GRIS 骨架、48B 记录结构、W 恒等式、PSS 雅可比（Eq.2） | ✅ 同构，代数核对无误——in-place 后缀复用下 `J = cos比` 是 Eq.2 的正确退化形，三条写回路径都维持恒等式 | 无动作 |
| §4 双 ray-footprint 判据 + §4.2 单顶点粗糙度门 | ⚠️ 哲学一致（D223 已足迹化）、形态不同（接收侧位移界 vs 候选密度界，属 in-place 方案的自然形）；**粗糙度门缺失**——记录到验证全程无粗糙度条件，glossy 重连顶点上 retarget 误差被 f(wi) 的方向敏感性放大 | **发现 1** → A-①a |
| §6.3 RGB 向量权重 | ✅ 着色侧已满足（applied 估计按 RGB candValue 累积）；⚠️ 选择权重用候选 **home 域** target 而非**接收域**值——无偏性不受影响（选择目标可为任意正函数，只要 W 恒等式的 draw/sums/t_chosen 同域），但幸存者代表性次优 | **发现 2** → A-②b |
| §5 duplication map | ❌ 未落地；**S3a 写回泛化后其标靶真实存在**：门一开，幸存者写回是邻居 payload 的原样拷贝（含 `pathSeed`）、m 可到 17，同一样本跨像素复制跨帧存活，正是论文 §5 的「firefly 经复用扩散成相关团块」机制，而当前无任何去相关件（Cap 16 是常数） | **发现 3** → A-③ 优先级上调 |
| §6.1 统一 DI+GI / §3 配对空间复用 / §6.2 工程四项 | ⏳ S4 / 决策 6 明确推迟 / 随 A-① 落地 | 不变 |
| 空间复用读上帧 parity、应用门 D221、J 钳制、Cap 16 vs 论文 20、半径 12px vs 30px、足迹常数 | 已记档的刻意偏离或代码已注 uncalibrated 的待校准参数 | 无动作（按 D223 验收校准） |

### 任务 A-①：随机重放子循环 + 重连粗糙度门（hybrid shift，主工程量；含发现 1）

**机制（照论文 hybrid shift 精确拼写，消除「重追到重连顶点」的歧义）**：重放前缀**止于深度 `reconIndex − 1`**（得 y_{k-1}），后缀永不重追；然后从 y_{k-1} **连接**到存储记录的重连锚点 x_k（`stored.reconPos`，后缀原地复用），连接方向上用 y_{k-1} 的**真实材料**（重放解码的，非 retarget）求值。现状（D217/D222）的「对应性校验 + 本顶点材料原地复权」对 `reconIndex == 0`（主流 diffuse 像素）是精确移位、保持不动；对 `reconIndex > 0`（镜面链）是已记档近似，本任务换掉。

#### A-①a：重连粗糙度门 + 深度占比统计（先行，独立 commit，无重放本体）

1. `restir_pt.slang` 新常量（含取值依据注释）：
   ```slang
   // Enhanced §4.2 的单顶点粗糙度门，作用在重连顶点的前一顶点 x_{k-1} 上。论文 ρ_min=0.2 是
   // Falcor 的感知粗糙度语义；本仓库存储值就是 GGX alpha（铁律 2），换算 0.2² = 0.04。
   // 这是论文常数的一次性单位换算，不是对任何存储值平方。校准：stats lane 5 的 deep-recon
   // 接受率观察，0.04 只是起点。
   public static const float PATH_RECONNECT_MIN_ALPHA = 0.04;
   ```
2. `world.rgen.slang` `tracePath` 新增循环携带状态 `float prevVertexRough = 1.0;`（与 `reconPos` 等声明同区块）：每个不透明着色顶点解码 `rough` 后更新；`reconQualifies`（现 `= recordPath && !reconFound && !exactSpecular`）追加 `&& prevVertexRough >= PATH_RECONNECT_MIN_ALPHA`。初值 1.0 的语义：相机前缀空泛地视为粗糙（`reconIndex == 0` 没有 x_{k-1} 表面，论文脚注 6 的 diffuse 情形），门只作用于镜面链。**无开关**：这是记录的正确性判据，只影响 on-switch 帧，主开关默认关已护住发布行为。
3. `RtPathReservoirStats.LANES` 5→6（Java 与 shader 同 commit，banner 的 lane 表同步）：lane 5 = 被接受候选中 `reconIndex > 0` 的采样计数（日志名 `deep-recon`）。**这一步的产出是一张占比底片**，A-①b 是否开工由它决定。
4. `RtPathReplayContractTest` 新钉：`reconQualifies` 行含 `prevVertexRough >= PATH_RECONNECT_MIN_ALPHA`；`prevVertexRough` 在 tracePath 内声明。

**决策门（铁律 1）**：用户复验拿到 lane 5 数据后，若 `reconIndex > 0` 候选占比可忽略（< ~5% 被接受候选），重放在本仓库没有估计量角色——报备数字，由用户裁决「照做 A-①b / 推迟」。

#### A-①b：重放子循环本体（仅空间候选；时域 reconIndex>0 的 retarget 近似本轮记档保留）

新代码全部预先定名，**除函数体外无自由度**：

1. **新结构体 `ReplayedPrefix`（放 `restir_pt.slang`——求值侧类型，`world.rgen` 经既有 `import restir_pt` 可见；不放 world_common 是因为它携带 `BsdfContext`，而 restir_pt 已 import bsdf）**：
   ```slang
   public struct ReplayedPrefix {
       bool   valid;        // false = 移位失败，候选作废（调用方 continue；禁止静默零贡献）
       float3 throughput;   // y_{k-1} 处的重放前缀吞吐 T_pre
       float3 origin;       // y_{k-1} 位置
       float3 normal;       // y_{k-1} 着色法线
       float3 wi;           // 单位向量 y_{k-1} -> stored.reconPos（连接方向，重放函数内算好）
       float  dist;         // ||stored.reconPos - y_{k-1}||，可见性射线的 tmax
       BsdfContext bc;      // y_{k-1} 的真实材料上下文（重放解码，非 retarget）
       float3 diffAlb; float3 F0; float pf; float ps; float walkSssShare; // 与 evalPathSuffixCandidate 同款参数束
       float  jacobian;     // 论文 Eq.2 的重放前缀逐顶点乘积，沿用 D219 的 [1/3,3] 钳制
   };
   ```
2. **重放函数（放 `world.rgen.slang` 文件域——需要 `traceRadiance`/payload 访问，trace 依赖进不了 restir_pt）**：
   ```slang
   public ReplayedPrefix replayPrefixToReconnect(uint replaySeed, float3 ro, float3 rd,
                                                 uint reconIndex, float3 reconPos)
   ```
   - 重放种子初始化：`uint seed = pcg(replaySeed);`（记录只发生在 sampleIndex==0，与记录侧 `seg.seed ^ sampleIndex*C → pcg` 的推导一致性由契约钉子钉住）。
   - 内联循环复用主循环同款 `traceRadiance` / `traceRadianceReordered` 分支、同款 lobe 选择与方向抽样、同款 rayCone 与 medium 状态推进，逐顶点累计 Eq.2 因子；**深度达到 `reconIndex - 1` 即停**，算 `wi`/`dist` 返回。
   - `valid = false` 的全部出口（函数注释逐条枚举）：重放路径 miss 逃逸；命中 delta lobe（无有限密度，移位不可逆——论文的 invertibility check）；到达 maxBounces 未见深度；重放链的 `prevVertexRough < PATH_RECONNECT_MIN_ALPHA`（门对称作用于重放侧）；连接方向在 y_{k-1} 或 `stored.reconNrm` 一侧切向（cos < `PATH_REUSE_MIN_SURFACE_COS`）。
3. **求值函数（放 `restir_pt.slang`，签名固定）**：
   ```slang
   public bool evalReconnectedSuffixCandidate(ReplayedPrefix p, float3 storedNrm,
                                              PackedPathReservoir stored,
                                              out float3 value, out float count, out float target)
   ```
   - `value = max(W · p.throughput · continuationReconnectWeight(…) · p.jacobian · unpackRgb9e5(stored.li), 0)`；`count = min(stored.m, PATH_RESERVOIR_M_CAP)`（与 in-place 路径同式）。
4. **一处拼写重构（先行小 commit，无行为变化）**：把 `evalPathSuffixCandidate` 内的 f·cos/pdf 块抽成共享 helper（restir_pt 文件域）：
   ```slang
   float3 continuationReconnectWeight(BsdfContext bc, float3 diffAlb, float3 F0,
                                      float pf, float ps, float walkSssShare,
                                      float3 v, float3 wi)
   ```
   in-place 与 reconnect 两条求值路径共用——文件横幅已警告「两种拼写 = 复用率失效」，这条重构就是防它。
5. **调用点（`world.rgen.slang` 空间候选循环）**：`candidate.reconIndex == 0u` 走现有 `evalPathSuffixCandidate`（精确移位，零改动）；`> 0u` 走 `replayPrefixToReconnect` → **可见性** → `evalReconnectedSuffixCandidate`。可见性复用 `trace.slang` 的 `visibilityMasked(CULL_SHADOW_NO_SELF, offsetSurfaceOrigin(p.origin, p.normal, p.wi, SURF_BIAS), p.wi, p.dist)`，透射为零 ⇒ `continue`（铁律 4：阴影 payload 不增长，复用不新增）。
6. **开关**：新旋钮 `composite.path-replay`（`FluoriteConfig`，默认**关**；关档 = A-①b 之前的 retarget 行为——测量 A/B 的隔离臂 + 2080 Mobile 上重放成本爆掉时的回退位）。WorldPush 尾部追加 uint lane（§3.2 豁免），codegen + layout 测试同 commit。S4 验收后关档退役。
7. **stats**：`RtPathReservoirStats.LANES` 6→8：lane 6 = replay attempted、lane 7 = replay valid。banner 读法：attempted 高 valid 低 = 移位在死（delta/切向/可见性），与 D223 的 s-usable 读法并列。
8. **契约钉**（`RtPathReplayContractTest`，全部源级 contains）：`pcg(replaySeed)` 推导存在；`replayPrefixToReconnect` 的失败出口全部以 `valid = false` 收尾（扫重放循环内不带 valid=false 的 `break`）；reconnect 路径调用 `visibilityMasked`；`continuationReconnectWeight` 同时被 `evalPathSuffixCandidate` 与 `evalReconnectedSuffixCandidate` 引用（一拼写钉）；重放循环内不出现对 `reconIndex` 深度之后顶点的追踪（后缀永不重追——扫函数内 `for`/`while` 的边界表达式含 `reconIndex - 1` 或等价形式）。
9. **已知风险（S2 设计记档，落地即测）**：raygen 寄存器压力 R6 台阶——`frame.csv` 的 `traceIndirect` 同会话 A/B（`composite.path-replay` 开/关）；雅可比错误 = 整体偏亮/偏暗——固定机位收敛对照（静态 J≡1 必须仍成立）。

#### A-①c：归档 commit

固定机位收敛对照 + `frame.csv` 同会话 A→B→A（`composite.path-replay` 翻转），随 D 号段记档；D219 两件套（J 钳制 + 双侧 cos 门）在重放路径的等价物写进 devlog。

### 任务 A-②：RGB 向量权重（钉现状）+ 接收域选择目标（论文 §6.3 对齐；含发现 2）

**论文条款**（§6.3）：重采样用标量（luminance）驱动，着色用向量累积——「解耦重采样与着色」；选择目标 p̂ 在**接收域**求值。

#### A-②a：钉现状（独立 commit，无行为变化）

2026-09-14 对照结论：着色侧**已满足**——applied 估计 `(own + Σ m_i·value_i)/(1+Σ m_i)` 按 RGB `candValue` 累积，选择侧用标量。本片只写契约钉子 + devlog 结清决策 6③ 前半：
- `RtPathReplayContractTest` 新钉两条源级断言：applied 估计表达式含 `temporalCount * temporalValue + spatialValue`（RGB 累积，向量进画面）；幸存者抽签的权重是标量 luminance（`rndf(seed) * totalTarget` 的形式不变）。

#### A-②b：接收域选择目标（行为变化，独立 commit）

现状的 `candWeight = candCount * candMeanTarget` 用候选 **home 域** target（`stored.targetSum/stored.m`，候选在老家的亮度）。换成接收域值（`luminance(candValue)`，本顶点重评后的贡献亮度），幸存者代表性与论文一致。**无偏性论证（写进 restir_pt banner）**：GRIS 的选择目标可为任意正函数，无偏性只要求 W 恒等式的 draw/sums/t_chosen 三者同域——下帧读 `meanTarget = targetSum/m` 也只是正数选择提示，域可以不同。

落点（全部预先写定）：
1. `world.rgen.slang` 候选循环：`candWeight = candCount * candMeanTarget` 改为 `candWeight = candCount * luminance(candValue)`（时域/空间同式，一处拼写）；`temporalSum` / `spatialTargetSum` 语义随之变为接收域目标和，注释同步（banner 与 D217 的 f(wi) 口径注释）。
2. 幸存者写回的 `t_chosen` 换到同域：时域胜 = `luminance(temporalValue)`；空间胜 = 胜者的 `luminance(candValue)`（`spatialWinTarget` 的赋值点同改）。W 公式 `W = targetSum'/(m'·t_chosen)` 不变，域整体切换。
3. **域一致性是这条的生命线**：契约钉子钉住 draw/sums/t_chosen 三处的 `luminance(` 同款拼写；固定机位收敛对照亮度必须不偏移（静态 J≡1 下域切换不改变期望，偏了 = 域混用）。
4. 验收：静止 A/B 噪声不劣化（预期变好：幸存者更贴接收域，下帧时域候选更准）；`deep-recon`/`applied` 不受影响（选择分布不进 applied 估计的归一化）。

### 任务 A-③：duplication map 自适应降 Cap（决策 6④；发现 3——优先级上调为「核对后优先请示落地」）

**为什么升级（2026-09-14 检查）**：D219 的「写回恒为新鲜单样本」只在门未开时护住；S3a 门一开，幸存者写回是邻居 payload 的**原样拷贝（含 `pathSeed`）**、m 可到 17——同一样本跨像素复制、跨帧存活，正是论文 §5 的「firefly 经复用扩散成相关团块」机制，而当前无任何去相关件（Cap 16 是常数）。**这是相关性控制目前唯一的缺失环节。**

**铁律 1 前置（不变）**：论文承认该技术带偏（实测 3.25% 平均绝对相对偏差，MIS 的 partition of unity 被破坏，偏差集中在 glossy 面）。落地方案已预先写定（下述），**开工前把「偏差换相关」取舍连同本设计一并请示**；未获批不动手。

**落地方案（预先定稿，除函数体外全部在此）**：

1. **检测 pass**：新文件 `shaders/world/restir_duplication.comp.slang`（binding 结构照 `volume_visibility_far.comp.slang` 先例；记录读法经 `world_common` 的 `PackedPathReservoir`——D220 教训，布局不出现第二份）。每像素读本 parity 的 `pathSeed`，数 (2R+1)² 邻域内同 seed 的记录数，写 `score = count/((2R+1)²)` 进字节缓冲（0–255 量化）。模块常量：
   ```slang
   public static const int  PATH_DUP_RADIUS_PX = 8;   // 17×17 = 论文默认；2080 Mobile 上 gpu.restirDup
                                                     // 超预算则减半到 4（9×9），由实测裁决
   public static const float PATH_DUP_GAMMA   = 0.1; // 论文推荐：γ→0 快降，γ=1 线性降
   public static const float PATH_DUP_CAP_MIN = 1.0; // 满重复度时的最低 Cap
   ```
2. **资源与派发**（`RtComposite` 分区①）：score 缓冲（1 字节/像素）随 reservoir 同开关同 `ensureOutput` 分配；dispatch 在 pass B 之后（rgen 写完本帧记录）同帧执行；GPU zone `gpu.restirDup` 同 commit 登记 `RtFrameStats`/`RtGpuTimers`（D218 规则，`RtFrameStatsNameRegistryTest` 兜底）。
3. **地址 lane**：`WorldPush` 尾部追加 `pathDuplicationAddr`（在 A-① 的 uint lane 之后 tail-append；codegen + `RtSkyMediumLayoutTest` 钉子同 commit——§3.2 冻结令的唯一豁免）。
4. **开关**：新旋钮 `composite.path-duplication`（默认**关**；关档 = 不分配 score 缓冲、地址推 0、消费端 score 恒 0 = 与 A-③ 之前逐位一致——同会话 A→B→A 的测量臂）。
5. **消费**（`restir_pt.slang`，一处拼写）：`evalPathSuffixCandidate` 与 `evalReconnectedSuffixCandidate` 签名各加一参 `uint dupScore`（调用方在 readPixel / neighbourPixel 处读字节，地址 0 时直接传 0）；两函数共用同一内联式：
   ```slang
   float dupCap = lerp(PATH_RESERVOIR_M_CAP, PATH_DUP_CAP_MIN, pow(dupScore / 255.0, PATH_DUP_GAMMA));
   count = min(stored.m, dupCap);   // 时域/空间两移位同式同域
   ```
   语义：重复度压的是**历史发言权**（merge 权重与幸存者抽签的 m），不是 applied 门的 `extraCount` 计数——门读的是「独立候选数」，重复的历史本来就谎报了独立性，压回真值。
6. **契约钉**（`RtPathReplayContractTest`）：lerp 式在 restir_pt 恰好一处；duplication pass 只经 world_common 读记录；rgen 侧地址 0 ⇒ `dupScore == 0` 的守卫存在。
7. **验收**：静止场景同会话 `composite.path-duplication` A→B→A：高相关区（glossy 面、水面反射）的相关亮斑/拖尾应减少；整体亮度轻微下探（论文量级 ~3%，明显超出 = γ/CAP_MIN 取值问题回报）；`gpu.restirDup` 成本归档（17×17 超预算则 R=4 重测）。

### 顺序与验收（A 线总）

commit 顺序固定，每项独立可回退：**A-②a（钉现状）→ A-①a（粗糙度门 + 占比统计）→〔用户裁决点：deep-recon 占比数据决定 A-①b 是否开工〕→ A-②b（接收域选择）→ A-①b/c（重放）→〔用户裁决点：A-③「偏差换相关」请示〕→ A-③**。S3b 复验（D223 清单：s-usable 抬升 / applied > 0 / 静止噪点变细、亮度不偏移）随时可能回来反馈；回来即优先响应，且 A 线任何改动不得与 S3b 的效果混档归因（每片独立 commit 是归因的底线）。

交给用户的最终验收：静止 + `composite.restir-spatial-neighbours ≥ 2`（UI 名「ReSTIR 空间邻居数」）+ `composite.path-replay` 开 + `composite.path-duplication` 开（获批后），间接光区（墙角/屋檐）A/B 噪点变细且**亮度不偏移**；移动 ≈ 关档。性能按铁律 7 同会话 A→B→A：主开关、`composite.path-replay`、`composite.path-duplication` 各自成对翻转段，`traceIndirect` 底值（R6 台阶观察）与 `gpu.restirDup` 归档。stats 日志读法：s-usable 抬升（D223 目标）、`deep-recon` 占比（A-①a 产出）、replay attempted/valid（A-①b）、applied > 0。

**禁止**：不动 M24（`restir.slang`、`RtRestirStats`、M24 reservoir 的退役是 S4）；不动 `PackedPathSegment`/`PackedPathReservoir` 布局与 `WorldPush` 既有偏移（§3.2 冻结令 + 尾部追加豁免除外）；不动 `segment.slang` 的 seed 推进（重放的确定性命脉，改它 = 全部存储种子静默作废）；不改任何既有开关的默认档。

## 5. 工作流 B：M29 第一人称手部 RT 光照（前端与光影同步）

**目标**：第一人称的手（手臂 + 手持物）不再吃 vanilla lightmap 的平光，而是接受 RT 场景的**正确光照**（太阳/天空/发光体，含阴影）与 **PBR 材质**（资源包 LabPBR 的法线/高光，手持方块与物品各自生效）。

**现状事实链**（2026-09-14 核验，全部有代码锚点）：

- 手目前是 **vanilla 光栅**：`GameRendererMixin.fluorite$redirectHandToOverlay` 把 `renderItemInHand` 的输出重定向进 UI overlay（`RtUiOverlay`），原生分辨率、RR 之后、GUI 之前合成（DLSS-FG 的 hudless/pUI 形状因此成立）。它的光照就是 vanilla lightmap——与 RT 世界不一致，这正是本任务要修的。
- 第一人称**身体**已在 TLAS（`RtEntities` 以 `MASK_SELF` 捕获，参与反射/阴影/GI、不挡主射线）——本任务不动它；手部**渲染**与身体**追踪**是两条链。
- overlay 光栅管线**已能在 frag 里对 TLAS 发 ray query**（`RtBlockOutlineFeature` 的内联 `rayQueryEXT`，`RtOverlayPipelines.AccelStructureSet` 管 TLAS 绑定环）。
- **受光雨丝**（`rain_streak_light.comp.slang`）就是「overlay 内解析 RT 光照」的完整先例：push 带 `worldPushAddr` + 五个光源地址（`LightBufferAddresses`），TLAS 内联阴影线，`atmosphere`/`light_sampling`/`world_common` 直接 import。
- 采集侧：`RtEntityCollectorBase implements SubmitNodeCollector`，每 quad 拿到 sprite/tint/lightCoords，atlas sprite 经 `RtMaterialRegistry.resolveEntitySprite` 解析出**含 LabPBR 的材质**；实体纹理槽按 image view 键控（玩家皮肤走整图路径）。M18 已在真实 submitItem 路径观察过手持物姿态/sprite/tint。
- 方向性天空场的 4 个扇区辐亮度 `skySectorRadiance[4]` 就在 WorldPush（`world_common.slang:456`）；消费函数 `volumeSkyOpenness(p)` 双模（标量/方向）。**表面**的天空环境项消费者还不存在（S1 只接了 marched 雾）——B 线补上它，恰好是 M28 决策 3（「雾与表面天空环境项读同一格同一分布」）的首个表面消费者。

**方案裁决记档**（目标由用户 2026-09-14 指令；路线按铁律 1 的精神在分派层记档，实施中若发现事实不符再回来请示）：选**自绘 display-res 光栅 pass + 解析 RT 光照**（雨丝同构），不选「手部全光追」。理由：① 手部走独立 viewmodel 投影（`setProjectionMatrix` ordinal=1 的手部窗口），塞进主 pass 意味着第二套 primary/guides/RR 链，工程与显存双倍；② 1spp 在 display-res 近距高光上的噪声过不了 RR（手不在 RR 域内）；③ 解析光照（真阴影线 + 网格天空场 + 光源 NEE）与雨丝同构、噪声为零、成本一个 overlay pass。物理缺口（记档）：**GI 缺失**——手部只收直接太阳/天空/发光体，间接反弹（洞里火把照亮的手臂）v1 没有来源，缓解项 = 天空项的网格开阔度；真正的 GI 接入（路径 reservoir 采样或辐照 probe）列 §8。

**必读**（全体的之外）：`devlog/M19-M20-entities-particles.md`（实体捕获链）；`devlog/M18-dynamic-light-data.md`（submitItem 观察）；`rain_streak_light.comp.slang` + `rt/overlay/RtRainStreaks.java`（push/绑定/地址模式，全任务的模板）；`rt/overlay/RtBlockOutlineFeature.java` + `RtOverlayPipelines.java`（TLAS 光栅管线与 `AccelStructureSet`）；`mixin/GameRendererMixin.java`（手部重定向与投影捕获点）；`devlog/M28-restir-backbone.md` D209–D213（方向性天空场语义）；`volume_visibility.slang`（网格消费与扇区权重）；`DEVELOPMENT.md` §3.7（粒子 raw albedo 不烘 lightmap 的先例——手部同规）。

**所有权**：见 §3.1 B 列。**禁改**：实体主捕获链（`captureEntities` 一字不动，手部走独立采集器）；身体 `MASK_SELF` 语义；`volume_visibility.slang` 的既有函数（只增不改）；雨丝 pass（C 线地盘）；WorldPush 布局（本任务**零** ABI 改动——`worldPushAddr` 只读）。**开关**：`composite.hand-rt-lighting`（默认关；关档 = vanilla 手部经既有 overlay 重定向逐位不变，铁律 8）。

### B-0：手部采集与投影基线（无画面变化，独立 commit）

1. **新采集器** `rt/entity/RtHandCapture.java`（单例，帧态）：
   - `public void begin(RtEntityCollectorBase collector)` / `public RtHandFrame end()`——帧数据 `RtHandFrame`：视空间 quad 顶点（沿用 `RtEntityCapture` 的顶点布局与 sprite/材质槽）、`handModelView`（`Matrix4f`）及其逆、手部投影 `Matrix4f`。
   - `RtHandCollector extends RtEntityCollectorBase`：覆写动态光记录为 no-op（第一人称手持光已由身体捕获链的 D18 路径记录，采集两遍会双计——覆写点就是所有权行里说的「hand no-op 钩子」）。
2. **捕获挂点**：扩展 `GameRendererMixin.fluorite$redirectHandToOverlay`——`beginOutputRedirect` 后、`original.call` 前后包 `RtHandCapture.INSTANCE.begin/end`。**本片唯一实现期发现点**：手部网格的 vanilla submit 点（`renderItemInHand` 内部的 PlayerAvatarRenderer/ItemInHand 链，MC 26.2 类名以反编译为准）需要一个小 mixin 把 quad 流转入 `RtHandCollector`（复用 `SubmitNodeCollector` 接口的数据面：sprite、tint、uv remap、材质解析全部白拿）；**备选**：若 submit 点不便包裹，改自驱动 extract+submit（`captureEntities` 的 `dispatcher.extractEntity/submit(state, …, collector)` 同款），姿态差异以截图对照裁决。Fabric/NeoForge 双端都要跑（§4.5）。
3. **手部投影捕获**：`GameRendererMixin` 新增 `@ModifyArg`（`setProjectionMatrix` ordinal=1 的 `Matrix4f` 参，即手部/3D-HUD 投影）→ `RtHandCapture.captureHandProjection(Matrix4f)`。modelView 从 WrapOperation 的既有参数直接拿。
4. **计数**：`FRAME.count("handQuads", n)`、`FRAME.count("handTextures", n)`——名字先进注册表（D218 规则）。**本片验收**：帧统计出现且数量随手持物切换变化；画面零变化。

### B-1：自绘 pass 与 vanilla 平替（姿态对齐里程碑，独立 commit）

1. **数据布局**（push 恒 < 128 B，矩阵走 BDA——两个 mat4 加地址就超 Vulkan push 常量保证，雨丝的「地址进 push、数据走 BDA」同款）。`shaders/world/hand_lit_common.slang`（放 world/，照 `rain_streak_common.slang` 先例）：
   ```slang
   public struct HandLitPush {                 // 72 B，push constant
       public uint64_t worldPushAddr;          // WorldPush 只读 BDA（含 dynamicLightAddr/Count，B-2 用）
       public uint64_t lightBufAddr;           // LightBufferAddresses 五件套，照 RainStreakPush
       public uint64_t lightAliasAddr;
       public uint64_t lightLocalAliasAddr;
       public uint64_t lightGridCellAddr;
       public uint64_t lightGridSpanAddr;
       public uint64_t materialAddr;           // MaterialHeader/Extension 缓冲（实体材质解析的产物）
       public uint64_t frameDataAddr;          // HandFrameData，每帧 host-visible 写入
       public uint    quadCount;
   };
   public struct HandFrameData {               // BDA，与 RtHandFrame 一一对应
       public float4x4 handProjection;         // B-0 捕获的手部投影
       public float4x4 handModelViewInv;       // 视空间 -> 世界空间（光照用世界方向）
   };
   ```
2. **管线**：`RtOverlayPipelines.VertexFormat` 新增条目（pos + normal + uv + materialIndex + RGBA8 color，步长与 `RtEntityCapture` 顶点布局对齐）；新光栅管线绑定：顶点缓冲、实体纹理槽采样器（皮肤整图 + blocks/item atlas）、材质纹理（LabPBR normal/specular）、`AccelStructureSet`（TLAS，ray query 用）。`rt/overlay/RtHandFeature.java`：
   - `public static boolean suppressesVanillaHand()`——配置开**且**上一帧捕获成功（失败不抑制，vanilla 手兜底，日志一行）。
   - `public void draw(VkCommandBuffer, RtUiOverlay.ImageHandle, RtHandFrame, RtGpuExecutor.GraphicsUse)`——录制手部绘制。
3. **抑制 vanilla**：`fluorite$redirectHandToOverlay` 中 `if (!RtHandFeature.suppressesVanillaHand()) original.call(...)`（关档路径逐字节旧分支）。
4. **绘制接缝**：与 `RtWorldOverlay.compositeIntoUiOverlay` 同一 `endWorldScaleBeforeHand` 接缝，画进 UI overlay 图（vanilla GUI 在其后、照旧盖在手之上——层级与现状一致；被抑制的 vanilla 手部窗口不再写入）。
5. **shaders**：`shaders/overlay/hand_lit.vert.slang`（`handProjection × viewPos`，输出世界 pos/TBN/uv/materialIdx）与 `hand_lit.frag.slang` 骨架——本片 frag = albedo × `worldPush.mediumSkyRadiance`（只求**看得见**，光照正确性全在 B-2）。
6. **GPU zone** `gpu.handLit` + stage 注册同 commit。**本片验收**：开关 A/B 同位姿截图，开的姿态/大小/摇摆与 vanilla 手**逐像素对齐**（对不齐 = 投影或 pose 捕获错，禁止进 B-2）。

### B-2：RT 光照 + PBR（核心，两个 commit：B-2a 太阳+天空，B-2b 发光体+PBR）

frag 的三个光照项与一个求值，全部预先定名（`hand_lit.frag.slang` 文件域）：

1. **`float3 handSunTerm(float3 p, float3 n, float3 v, MaterialCtx m, inout uint seed)`**：有限面积天体采样照抄世界的 idiom（`sampleSquare`/`squareLightPdf`/`sampleProviderCelestialIrradiance`，`worldPush.lightRadiance` 是 E 口径——直接光 = `E · f(ω)·max(cos,0)`，**不引入 4π**）；阴影 = 一条抖动阴影线（M25 结论的表面版）：内联 `RayQuery`（`RAY_FLAG_FORCE_OPAQUE | ACCEPT_FIRST_HIT_AND_END_SEARCH`，cull mask `CULL_SECONDARY_NO_SELF`——雨丝同款；手部阴影不碰第一人称身体，与 vanilla 语义一致）；云影 = D176 光空间透射率图的既有读取函数（实现时从 `world.rchit`/`cloud_shadow.comp.slang` 定位函数名并 import，**不得复制拼写**）。
2. **`float3 handSkyTerm(float3 p, float3 n)`**：新消费 helper **`volumeSkySurfaceIrradiance(float3 p, float3 n)`**（加在 `volume_visibility.slang`，只增）：
   - 方向场模式：`Σ_k W_k · skySectorRadiance[k].xyz · bin_k(p) · max(dot(n, ω̂_k), 0)`（ω̂_k = 扇区中心方向，W_k = 扇区立体角权重——与 marched 雾的 S1 公式同一格同一分布，决策 3 的表面消费者）；
   - 标量模式（方向场开关关）：`mediumSkyRadiance.xyz · volumeSkyOpenness(p)` 的既有语义折算半 Lambert（`·(1+dot(n,up))/2` 钳制），关档连续性优先于方向正确性（雾可调性教训 [[feedback-fog-tunability]] 的同款约束）。
3. **`float3 handEmitterTerm(float3 p, float3 n, float3 v, MaterialCtx m, inout uint seed)`**——两个来源，缺一不可：
   - **静态发光体**：1–2 个 RIS 候选（`light_sampling` 的 grid/alias 路径，`LightBufferAddresses` 从 push 填充——雨丝同款），每个候选一条 `RayQuery` 阴影线，求值 `Le·f·cos/d²`；
   - **动态光（含手持火把——2026-09-14 补，原稿漏了）**：静态 grid 里**没有**动态光（M18 数据层独立成 buffer，M24 S3 只把它接进了 DI 候选），手持火把经此路径照不到手。补法：直接读 `worldPush.dynamicLightAddr` 的 `Light` 记录（restir.slang:285 的同款读法），**上限评估 32 条**（`ConstPtr<Light>` 循环，`dynamicLightCount` 截断），按未遮挡贡献 `Le·f·cos/d²` 排序取**前 3 条**打阴影线，其余按可见近似计入。手持火把几乎恒在前 3（最近）。偏差记档：top-3 截断 + 未打线者的可见近似。
   - **两处拼写的风险**： emitter NEE 的求值式与 `world.rchit` 是第二份拼写——frag 横幅必须写明并互指（froxel NEE 已有此先例），若实现时发现 rchit 的求值可提取为共享模块，提取优先（记档不强制）。
4. **`float3 handLitBrdf(...)`**：`bsdf.slang` 的 Disney 求值（与世界的表面着色同一实现，不是简化 BRDF）；LabPBR 法线贴图（quad TBN 切线空间，`MaterialHeader/Extension` 按 materialIdx 取——方块物品拿满 PBR，普通物品拿资源包给了什么用什么，皮肤走实体默认材质 + JSON override 通道）。
5. **合成**：`Lo = handLitBrdf · (handSunTerm + handSkyTerm项的辐照度折算 + handEmitterTerm)`；不消费 `lightCoords`（粒子 raw albedo 先例）；前缀介质忽略（手距相机 <1 m，量级记档）；输出 HDR 直进 UI overlay 图（曝光/显示映射是 overlay 合成的既有职责）。
6. **验收前自检**：`composite.hand-rt-lighting` 关 = vanilla 手逐位；方向场/出格钳制两开关对手部天空项生效（读同一格）；无光源/夜间的手部不该全黑（天空项 + 保底）。

### B-3：验收与归档（交用户）

观察点清单（同 [[feedback-in-game-test-protocol]] 格式）：
- **姿态**：开档 vs 关档（vanilla 手）同位姿截图逐像素对齐（B-1 的复验延续到 B-2 之后——光照变了但轮廓/摇摆不得变）。
- **太阳同步**：头顶放方块 → 手部按世界阴影同步变暗；转动视角，手持方块的高光随太阳方位移动；日出/正午/夜晚色温与世界一致。
- **发光体**：手持火把 → 手臂和手持物被自己的火把照亮（动态光直接评估路径，见 B-2 item 3）；身旁放熔岩/萤石同验（静态 grid 路径）；远处火把隔墙不照亮手（阴影线）。
- **天空项**：洞内 vs 野外，手臂的环境亮度随开阔度变化；方向场开关 A/B 时洞口朝向的一侧手臂更亮。
- **PBR**：LabPBR 资源包下手持方块的法线细节与高光；金属方块（金锭等）的镜面响应。
- **回归**：3D 准星、屏幕效果（火/水下叠加）、F5 第三人称、望远镜均不受影响；Fabric 与 NeoForge 双端。
- **性能**：`gpu.handLit` zone 归档（预期一个 overlay pass + 每像素 ~2–3 条 ray query，手部约占屏 10–20%）；frame.csv 同会话 A/B。

devlog：新文件 `docs/devlog/M29-first-person-hand.md`（D234 号段起；README 索引随首条更新）；`DEVELOPMENT.md` §8 增 M29 小节、§3.7 增手部条目（「手部光照读 RT 场景缓存，不烘 vanilla lightmap」）。

## 6. 工作流 C：M30 材质视差 + 雨水坑联动（低凹先淹、水位随雨情涨落）

**目标**：带 LabPBR 高度图的材质获得**视差**（视向相关的表面起伏，位移纹理取用坐标）；雨天系统里的水坑**吃视差高度**——坑内水位与视差高度场比较，纹理低洼处先淹没、高处在水位上涨后才没顶，微观海岸线随蓄水/干燥爬移，观感如「雨水淹没了视差里的低处」。

**现状事实链**（2026-09-14 核验，全部有代码锚点）：

- **高度通道已在 GPU、只是没人用**：`world.rchit.slang` 的 `perturbNormal(..., float4 ntex, out float ao)` 拿到的 `_n` 是四通道——R/G = 切线法线 XY、B = AO（以 `NORMAL_AO_STRENGTH = 0.5` 压 albedo）、**A = LabPBR 高度，当前被丢弃**。C-0 的第一件事是核验 Java 侧上传没把 alpha 丢掉（若丢，改格式并钉契约）。
- **逐命中 TBN 现成**：`uvTangent(n, p0, p1, p2, t0, t1, t2, out handedness)`（VK_KHR_ray_tracing_position_fetch 的顶点 + UV 构造，含 Gram-Schmidt 与 handedness）——视差行进的切线空间就是它，无需新几何。
- **取用 LOD 有 ray-cone 体系**：`rayConeTextureLod(...)` 已按足迹算纹理 LOD——视差行进读高度用低 LOD，最终位移后的取用沿用现有 LOD 路径。
- **水坑是纯位置函数、刻意无视差**（M21 记档的边界，本任务**有意打破并在 devlog supersede 该条**）：`rain_surface.slang` 的 `evaluateRainSurface(p, ext, filmReceiver, topReceiver, plantReceiver)`——`r.puddle = clamp(rainPuddle.y × retainedPuddle × affinity × rainPuddleMaskAt(p))`，掩码是世界锚定 FBM（`rainSmoothFbmPeriodic`），与纹理无关。**水位是连续标量**（`worldPush.rainPuddle.y` + history 的 retainedPuddle），这正是「涨落爬移」的驱动量。
- **水膜已能量分层进 BSDF**：`BsdfContext.rain`（film/filmAlpha），`rainFilmBrdf`/`rainBaseTransmission` 在所有表面求值处生效——联动的输出侧（水覆盖处的 BRDF）已有落点，缺的只是「何处有水、水多深」的纹理感知。
- **调用点三处**：`world.rgen.slang`（pass B 弹射顶点）、`world_primary.rgen.slang` 两处（pass A，含 debug view 20/21）。视差高度在 rchit 才知道（纹理在那取），需经 Radiance Payload 传给求值方。
- 雨滴冲击涟漪已由「原生水面与水坑共享的无状态世界锚定事件」提供——联动只改水的**范围与深度**，不动事件系统。

**必读**（全体的之外）：`devlog/M21-rain.md`（水坑/水膜/涟漪的完整设计与「不用视差」边界原文）；`rain_surface.slang` 全文；`world.rchit.slang` 的 `_n` 解码与 TBN 段（`perturbNormal`/`uvTangent`/`rayConeTextureLod`）；`docs/MATERIAL_FORMAT.md`（format-3 `weather` 块与「未改 LabPBR 包逐位不变」承诺——视差开关默认关正是保它）；`DEVELOPMENT.md` §2.8 偏差账（视差是新的已具名偏差，结案时入账）。

**所有权**：见 §3.1 C 列。**禁改**：`evaluateRainSurface`/`rainPuddleMaskAt` 的既有拼写一字不动（关档分支必须是旧式原样）；阴影 Payload（铁律 4）；`MaterialHeader`/`MaterialExtension` 的 64/80 B 布局（**v1 不加任何材质 ABI 字段**——视差深度用全局常量/开关，不进 per-material JSON）；涟漪事件系统。

**开关**（全部默认关；关档 = 逐位现状）：
- `material.parallax`（UI「材质视差」）：视差位移本体。
- `material.parallax-depth`（UI「视差深度（格）」，0–0.25，默认 0.0625）。
- `weather.puddle-parallax`（UI「水坑贴合材质凹凸」）：水坑联动；**独立于视差开关**——联动只读高度纹理（可用未位移的基础 UV），视差关着也能只开「低凹先淹」。

WorldPush 冻结令豁免（§3.2 已列）：C 线**尾部追加一条** `reliefParams`（打包：uint `reliefSwitches` bit0=parallax bit1=puddle-link + float parallaxDepth，8 B 尾部、codegen + layout 测试同 commit）——开关与深度必须到 shader，这是唯一的 ABI 改动。

### C-0：高度通道核验与契约（独立 commit，无画面变化）

1. 核验 `_n` 纹理上传保留 alpha（`rt/material/` 的上传格式；若被压成 RG8 → 改 RGBA8，路径与 mip 链一并核验）。
2. 新契约测试 `RtReliefContractTest`（源级钉，仓库惯例）：`_n` 上传格式含 alpha；`perturbNormal` 的 `ntex.w` 在 rchit 中被读取（证明高度可达着色端）。
3. **本片验收**：测试绿；画面零变化。

### C-1：视差本体（POM，独立 commit）

1. **新模块 `shaders/world/relief.slang`**（无绑定模块，纹理作参数传入——`precipitation.slang` 先例），两个公开函数（签名固定）：
   ```slang
   /** Parallax-occlusion march: step the view ray through the height field in tangent space.
    *  Returns the offset UV and the relief height at the exit point (0..1, 1 = no map/off). */
   public float2 parallaxUv(float2 baseUv, float2 texelSize, Sampler2D ntex, float lod,
                            float3 tangentViewDir, float depthBlocks, uint switches,
                            out float reliefHeight)
   public float puddleReliefDepth(float waterLevel, float reliefHeight)
   // = waterLevel - reliefHeight; > 0 = submerged. 单一拼写，C-2 全走它。
   ```
   行进参数为常量（`PARALLAX_STEPS = 16` 线性 + 一次线性插值精化；`RAY_CONE_MIN_*` 同族的最小足迹钳制）；`tangentViewDir` 由调用方用 `uvTangent` 的 TBN 变换。**关档契约**：`switches` bit0 = 0 时函数原样返回 `baseUv`、`reliefHeight = 1.0`——不进循环。
2. **rchit 插桩**：`perturbNormal` 调用点之前，取 `_n`（现有 fetch 旁，不新增采样点数量级）、调 `parallaxUv`，得到的位移 UV 交给**所有**后续纹理取用（albedo/_n/_s，同一偏移——三处取用必须同一 UV，契约钉）；`reliefHeight` 写入 Radiance Payload 新 lane（`payload.reliefHeight`，unorm16 打包，1.0 = 无图/关档；`trace.slang` 的 Payload 打包契约测试同 commit 更新）。
3. **关档逐位**：bit0=0 → UV 偏移恒 0、`reliefHeight` 恒 1.0，取用路径与现行拼写一致（源级钉：偏移应用在守卫内）。
4. **记档偏差**（入 §2.8 账）：虚拟位移不改命中几何——剪影/自遮蔽不真实（POM 自阴影与几何偏移列为 §8 升级项）；ray-cone LOD 与行进的交互（远距离行进步长退化到 mip 平坦高度——脚注写清「远距离视差自然退化为法线贴图」是有意的连续性行为）。
5. **本片验收**：开关 A/B，开档石砖/圆石从掠射角可见起伏，albedo/高光随视角滑动；关档逐位；`gpu.traceIndirect` 同会话 A/B 归档（rchit 每命中 +16 步行进的代价——超预算时降 `PARALLAX_STEPS` 并记档，或按铁律 1 请示「仅主命中启用」的选项）。

### C-2：水坑联动（两个 commit：C-2a 机制，C-2b 诊断视图）

1. **rain_surface.slang 只增不改**：新函数（既有 `evaluateRainSurface` 原样保留）：
   ```slang
   public RainSurface evaluateRainSurfaceRelief(float3 p, MaterialExtension ext,
                                                 bool filmReceiver, bool topReceiver, bool plantReceiver,
                                                 float reliefHeight)
   ```
   - `reliefHeight >= 1.0`（无图/视差关/联动关）→ **字面复用旧公式的分支**（逐位，钉契约）；否则：
   - 坑内水位 `waterLevel = r.puddle`（旧公式的全部因子——FBM 掩码、retained history、affinity、`rainPuddle.y`——原样决定「这一带有多少水」，**水位语义不变**）；
   - 淹没判定走 `puddleReliefDepth(waterLevel, reliefHeight)`，微观海岸线 = `smoothstep(0, PUDDLE_SHORELINE_EPS, depth)`（常量 ~0.05，连续性优先——[[feedback-fog-tunability]]：爬移的海岸线必须平滑，不许像素抖动）；
   - 深度驱动的三件事（全部在既有能量框架内，不新增能量规则）：`r.puddle ← 淹没系数`（水膜/分层 Fresnel 随之——现有 `rainFilmBrdf` 管道零改动）；`puddleExtraDarkening` 在既有 ≤0.25 帽内按深度插值（深水更暗）；**折射弯折**（可选常量 `PUDDLE_REFRACTION_BEND`，衬底 UV 沿切向视线偏移 `depth × bend`，非 Snell 精确——记档为艺术近似）。
2. **调用点接线**（三处，全部传 payload 的 `reliefHeight`；`weather.puddle-parallax` 关 → 传 1.0 即回旧行为）：`world.rgen.slang` 的 rain 求值段（C 所有权行）、`world_primary.rgen.slang` 两处。涟漪事件不动——水面的冲击法线扰动作用在淹没系数 > 0 的区域，C-2b 验收确认涟漪仍落在坑内。
3. **debug view 22**（编号连续，pass A 拥有，与 20/21 同族）：R = reliefHeight、G = waterLevel、B = 淹没深度——微观海岸线与涨落的归因仪表（D211 规则：捕获自带归因）。
4. **M21 边界 supersede**：devlog M21 的「水坑不使用真实几何或视差」条目由 M30 记档取代——**仍然不做**的是毫米级自由表面位移（水面仍是高度阈值层，非位移几何），现在**做**的是范围/深度吃视差场。
5. **本片验收**：LabPBR 高度包 + 石砖：下雨后灰浆凹缝先出现镜面水线、随 `rainPuddle.y` 涨落扩大/收缩；停雨后高处先干、凹缝最后干；涟漪仍打在坑内；联动关档 = 现行平滑 FBM 水坑逐位。

### C-3：验收与归档（交用户）

观察点清单（[[feedback-in-game-test-protocol]] 格式）：
- **视差**：`material.parallax` A/B——掠射角起伏、关档逐位；`material.parallax-depth` 0.03/0.0625/0.125 三档观感（过高 = 突起的「浮空」感，过低 = 仅法线感）。
- **联动**：雨中石砖低凹先淹、水位爬移、停雨高先干；「水坑贴合材质凹凸」独立开关下（视差关）仍有效；debug view 22 的 G/B 通道随雨情变化。
- **连续性**：海岸线无像素抖动（水位静止时完全静止）；反射/弹射路径里的坑也贴合（rchit 对每次命中生效，反射里看到的坑与主视图一致）。
- **回归**：无高度图的材质逐位不变（`reliefHeight=1.0` 路径）；雪天坑不冒水（M21 D-雪坑事故的回归位）；原生水面/玻璃不受联动影响。
- **性能**：`gpu.traceIndirect` 同会话 A/B（视差开/关 × 联动开/关四格）；POM 行进成本归档。

devlog：新文件 `docs/devlog/M30-material-parallax.md`（D244 号段起；README 索引随首条更新）；`DEVELOPMENT.md` §8 增 M30 小节、§8.5 M21 段补注、§2.8 偏差账加「视差虚拟位移」条目；`MATERIAL_FORMAT.md` 增「`_n` alpha = 高度，驱动视差与水坑联动」一节。

## 7. 待协调清单（跨线请求落这里）

1. **共享工作树检出冲突（2026-09-14，A 线记录）**：A 线在 `feat/m28-s3-finish` 提交 A-①b 时，工作树已被切到 `feat/m30-parallax-puddle-link`（C 线/M30），该提交（86d16f7）落在了 C 线分支上。处置：A 线未动 C 线检出，改在 `.claude/worktrees/line-a` 建 A 线 worktree，将提交 cherry-pick 回 `feat/m28-s3-finish`（e1730e6）；C 线分支上的 86d16f7 与 A 线 e1730e6 同补丁，合并时 git 按补丁等价处理。**协议建议**：并行期各 agent 用独立 worktree，共享树切分支前先 `git log --oneline -1` 确认无人正在提交。等用户裁决是否立为铁律。

## 8. 三条线之后（本轮不分派，防遗忘）

- **S4 表面 DI 迁入统一链、M24 reservoir 退役**：触发条件 = S3 用户验收 GO + A 线归档完成。显存风险（过渡期两套大缓冲并存）在 S4 设计期先算账。
- **S5 太阳/天体入池**：水下 Snell、云影规则、`E·phase` 口径逐项过契约测试；依赖 S4。
- **S6 雾照明接统一链 + D210 水拆段**：雾观感可调性是验收重点（M25 教训）；D210 拆段自备开关。届时与 C 线的水体语义改动合并排期。
- **Issue #77 远场暗区真值探针**：原 B 线任务，2026-09-14 让位给 M29；重启时照 D215 的探针设计做（真值 ≈0.2 → 物理/美术权衡，≈0.8 → 烘焙系统错误排查），**S1.5 暗区的最终处置**以它的判读结果为准。
- **Issue #20 跨骑判据取证**：原 C 线任务，2026-09-14 让位给 M30；重启时照 F29 原样做（差值判据 → 跨骑判据、拿到 fault 行就停、哪个水面说了算交用户裁决）。
- **Issue #40 近相机雨丝硬剔除**：原 C 线任务，同上；修复方向已记档（exposure/lit 剔除同点按最小距离直接弃实例）。
- **M30 视差升级项**（观感裁决后按需请示）：POM 自阴影与几何偏移（真实剪影）；per-material 视差深度（材质 JSON `parallax` 块——等 MaterialExtension 的 ABI 窗口）；`PARALLAX_STEPS` 超预算时「仅主命中启用视差」的分级。
- **M29 手部光照的 GI 缺口**：v1 手部只收直接太阳/天空/发光体；间接反弹（洞中火把照亮的手臂）的接入选项——读路径 reservoir 的时域候选（依赖 A 线 S3 成型）、辐照 probe、或接受现状——留 M29 验收后按观感裁决。
- **M29 手部全光追**（若解析光照的观感不够）：独立 viewmodel 追踪 pass（手部专用 primary/光照，主链 RR 不动），成本/噪声代价先按铁律 1 请示。
- 其余未结（M13 验收债务、M11 云成本结算、M14 末地动态 HDRI、云影精确后端）：依赖用户输入或主线切片，见 `DEVELOPMENT.md` §1.4/§8。
