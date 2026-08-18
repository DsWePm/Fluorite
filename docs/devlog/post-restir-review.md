# M24 后审查：能量重复计数与性能悬崖

> **第一轮（静态审查）已完成，游戏内验证未做。** 本文件记录审查方法、逐项裁决与依据。发现项若被采纳为改动，实施依据归入对应 devlog 条目。
>
> 触发原因：S4 发现地形发光体在镜面反弹上被计数两次（D192），而它在代码里活了很久没被发现。用户要求「再做一遍 review 看看还有没有那种重复计算的地方」，并要求给出所有可能的性能暴降点。

## 方法

**不逐文件读，而是逐「能量入口」读。** 一次重复计数总是形如：同一份物理量被两个估计器各自加了一遍，而没有任何一方被削权。所以枚举 `world.rgen.slang` 里**每一个把辐射加进 `L` 的位置**（12 处），对每一处问同一个问题：

> 这份光，还有谁在估计它？如果有，谁把权重降下来了？

这个问法能抓住 D192 那类错误，因为它不依赖注释说了什么——D192 的注释恰好宣称了一件代码没做的事。

---

## A 部分：能量入口逐项裁决

| # | 行 | 加的是什么 | 竞争的估计器 | 裁决 |
| --- | --- | --- | --- | --- |
| 1 | 209 | 段内体积内散射（闭式） | — | 干净 |
| 2 | 215 | 体积 NEE（发光体） | 1 | **干净**，见 A.1 |
| 3 | 243 | 云内散射 | — | 干净 |
| 4 | 271 | 逃逸到天空 | 10（太阳 NEE） | **干净**，MIS + `showCelestial` |
| 5 | 412 | 粒子的无遮挡环境光 | 4 | **有条件**，见 A.2 |
| 6 | 441 | 粒子太阳 NEE | 4 | 干净（粒子漫反射后 `showCelestial = false`） |
| 7 | 455 | 粒子 RIS | 9 | 干净（粒子不发光，无直击项与之竞争） |
| 8 | 545 | 表面的无遮挡环境光 | 4 | **有条件**，见 A.2 |
| 9 | 591 | 直击自发光 | 11 | **S4 修复**，见 D192 |
| 10 | 694 | 太阳 NEE | 4 | 干净，`powerHeuristic` |
| 11 | 712 | 表面 RIS | 9 | **S4 修复** |
| 12 | 745 | 薄壳 SSS 透射（太阳） | 11 的 SSS 分支 | **干净**，见 A.3 |

### A.1 体积 NEE 不与内散射重复，因为体积路径是解析的

`segIntegral.inScatter` 是闭式积分，只携带太阳与天空；`volumeNee` 补发光体，并按 `scatterWeight` 加权。二者互补。

关键的额外确认：**体积从不派生散射延续光线**。`volume.slang` / `segment.slang` 里没有任何 `cosineDir`/`sampleHg` 之类的方向采样把路径从介质内部继续出去——单次散射是沿段解析积分的。因此不存在「在雾里散射一次、再打中一个发光体」的路径，也就不存在与 `volumeNee` 竞争的第二个发光体估计器。

如果将来体积改成显式散射事件（体积 ReSTIR 会需要），**这一条会立刻失效**，届时必须补 MIS 或划分。

### A.2 `ENVIRONMENT_AMBIENT_UNOCCLUDED` 与天空逃逸：目前不重复，但**没有任何东西约束它**

两处（粒子 412、表面 545）在预设声明 `ambient_visibility: unoccluded` 时无条件加 `mediumSkyRadiance`，不采样任何可见性——注释自称是「D78A 的非物理可读性下限」。

同时，从该顶点出发的漫反射延续若逃逸到天空，会在 271 再加一次天空辐射。**如果一个预设同时声明「无遮挡环境光」和「可达的天空」，这两项就是同一份光的两次计入。**

实测已发布预设：

| 预设 | `ambient_visibility` | `provider` |
| --- | --- | --- |
| overworld | sky | atmosphere |
| the_end | sky | environment |
| the_nether | **unoccluded** | **local_ambient** |

只有下界用 `unoccluded`。

