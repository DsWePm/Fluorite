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
- **[Issue #20](https://github.com/DsWePm/Fluorite/issues/20)**：水面消失/水下曝光闪烁，机制已定位（F29：两个水面把相机夹进 2/9 格歧义带），检测器判据错误导致沉默 → 工作流 C。
- **[Issue #40](https://github.com/DsWePm/Fluorite/issues/40)**：近相机降水粒子拉出斜线，机制已知（守卫只收缩长度不弃实例）→ 工作流 C。
- **M29 第一人称手部 RT 光照（2026-09-14 用户指令）**：vanilla 光栅的手（手臂 + 手持物）改为接受 RT 场景的正确光照与 PBR 材质 → 工作流 B。

三条工作流按**子系统**切分：A = ReSTIR 主干（restir/reservoir/rgen），B = 前端合成与手部（mixin 重定向、overlay 光栅、实体捕获、天空场消费），C = 水体 + 降水粒子。文件交集只剩 `RtComposite.java`，用 §3.1 的分区规则处理。

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

| 文件/区域 | A（ReSTIR 主干） | B（前端合成/手部） | C（水体+降水） |
| --- | --- | --- | --- |
| `shaders/world/restir_pt.slang` | **写** | — | — |
| `shaders/world/restir_duplication.comp.slang`（新文件，A-③） | **写** | — | — |
| `shaders/world/world.rgen.slang`（restir/合并/重放段） | **写** | — | — |
| `shaders/world/world_common.slang`（`PackedPathReservoir`，布局冻结） | 只读 | 只读 | — |
| `shaders/world/volume_visibility.slang`（**只增** `volumeSkySurfaceIrradiance`；烘焙/既有消费主链不动） | — | **写** | — |
| `volume_visibility.comp.slang` / `volume_visibility_far.comp.slang` | — | 只读（本轮无主） | — |
| `mixin/GameRendererMixin.java`（手部 WrapOperation + 手部投影捕获） | — | **写** | — |
| `rt/entity/RtHandCapture.java`（新）、`RtEntityCollectorBase.java`（仅 hand no-op 钩子）、手部 submit 捕获 mixin（新） | — | **写**（实体主捕获链不动） | — |
| `rt/RtUiOverlay.java`（手部绘制接缝） | — | **写** | — |
| `rt/overlay/RtOverlayPipelines.java`、`rt/overlay/RtHandFeature.java`（新） | — | **写**（C 的 `RtRainStreaks.java` 不在此列） | — |
| `shaders/world/hand_lit_common.slang`（新）、`shaders/overlay/hand_lit.vert.slang` / `hand_lit.frag.slang`（新） | — | **写** | — |
| `shaders/overlay/rain_streak*.slang`、`rt/overlay/RtRainStreaks.java` | — | — | **写** |
| `shaders/world/water*.slang`、`medium.slang` | — | — | 只读（本轮禁改） |
| `rt/RtComposite.java` | 分区①：path reservoir 分配/统计 + duplication 派发（A-③） | 分区②：无改动预期（手部走 UI overlay，不经 RtComposite） | 分区③：水体 probe/诊断 |
| `rt/RtPathReservoirStats.java`、`FluoriteConfig.java`（`composite.path-*` 键） | **写** | — | — |
| `FluoriteConfig.java`（`composite.hand-*` 键）+ 对应 lang 三语 | — | **写** | — |
| devlog | `M28-restir-backbone.md`，**D224–D233** | `M29-first-person-hand.md`（新），**D234–D243** | `M12-water-simulation.md`（F30+）与 `M21-rain.md`，**D244–D253** |
| `DEVELOPMENT.md` | §8.13 S3 行 | §8 新增 M29 小节 + §3.7 手部条目 | §8.1 Issue #20/#40 |

无主文件（`math.slang`、`bsdf.slang` 等）默认只读；确实要动就在自己号段记档理由，并在合并时第一个声明。

### 3.2 冻结令

- 共享 ABI 冻结：`WorldPush`（1296 B）、`PackedPathSegment`（48 B）、`PackedPathReservoir`（48 B）、push-constant 块（120 B）。任何 agent 的设计若「需要动布局」，说明设计错了——S2 已验证重放子循环在 raygen 内联完成、不需要新段类型。**唯一豁免（2026-09-14 增，仅 A 线）**：A-① 与 A-③ 的两个 WorldPush **尾部**追加（`pathReplayEnabled`、`pathDuplicationAddr`）——尾部追加是位置性的、不改任何既有偏移，且 A 线第一个合并、B/C 只 rebase 一次。硬性要求：codegen（`generateShaderRecords`）+ `RtSkyMediumLayoutTest` 钉子与字段同一 commit；结构中部与既有偏移仍然全冻。
- 默认值冻结：任何开关的默认档保持现状（铁律 8）。新开关一律默认关、关档 = 逐位发布行为（A 线两个新旋钮的「发布行为」= 各自落地前的 on-switch 行为，主开关 `composite.path-reservoir` 默认关已经护住发布帧）。
- 退役令冻结：本轮**不退役任何现有开关/视图/统计列**（那是 S4 的事）。

### 3.3 分支与合并

- 各自从 `7a7d50f` 拉分支：A = `feat/m28-s3-finish`，B = `feat/m29-first-person-hand-lighting`，C = `feat/issue20-straddle-issue40-rain`。
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

## 5. 工作流 B：Issue #77 远场暗区真值探针（S1.5 挂起案重启第一步）

**目标**：把 D215 挂起的「山体旁暗区」从「远场读数低」推进到「物理真 vs 烘焙错」的**有证据裁决**，交给用户选择后续路线。

**背景（D214/D215 已钉死的事实，全部经过隔离验证）**：暗区仅「出格雾段遮挡」开时存在、位置锁定、室外头顶无遮挡；移动闪烁已由 D213 邻居播种消除；`FAR_CELL` 8→4 无改善，「粗格把坡遮挡糊进邻格」假设已被否——那些格子本身就收敛在低值（或烘焙有系统性错误）。

**必读**（全体的之外）：`devlog/M28-restir-backbone.md` D213–D215；`devlog/M25-volumetric-restir.md` 的可见性网格部分（EMA/重投影机制的来源）；`shaders/world/volume_visibility.slang` 与 `volume_visibility_far.comp.slang` 现状（横幅注释）；`DEVELOPMENT.md` §5.3 debug views。

**所有权**：见 §3.1 B 行。**禁改**：`volume.slang`（march 消费端本轮只读）、水路/froxel 消费者、远场开关默认档。

### 实施

1. **真值探针（新 debug view，取 22 号——现行编号连续，19 号 = 远场网格）**：在暗区位置的雾采样点，从同一点投 64 条半球射线（余弦加权，与烘焙同遮挡口径：仅地形、不含云 τ）直接算开阔度，与远场/钳制读数**并排显示**（左真值右读数，或双色叠加）。这是 D215 写明的重启第一步，照做，不要换设计。
2. 探针只读不改：烘焙链路、EMA、钳制策略一字不动；新视图默认不影响任何发布路径（debug view 0 = 关闭，即发布行为）。
3. **判读决策树**（D215 原文）：
   - 真值 ≈ 0.2 → 远场是对的，暗是物理（该点真的看不到多少天空）→ 转美术/密度权衡，**停下请示用户**（选项 + 观感截图）；
   - 真值 ≈ 0.8 → 远场烘焙有系统性错误 → 在所有权内逐项排查：格心入几何、坐标空间（世界锚定/迟滞重定位）、EMA 链路（自举种子污染？轮询 1/32 的收敛 vs 遗忘？）、射线遮挡口径不一致。找到根因 → 修（属已批 S1.5 设计内的 bug 修复）→ 关档逐位等价 + 修复档 A/B 截图交用户。
4. 若探针显示两种位置（暗区/正常区）读数都「半对不上」，把并排截图 + 数字报给用户，不要猜第三种机制。

### 验收

- 构建 + 测试门照 §3.4。
- 交给用户：站位清单（D215 的暗区复现位）、debug view 22 的开启方式（视频设置 → Fluorite Settings → 诊断）、三种判读各自的预期画面、需要的截图。
- devlog：探针设计、用户回执的数字、判读结论与后续路线（D234 号段起）。

## 6. 工作流 C：Issue #20 跨骑判据 + Issue #40 近相机雨丝硬剔除

**目标**：两个机制已定位的 Issue，一个换对检测器判据拿到证据（#20），一个落已知的防御修复（#40）。

**必读**（全体的之外）：`devlog/M12-water-simulation.md` F29 全文（机制定位 + 检测器为何沉默）；`devlog/M21-rain.md` 雨丝 pass 部分；`DEVELOPMENT.md` §8.1 两个 Issue 的现状段；`rt/RtComposite.java` 的水体 probe 区与 `shaders/overlay/rain_streak.vert.slang` 的 `nearCamera` 注释。

**所有权**：见 §3.1 C 行。**禁改**：`water_sim.comp`/`water.slang`/`medium.slang`/`water_wave.slang`（本轮只读——#20 的「哪个水面说了算」裁决出来之前不许动水语义）；雨丝 pass 的能量/亮度口径。

### 任务 C-①：Issue #20 检测器换跨骑判据（先取证，后修）

1. 把水体 probe 第五项从**差值判据**（`|waterPlaneRebasedY - surfaceY| > 1.5`，永不触发）改为**跨骑判据**：相机高度落在 `min(surfaceY, simPlane)` 与 `max(...)` 之间即为 fault。**无阈值常数**（F29 明示）。
2. probe 保持纯诊断：不改任何渲染语义，fault 行记录 `camY / surfaceY / simPlane` + 帧、天气、区块状态，落日志（沿用 M12 probe 的既有输出通道）。
3. **拿到 fault 行就停**：1.7778（介质参考水面）与 2.0000（仿真平面）哪个该说了算，是方向性裁决——整理证据（哪侧的曝光/合成是对的、歧义带内两侧各自的答案）列选项**请示用户**。获批后才进入修复片（修复片另立 commit，届时再申请改水语义文件的授权）。
4. 交给用户的复现脚本：F29 的触发姿势（水下朝曝光方向停 3 秒 → 朝缺失水面停 3 秒），加上日志开关说明。

### 任务 C-②：Issue #40 雨丝最小距离硬剔除

1. 机制已知：`rain_streak.vert.slang` 的 `nearCamera` 守卫只收缩长度不收缩宽度，且是 smoothstep 渐隐不是硬剔除——0.35 格内仍投出退化四边形横贯整屏；侧向轴 `cross(rainDirection, toCamera)` 近距退化放大之。
2. 修复方向（文档已写明）：在既有 exposure/lit 剔除同一处**按最小距离直接弃掉实例**，不是把长度渐隐到零。阈值做成常量并记档取值依据；关档（雨关闭）行为不变。
3. 未确认项交给用户观察：雨/雪是否都出现、是否与快速转视角相关（修复后两种天气 + 转视角各看一轮）。
4. 顺带记录：`cross` 回退轴是否仍需加固——若最小距离剔除后不再可达，记档即可，不扩大改动面。

### 验收

- 构建 + 测试门照 §3.4；probe 输出格式若有新 CSV/日志列，走注册表规则。
- 交给用户：#20 复现姿势 + fault 日志的取回方式；#40 的雨天/雪天/转视角观察点矩阵。
- devlog：`M12-water-simulation.md` F30（跨骑判据 + fault 行证据）与 `M21-rain.md`（剔除修复），用 D244+ 号段。

## 7. 待协调清单（跨线请求落这里）

（空——出现跨线需求时，由提出方在自己号段记档后追加到此，等用户裁决。）

## 8. 三条线之后（本轮不分派，防遗忘）

- **S4 表面 DI 迁入统一链、M24 reservoir 退役**：触发条件 = S3 用户验收 GO + A 线归档完成。显存风险（过渡期两套大缓冲并存）在 S4 设计期先算账。
- **S5 太阳/天体入池**：水下 Snell、云影规则、`E·phase` 口径逐项过契约测试；依赖 S4。
- **S6 雾照明接统一链 + D210 水拆段**：雾观感可调性是验收重点（M25 教训）；D210 拆段自备开关。届时与 C 线的水体语义改动合并排期。
- **S1.5 暗区的最终处置**（美术权衡 or 烘焙修复）视 B 线判读结果。
- 其余未结（M13 验收债务、M11 云成本结算、M14 末地动态 HDRI、云影精确后端）：依赖用户输入或主线切片，见 `DEVELOPMENT.md` §1.4/§8。