> **本段以下在动手修护栏时被推翻，见文末「第二轮遗留项」。** 原判断是「`local_ambient` 没有可达的天空盘/梯度，所以当前不重复，那是巧合」。事实是 `world.rmiss:178` 的 `local_ambient` 分支**直接把 `mediumSkyRadiance` 写进 `payload.albedo`**——逃逸背景是可达的，下界现在就在两次计入。**「没有天空盘/梯度」被我当成了「没有可达背景」，而均匀背景也是背景。**

**建议**：在 `RtSkyPresets` 解析时拒绝（或至少警告）`unoccluded` 与带天空的 provider 的组合。这是本轮唯一一条「现在没坏但没有护栏」的发现——「现在没坏」这半句也是错的，见下。

### A.3 SSS 的两项是互补的，不是重复的

- 745 的薄壳透射用 `backNdl = max(0, dot(-n, lightDir))`——**背面**，且只对太阳。
- 694 的太阳 NEE 用 `ndl > 0`——**正面**。

两者的半球互斥。而 `evalSampleContrib` 的 SSS 分支（`else if (c.sss > 0.0)`，即 `ndl <= 0` 时）覆盖的是**发光体**的背面透射，与 745 的**太阳**是不同的光源。三者两两不相交。

### A.4 本轮没有发现新的重复计数

除 A.2 那条无护栏项外，12 个入口都能指出「谁在削权」或「没有竞争者」。

**这不等于不存在。** 本方法只覆盖 `world.rgen.slang` 的 `L +=`；它看不见：

- **通量层面的重复**：例如同一个方块既进静态光缓冲、又被实体采集器当作发光实体收进球代理。S3/S4a 处理了实体网格与其代理，但没有系统性核对「一个发光体是否可能同时出现在两个光缓冲里」。
- **pass A 的贡献**：主命中前缀的体积积分由 pass A 计算、pass B 消费，两者的分界若错位会重复或丢失一段。`cameraPrefixIntegral` 有专门的 debug view（`DEBUG_VIEW_COMPOSITE_PREFIX_AB`）就是为此，但本轮没有跑它。
- **后处理**：bloom / 泛光是否把已计入的发光体再加一遍。

---

## B 部分：性能悬崖

### B.0 用户提供的关键事实：石英房间掉帧**早于 ReSTIR**

审查写完后用户补充：这个掉帧在 M24 开工之前就存在。

**这排除了 M24 新增的每一项每顶点成本** —— reservoir 读写、光源重读、空间邻居的 2k 次目标函数求值、S4 的 MIS 权重，全部与之无关。

剩下的嫌疑恰好是 B.1 与 B.3.2 那一对，而**两者都早于 M24**：

- **存活顶点数**（RR 与逃逸同时被击穿，B.1）——路径追踪的固有代价，与 RIS 同龄或更早；
- **每顶点的 RIS 候选走**（实测 5.9 ms / 18 ms，其中约 4.3 ms 在次级顶点，B.3.2）——M6 时代就在了。

两者相乘：封闭明亮房间制造大量次级顶点，而次级顶点正是候选走最贵的地方（实测如此）。这是当前唯一同时满足「量级够大」和「早于 ReSTIR」的解释。

**它仍然是推断。** B.1 的判据测试（降弹射数）不需要时间戳，且能一次分开这两个因子：

- 帧时间随弹射数近似线性下降 → 存活顶点数是主因；
- 几乎不变 → 是每顶点的固定成本，那就该去查 RIS 候选走本身（预采样池是已知的结构性解法，`lighting.slang:316` 有注记）。

### B.0.1 先纠正一个会误导排查的前提

**重复计数不花帧率。** 它把同一个数加两遍——算的工作量完全一样，变的是亮度不是开销。所以「石英房间掉帧」不可能是 D192 那个重复计数造成的，两者没有因果关系。

真正的原因几乎肯定是下面这条，而石英恰好把它的两个条件同时占满。

### B.1 头号悬崖：封闭的明亮房间同时击穿「逃逸」和「俄罗斯轮盘」

路径追踪的每帧成本 ≈ **存活路径数 × 每顶点成本**。开放世界里第一项被两件事压着：

1. **逃逸**：二次光线有相当比例打不到东西，进 miss shader 拿一次天空就结束了。
2. **俄罗斯轮盘**（`world.rgen.slang:393` / `857`）：`q = clamp(max(throughput.rgb), 0.02, 1.0)`，以概率 `q` 存活。漫反射后 `throughput *= albedo`。

普通地形 albedo 约 0.3–0.5，所以 `q ≈ 0.3–0.5`，多数路径在第 2–3 次弹射就被杀掉。

**石英是 `Profile.SMOOTH`，albedo 接近白（~0.85–0.9）**，于是：

- `q ≈ 0.9`，每次弹射 90% 存活；四次弹射后仍有约 73% 的路径活到上限；
- 一个**封闭**房间里没有任何光线逃逸，每条光线都命中几何。

两者叠加：**每条路径都跑满弹射预算，每个顶点都付全额成本。** 相对开放地形，存活顶点数可以差 3–5 倍，而这正是帧时间的乘数。

注意 RR 用的是 `max` 通道，所以判据是「**亮**」而不是「白」——一面饱和的亮红墙一样糟。

**验证方法**（不需要改代码）：站在石英房间里把 `composite.max-bounces` 从 4 降到 2，如果帧时间几乎减半，就是这条；如果几乎不变，就不是。

### B.2 每顶点成本清单：M24 把第二个因子也放大了

一个着色顶点现在要付：

| 项 | 成本 | 何时 |
| --- | --- | --- |
| 延续光线 | 1 TraceRay | 总是 |
| **RIS 候选走** | **M=8 次 alias→grid→record 依赖加载**，实测占 5.9 ms / 18 ms 帧 | `risOn` |
| 幸存者阴影线 | 1 TraceRay | `risOn` |
| 太阳 NEE 阴影线 | 1 TraceRay | `celestialOn` |
| ReSTIR 时间读 | 64 B 读 + 32 B 光源重读 + 校验 | 深度 > 0 |
| ReSTIR 写 | 64 B 写 | 深度 > 0 |
| **ReSTIR 空间读** | **k × (64 B 读 + 32 B 光源重读 + 2 次目标函数求值)** | 邻居 > 0 |
| S4 MIS 权重 | 1 次 `bsdfContinuationPdf` + 2 次密度 | 地形发光体 |

**关键交互**：B.1 让「存活顶点数」变成 3–5 倍，而 M24 让「每顶点成本」增加了。**两者相乘。** 所以石英房间恰好是 ReSTIR 开销被放得最大的场景，而它同时也是 ReSTIR 收益最大的场景（封闭空间全是局部光源）——这两件事必须分开测，不能凭感觉。

### B.3 悬崖排序（按预期幅度）

1. **封闭明亮空间**（B.1）。乘数最大，且完全由世界几何与材质决定，用户无法通过设置回避，只能降弹射数。
2. **RIS 候选走的依赖加载链**（实测 5.9 ms / 18 ms，其中约 4.3 ms 在次级顶点）。M24 把它乘以了「更多次级顶点存活」。**结构性解法是预采样候选池**（`lighting.slang:316` 的注记已经写明：共享池而非共享种子），未开工。
3. **ReSTIR 空间邻居**。每邻居 2 次 `evalSampleContrib` + 1 次 `retargetBsdfContext`。k=4 时相当于给每个顶点加了 8 次目标函数求值——**接近 RIS 候选走的一半**，而 RIS 那一半还带着更贵的指针追逐。
4. **reservoir store 的显存压力**。1080p 每层深度 265 MB × 2 halves × 路径数。深度 3 + spp 1 = 1.6 GB。**显存吃紧时驱动侧的搬运不会出现在 GPU 时间戳里**，只会表现为整体帧时间抖动——这是最容易误诊的一条。
5. **动态光候选**。选择是**均匀**的，不按功率。一个粒子很多的场景会让动态缓冲变长，而候选配额固定，于是配额被摊薄到大量暗粒子上——不是性能悬崖，是**质量**悬崖（噪声上升），但会诱使用户调高候选数，那才变成性能问题。
6. **深度 >1 的 reservoir**。D183/D186 已测：时间接受率 <1%，空间约 15%/9%。**付了 265 MB/层的带宽去读大概率被拒的数据。**

### B.4 本轮没有测量任何东西

以上全部是**静态分析加实测数字的重新组合**，没有一条是本轮新测的。§8.11 的 GPU 时间戳 A/B 用户已裁定不跑，因此：

- B.1 的验证只需要一次「降弹射数看帧时间」的对照，**不需要时间戳**，成本极低，建议做。
- B.2–B.3 的排序是推断，未经测量，**不应被当作结论引用**。

---

## 已核查且干净（第四轮不必重做）

- 体积内散射 / 体积 NEE / 云：三者互补，且体积无散射延续（A.1）。
- 太阳 NEE 与天空逃逸：`powerHeuristic` + `showCelestial` 双重把关（A.3 的邻项）。
- SSS 的三个分支两两不相交（A.3）。
- 粒子路径：不发光，无直击项与 RIS 竞争。
- RR 与逃逸的实现本身正确——B.1 不是 bug，是路径追踪在该场景下的固有代价。

## 未覆盖（下一轮的起点）

- 一个发光体是否可能同时进入静态光缓冲与动态球代理缓冲（通量层面的重复）。
- pass A 前缀与 pass B 叶片的分界（有 debug view 27 可跑，本轮未跑）。
- 后处理链是否二次计入发光体。
- `ENVIRONMENT_AMBIENT_UNOCCLUDED` 的护栏（A.2 的建议）。


---

# 第二轮：一个物理光源会不会进两个缓冲，以及 M24 留下的陈述

> 第一轮（能量入口 + 性能悬崖）见上。本轮按 pre-ReSTIR 的规格续做：静态清点里的「过期注释」与「关闭档」两类，加上第一轮点名未覆盖的第一条。

## 2.1 同一个物理光源进两个缓冲——**已核查，干净，且是被刻意设计过的**

这是第一轮列为「未覆盖」的头一条，也是最值得担心的一条：一个火把既是静态光缓冲里的矩形光（方块本身），它的火焰**粒子**又会被 S3 收成球代理。两份记录，一个物理火把。

查下来有一道明确的闸（`RtEntities.collectParticleLight:1371`）：

```java
int excess = ((packedLight >>> 4) & 15) - worldBlock;
if (excess <= 0) return;
```

**粒子只有在自身亮度超过所在格的世界方块光时才成为光源**，且强度按 `excess/15` 缩放。火把的火焰粒子坐在火把已经提供 14 级方块光的格子里，它贡献的只是超出那一份的部分。

实体身体光走同一套（`RtEntityCollectorBase:433`，`excess = reportedBlock - entityWorldBlockLight`）。

`worldBlock` 是 vanilla 的方块光，而静态矩形光来自材质发射——两者不是同一个量，所以这是**近似**而非精确抵消。但它是有原则的近似：方块光正是「这个格子已经被世界的静态光源照亮了多少」的度量。

### 但第三条路径没有这道闸，而且那是对的

`recordHeldLightQuad` 用 `stateEmission = getLightEmission()/15`，**没有 excess 检查**。这不是遗漏：**原版不会从手持物发出方块光**（那正是「动态光」类 mod 存在的理由），所以没有任何静态光代表手持火把，没有可抵扣的东西。加上这道闸反而会让你站在墙上火把旁边时，手里的火把被错误压制。

**三条路径、两种处理、不对称是正确的——而代码里没有一个字说明这件事。** 下一个动这块的人有一半概率把它「修」成一致。建议在 `configureDynamicLights` 附近写明。

## 2.2 两条已被 M24 推翻的注释（本轮直接修掉）

pre-ReSTIR 的 A3 类。两条都在宣称动态发光体尚未被采样：

| 位置 | 原文 | 现状 |
| --- | --- | --- |
| `world_common.slang:374` | 「…alias/grid sampling therefore sees rectangle records only **until the ReSTIR integration is approved**」 | S3 已给它们自己的分层通道 |
| `RtEntities.java:1750` | 「collection is measurable now, **sampling waits for ReSTIR**」 | 已通过 `WorldPush.dynamicLightAddr` 发布 |

两条都已改写，并顺带把「为什么是独立通道而不是并入 alias 表」和「那些位置已经 rebase 过」写进了前者——后者是 D187 里两个静默失败点之一。

## 2.3 关闭档清点（铁律 8）

| 开关 | 关档 | 状态 |
| --- | --- | --- |
| `composite.restir-reuse-depth` = 0 | 不分配缓冲、地址 0、回落单帧 RIS | ✓ 有测试 |
| `composite.restir-spatial-neighbours` = 0 | 循环零迭代 | ✓ 有测试 |
| `composite.dynamic-ris-candidates` = 0 | 通道跳过，不消耗随机数 | ✓ 有测试 |
| `composite.emitter-brightness` = 1.0 | 乘一（IEEE 精确） | ✓ 有测试 |
| `composite.emitter-temperature-k` = 0 | 完全跳过普朗克轨迹 | ✓ 有测试 |
| `diagnostics.restir-stats` = false | 地址 0，着色端提前返回 | ✓ |

**没有违规。** 但有一条结构性的欠缺：

### S4b 没有开关，因此它是 M24 里唯一无法在运行时 A/B 的改动

发光体 MIS 与移除高光下限**改变了已发布行为**（去掉镜面重复计数、让高光变锐），而它没有旋钮。铁律 7 明确偏好「运行时隔离开关优先于 git checkout A/B」——对这一项做不到。

这不是违规（铁律 8 管的是开关的关档，而这里没有开关），但它意味着：**如果将来有人报告「发光体附近的高光太噪」，无法在同一会话里把 MIS 关掉对比**，只能切 commit。若要补，成本是一个布尔 + 两处分支（`shadeReservoir` 的 `misWeight` 与 raygen 的 `emitterShare`），关档等于合并前的行为。

## 2.4 本轮未覆盖（第三轮的起点）

第一轮列的四条里，本轮结清了第一条，另外三条仍在：

- **pass A 前缀与 pass B 叶片的分界**——有 `DEBUG_VIEW_COMPOSITE_PREFIX_AB`（debug view 27 的邻居）专门为此存在，跑一次即可，但需要在游戏里跑。
- **后处理链是否二次计入发光体**（bloom/flare 的阈值取自已计入的辐射）。
- **`ENVIRONMENT_AMBIENT_UNOCCLUDED` 的预设护栏**（第一轮 A.2）。

以及本轮新增：

- **M24 新增代码的重复/耦合/边界条件**——pre-ReSTIR 第二轮的对应部分，本轮完全没做。已知的候选：`between/code/source` 三个测试 helper 在本轮新增的四个测试类里又复制了四份（pre-review 的 R5 记的是 ×12，现在是 ×16）。


---

# 第二轮遗留项 · 已动手：`unoccluded` 的 provider 护栏

> 用户裁定：从第二轮攒下的三条可动手项（`unoccluded` 护栏 / S4b 开关 / 测试 helper 去重）里挑一条做。选了护栏——另外两条是卫生项，这条挡的是「资源包一改字段，整个世界的光凭空翻倍，且无处报错」。

## R3.1 先更正 A.2：它的事实判断错了

A.2 说「下界配的 `local_ambient` 没有可达的天空，所以当前不重复」。查 `world.rmiss:178`：

```slang
if (worldPush.skyProvider == SKY_PROVIDER_LOCAL_AMBIENT) {
    float3 col = max(worldPush.mediumSkyRadiance.xyz, float3(0.0, 0.0, 0.0));
    payload.albedo = half3(clamp(col, ...));
```

**逃逸射线拿到的就是 `mediumSkyRadiance` 本身。** 所以下界此刻正在两次计入：表面在 545 无遮挡加一次，从同一表面出发的漫反射延续逃逸时在 271 再加一次。

我在 A.2 里把「没有天空盘/梯度」读成了「没有可达背景」。**均匀背景也是背景**，而且是同一个数。

## R3.2 但那不是 bug——是 D78A 明写批准过的

`DEVELOPMENT.md:592`：

> 环境光倍率统一缩放**表面保底光、逃逸背景与介质环境源**，不改变局部发光体功率；保底光不受遮挡是 D78A 明示的非物理可读性近似。

三项是**被当作一件事**批准的，共用一个维度滑条。所以下界的两次计入是设计，不是疏漏——这也正是 `ambient_radiance` 写 0.002 而不是写一个物理值的原因：它是**下限**，不是天空。

## R3.3 真正没有护栏的，是 `mediumSkyRadiance` 由谁写

三个 provider 的这一个字段来源完全不同（`RtComposite.mediumSkyRadiance` + `RtComposite:3192` 的 `if (atmosphereProvider)`）：

| provider | `mediumSkyRadiance` 从哪来 | 量级 |
| --- | --- | --- |
| `local_ambient` | 预设 authored 的 `ambient_radiance × 维度倍率` | 保底下限（下界 0.002） |
| `atmosphere` | **`sky_medium_reduce` 用 sky-view LUT 的相位积分覆写** | 真实天光 |
| `environment` | HDRI 的 `meanRadiance × 维度倍率` | 真实环境均值 |

**只有第一行是「保底光」，后两行是推导出来的日光。** 把后两者无遮挡地加到每一个漫反射表面上，密闭房间会和外面的旷野一样亮——而且哪儿都不会报错：没有 NaN、没有黑屏、没有校验失败，只是整张图均匀地错。这是最难归因到某个预设字段的一类错误。

所以护栏的判据不是「provider 有没有天空」（A.2 的表述），而是「**`mediumSkyRadiance` 是 authored 的下限，还是推导出的日光**」。当前这两个划分给出同一个答案，但前者是巧合，后者是理由。

## R3.4 改了什么

`RtSkyPresets.parse` 加一条跨字段一致性检查，紧挨着已有的那条同形状检查（`Environment providers require weather, clouds and fog off`）：

```java
if (ambientVisibility == RtSkyPreset.AmbientVisibility.UNOCCLUDED
        && provider != RtSkyPreset.SkyProvider.LOCAL_AMBIENT) {
    throw new IllegalArgumentException(
            "Unoccluded ambient visibility requires the local_ambient provider in " + source);
}
```

**「拒绝」在这里不是崩溃**：`load()` 逐资源 catch，写一行 WARN 点名文件，该维度回落 `FULL_ATMOSPHERE`。这是本文件对每一处跨字段矛盾的既有政策，不是本轮新发明的处理方式——所以这一条不构成方向性决策。

三个已发布预设全部不受影响：overworld = atmosphere + sky，the_end = environment + sky，the_nether = local_ambient + unoccluded。

约束同时写进了 `RtSkyPreset.AmbientVisibility` 的 javadoc——那是读到这个字段的人会看的地方。

## R3.5 测试：从已发布预设改一个字段

两个新用例，202 测试全绿。关键在**取材**：

- 用例从 `overworld.json` / `the_end.json` 的**真实文件**出发，只替换 `ambient_visibility` 一个字段。未改动版本先断言能解析通过——**这样「改动版本抛异常」就只能是这个字段的原因**。
- 手写 fixture 会green 得毫无意义：拿下界的 fog 拼一个 environment 预设，会先被 format-2 规则拒掉，护栏删了测试照样过。这正是上一轮踩过三次的坑（「断言了一个比要守的性质更弱的命题」）。
- `assertAll` 而不是 for 循环：循环在第一个维度就停，**只覆盖 atmosphere 而漏掉 environment 的护栏仍会显示绿色**。

**变异验证已做**：把判据改成 `if (false)`，两个维度**各自独立**报错（`MultipleFailuresError: 2 failures`），不是一个连带另一个。恢复后 202 全绿。

## R3.6 仍未动的两条

- ~~**S4b 开关**~~ —— 已做，见 R3.7。以下是当时的成本分析，保留。

  > **R3.6 初稿在此处写「`worldPush.flags` 的 32 位已全部分配完，需改用 `pc.shadeFlags` bit 2，引入一个当前不存在的耦合」。那是错的**，逐位重查的结果是 **bit 1 空闲**：`RtComposite:2769–2917` 共 19 处写入，覆盖 0、2–31，bit 1 无人写；`shaders/` 里也无人读（bit 1 在 `environmentFlags` 那个**另一个**字里是 `FROXEL_LOCAL_LIGHTS`，我第一次把两个字的位混在一起数了）。

  所以插桩成本恢复成第二轮的原始估计：**一个 bit，无新耦合**。三个落点都已经在读 WorldPush——raygen 读 `worldPush.flags`（116/138/1351），`shadeReservoir` 读它（`lighting.slang:594` 的水焦散），`evalSampleContrib` 读 `worldPush.emitterTint`。

  **真正的成本不在插桩，在关档的完整性**：off 若只恢复 MIS 权重而不恢复 alpha 下限，得到的是「无下限的高光被重复计数」——一张**从未发布过、且比两端都差**的图，拿它当 A/B 基准比没有开关更坏。要正确就得把三处 `UNWEIGHTED_SPEC_ALPHA_FLOOR` 和 `rainFilmBrdf(c, wi, true)` 一并放回 `evalSampleContrib`，而那是每顶点 M+1 次的最热循环。分支是 wave-uniform 且读的是已加载的值，预期极廉价，但按铁律 7 它落在实测过的 5.9 ms 里，**不能凭「应该很便宜」定论**。
- **测试 helper 去重**（`between`/`code`/`source` ×16）。


---

# R3.7 S4b 的隔离开关

> 承 R3.6。第二轮点名「M24 里唯一无法在运行时 A/B 的改动」，这次补上。

## 落点：`worldPush.flags` bit 1

R3.6 更正过的那个空闲位。三个读取点本来就在解引用 WorldPush，所以**没有新增任何耦合**，也没有新增 WorldPush 字段（`BYTE_SIZE` 不动，布局测试不受影响）。

**极性：设置为 OFF 时才置位**，于是 flags 全零 = 已发布画面。这是水体「无遮挡太阳」隔离开关（bit 15）的既有约定，理由相同：任何忘记置位的路径都应该落到已发布行为上，而不是落到旧估计器上。

## 关档必须是三处一起动

这是这次唯一需要想清楚的地方。S4b 改了三处：

| 位置 | ON（已发布） | OFF |
| --- | --- | --- |
| `world.rgen.slang` | 三情形（partition / MIS / delta 全取） | 1/0 闸 |
| `shadeReservoir` | `powerHeuristic(...)` 权重 | `misWeight = 1.0` |
| `evalSampleContrib` | 无 alpha 下限 | 三处下限 + `rainFilmBrdf(..., true)` |

**只恢复权重而不恢复下限，得到的是「无下限的高光被重复计数」——一张从未发布过、且比两端都差的图。** 拿它当 A/B 基准比没有开关更坏：对比出来的差异会被归因到权重，而实际来自下限，且没有任何东西会指出这一点。

所以三处共用一个 `emitterMisOn()`，`evalSampleContrib` 里进一步收敛成一个 `bool floorAlpha`，覆盖基础瓣和雨膜两处。

### 关档的等价性是机器验证的，不是眼看的

写了一段脚本：取 `f0b4de0^`（S4b 之前）的 `evalSampleContrib`，与当前版本把 `floorAlpha` 字面展开为 `true` 后逐行比对，去注释、归一化空白。

**结果：逐行相同。** 不是「看起来一样」。

这一步不是仪式。手写时最容易犯的错是**把各向同性分支的 Smith 项也加上下限**——各向异性分支确实 D 和 Smith 都用 `ax/ay`，看着就该对称。但已发布行为里各向同性分支**只给 D 加下限**，Smith 用未加下限的 `c.rough`（原注释：这么小的 alpha 下遮蔽项与 1 相差不到一个百分点，加了白加，还要多占一个寄存器）。那个不对称就是已发布行为，测试专门钉住了它。

其余两处的等价性是结构性的，不需要脚本：`* 1.0` 在 IEEE 下精确；raygen 的 `emitterShare = showCelestial ? 1.0 : 0.0` + `emitterShare > 0.0` 守卫，与旧的 `(!gateEmitter || showCelestial)` 真值表逐格相同，且触发时乘的是精确的 1.0。

## 开关**不**回退的那一半，以及为什么不能

S4b 顺带把切向量从 11 位压到 10 位（0.18° → 0.35°），腾出 bit 21 给 `PAYLOAD_EMITTER_PROXIED`。**那是 payload 布局改动，运行时开关无法回退**，也不该回退——它不改变估计器，只改变各向异性纹理方向的精度。

**所以这个开关的关档 = 「S4b 的估计器回退了」，不是「S4b 这个 commit 回退了」。** 用它做 A/B 时，切向量精度两端相同。

## UI

放在材质页 `sunMis()` 旁边，因为是同一个想法作用于两类光源——一个零角尺寸，一个有面积。差别在于波及面：太阳那个只动 roughness < 0.006 的材质，这个动的是**每一个站在火把旁边的高光**。en_us / zh_cn / zh_tw 三份。

## 测试

203 测试全绿（+1）。新增 `offIsTheEstimatorThatPredatesS4bAtEveryOneOfItsThreeSites`：三处各断言一次（任何一处单独漏掉都仍然渲染出一张有光的图）、极性断言（写反了会让所有忘记置位的路径静默发布旧估计器）、默认值断言。

原有三个 S4b 契约测试因为断言的是**无条件**形式而失败，已改成断言**条件**形式——这比原来更强：原来钉的是「下限不存在」，现在钉的是「下限恰好在权重关闭时存在」。
