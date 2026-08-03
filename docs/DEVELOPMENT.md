# Fluorite 开发文档

> **本文档是工程知识的唯一汇总入口。** 中文正文 + 英文术语/符号名（符号、配置键、文件名保持英文原样，可直接 grep）。
> 代码注释里引用的编号（`M*` 里程碑、`R*` 风险、`F*` 事实、`D*` 决策、`S3`）全部在本文档解析。
>
> **维护规则**：新的方向性决策进 §10 决策日志；新教训进 §9 档案；新铁律进 §3 索引并同时在代码注释处落地；里程碑状态变更改 §1 的表。三份历史计划（`C:\Users\Denni\.claude\plans\` 下）已标记 SUPERSEDED，只作考古，不再更新。
>
> **对所有协作者（含 AI agent）生效的硬规则**：任何方向性决策——方法选择、物理近似、默认档位、性能取舍——必须列出候选项 + 与物理准确的差距 + 性能实测或估计，**经用户批准后**记入 §10 决策日志。禁止擅自决定。（2026-08-02 用户确立）

---

## 1. 项目总览与现状

### 1.1 项目是什么

Fluorite 是 Minecraft 26.2 的客户端 mod：基于 Vulkan 硬件光线追踪的世界渲染器，取消原版 `LevelRenderer.render()`，把自己的合成结果拷回 MC 的 main render target。约 30.6k 行 Java（包名 **`io.github.dswepm.fluorite`**）+ 43 个 shader 文件（`shaders/world/` 29 个，7272 行 Slang）。

- Fork 自 **Caustica**（ComfyFluffy 等，LGPL-3.0-or-later），2026-07 改名独立开发；双版权（原作者 + Yuhan YAN / DsWePm）。
- 双加载器：Fabric + NeoForge，`common/` 是**共享源码目录而非 Gradle 子项目**（各加载器 `srcDir` 引入，对着各自的 MC 产物编译）。
- 已知历史遗留：旧文档中的包名 `dev.comfyfluffy.caustica` 已失效。

### 1.2 测试环境与硬件事实

- **RTX 2080 移动版（Turing，8 GB）+ i7-8750H**；屏幕上限 1080p ⇒ 基准分辨率**定为 1920×1080 显式声明**（F8）。
- 驱动报告 `SER=EXT`、OMM 支持——但 Turing 上**极可能是软件实现**：按「有接口、无收益」预算。OMM 已按硬件能力门控（R16 修复；`-Dfluorite.rt.omm`）。
- DLSS-RR（`nvngx_dlssd.dll` 310.7.0）正常；FG 默认关。`runClient` 默认 dev 版 DLL（有水印），`-PngxVendorConfig=rel` 去掉。
- **厂商中立约束（长期）**：新代码不得引入 NVIDIA 专属依赖；能力门控按「设备实际能不能做」而非扩展名/厂商名。AMD 结构性缺口：无降噪器时输出是 raw path trace，不可游玩（README TODO：NRD + FSR）。
- Y 轴约定：`jitter-sign-y = -1.0` + 上报 RR 时再取反。任何新的 `gDepth`/`gMotion`/NGX 提交代码都要显式确认自己站在约定哪一侧（DLSS dev 水印上下颠倒即此约定的可见证据，不是 bug）。

### 1.3 里程碑状态（2026-08-02，HEAD `50dd90e`）

| # | 里程碑 | 状态 | 备注 |
|---|---|---|---|
| M0–M4 | 双平台迁移 + 基准锁定 | ✅ | 地形摘要 986/989 一致，残差=NeoForge `hidesNeighborFace` 补丁（双方各自正确），见 `docs/PLATFORM_NOTES.md` |
| M5 | 渲染地基 | ✅ | segment ABI 进 codegen（48B 钉死）、break-before-attenuation 修复、`docs/WAVEFRONT_PLAN.md` |
| M6 | 统一介质框架 + 解析高度雾 | ✅ | `volume.slang`/`medium.slang`；雾开销 +0.622 ms |
| M7 | Disney BSDF | ✅ | sheen/clearcoat/各向异性/粗糙透射/太阳 MIS；`gpu.traceIndirect` 1.283×（门槛 1.5×） |
| M8 | 随机游走 BSSRDF | ✅ 实现 / ⚠️ 门槛 | 游走 1.567× 超 1.5% 门槛；默认 `thin`，游走作高配。两个 PROVISIONAL 常数未标定（`subsurface.slang`） |
| M9 | 水体散射（σa/σs） | ✅ | 七轮弯路后收尾：σa/σs 独立创作量、3 条分层**抖动**阴影线、折射太阳方向、焦散+色散。**性能欠账：bench-water 两机位从未采**（§4.4） |
| M10 | LUT 大气 | ✅ | 子阶段编号（代码注释在引用）：**M10.1**=大气单一实现（删 CPU 版 `atmosphereTransmittance`、F5 收口、transmittance 256×64）· **M10.2**=multi-scatter 表 32×32（binding 11）· **M10.3**=sky-view **三张**表 192×128（bindings 12–14，相位函数留在表外读取时施加，逐帧烘）· **M10.4**=aerial-perspective froxel 64×36×64（binding 15）。rmiss 用 `sampleSkyView`，CPU 推「未染色峰值」、GPU 端 `dyeCelestialLight` 染色 |
| M13.2/.3 | 体积可见性 + 能量标定 + 随机阴影线 | ✅ | 可见性网格 64×32×64（0.072 ms）；`MULTI_SCATTER_RETURN` 删除、`AMBIENT_FOG_FRACTION` 0.25→0.05；雾默认 1 条抖动太阳阴影线；水分层抖动化 |
| M11 | 体积云 | ⛔ 未开始 | 设计规格 §8.1；HP 对比表 §6.3；**挂进 M15 统一框架后动工** |
| M12 | 交互水体仿真 | ⛔ 未开始 | 设计规格 §8.2 |
| M13 残余 | 3D 噪声雾 + froxel 线程映射 | ⛔ | §8.3 |
| M14 | 维度预设 + 配置/UI/文档 | ⛔（二级设置界面已提前做） | §8.4 |
| **M15–M20** | **体积介质统一 + 光源收集 + overlay + 粒子** | 🔄 M15.0 ✅；M15.1/.2 代码完成、待游戏内/性能验收（2026-08-03） | §7，决策依据 §10 D1–D12 |
| — | ReSTIR 整合 | ⛔（M14 后） | 前向约束现在就生效，§8.5 |

---

## 2. 架构地图

### 2.1 双 pass wavefront（详见 `docs/WAVEFRONT_PLAN.md`，此处只留骨架）

- **Pass A** `world_primary.rgen.slang`：每像素一条相机光线，捕获 DLSS-RR guides 与运动矢量，走电介质链（最多**一次** delta 分裂），向队列写 1–2 条续段记录。普通 `TraceRay`（不摊 SER 屏障）。只对自己消费的段施加封闭介质 Beer–Lambert，**不做任何体积内散射**。
- **Pass B** `world.rgen.slang`：弹射循环（太阳 NEE+MIS、RIS、SSS、俄罗斯轮盘），**所有体积内散射在这里**，包括重建相机前缀段（ambient → 读 froxel；水下 → 零消光 `integrateSegment`）。编译两份：普通 + `world_ser`（SER）。
- 队列：`2 × width × height` 条 `PackedPathSegment`，索引固定不分配（`pixelIndex` / `W*H + pixelIndex`），`PATH_HAS_NEXT` 位；`MAX_PATH_SEGMENTS == 2` 与 `2×` 尺寸是同一事实的两次陈述，必须一起改。
- 分裂资格**只键于材质 model**（`MATERIAL_WATER`/`MATERIAL_DIELECTRIC`，粗糙电介质走终端续段由 Pass B 解析），永不键于叶集合。

### 2.2 `shaders/world/` 文件职责

**入口（编译 stage）**：

| 文件 | 职责 |
|---|---|
| `world_primary.rgen.slang` | Pass A（见上） |
| `world.rgen.slang` | Pass B（见上）+ debug 视图 |
| `world.rchit.slang` | closest-hit：逐 section BDA 表、材质求值、payload guides |
| `world.rahit.slang` | any-hit：alpha cutout + 阴影线染色累积（radiance/shadow 共用 Payload ABI） |
| `world.rmiss.slang` | 天空：sky-view LUT 采样 + 星空 + 日月盘 |
| `world_guide.rmiss.slang` / `shadow.rmiss.slang` | guide 探针 miss / SBT 占位（不实际运行） |
| `sky_transmittance.comp.slang` | 256×64 透射表（一次性烘焙） |
| `sky_multiscatter.comp.slang` | 32×32 大气多重散射表（一次性） |
| `sky_view.comp.slang` | 192×128 sky-view 表（**三张**：相位函数在读取时施加而非烘入——Mie 前向尖峰窄于方位轴分辨率；**逐帧**——依赖太阳） |
| `sky_froxel.comp.slang` | 64×36×64 相机前缀雾/空气透视 froxel（含大气项；自发光线，**不读**可见性网格） |
| `volume_visibility.comp.slang` | 64×32×64 世界空间可见性网格（R=太阳、G=天顶，各一条光线/cell，cell 中心写入） |
| `world_layout_probe.slang` | 仅构建期反射探针，不打包 |

**模块（import）**：`world_common.slang`（ABI：`WorldPush(Constants)`、`MaterialHeader/Extension`、`PackedPathSegment`、`Light`）· `world_core.slang`（bindings、payload、常量）· `math.slang` · `bsdf.slang` · `volume.slang`（体积积分器）· `volume_source.slang`（跨 stage 的纯高度积分/扩散/source 数学）· `medium.slang`（Medium/栈/参数/profile）· `water.slang`（波谱/法线/焦散）· `subsurface.slang` · `lighting.slang`（光网格+RIS）· `atmosphere.slang` · `atmosphere_lut.slang`（LUT bindings+读法）· `volume_visibility.slang` · `guides.slang`（仅 Pass A）· `segment.slang` · `trace.slang` / `trace_ser.slang`。

另有 `shaders/display/`（tonemap、直方图曝光、HDR UI 合成、SDR present）与 `shaders/overlay/`（方块轮廓、glow 轮廓、名牌——**光栅、display 分辨率**，因为「nothing thin/crisp survives DLSS-RR」）。

### 2.3 Java 包职责（`common/src/main/java/io/github/dswepm/fluorite/`）

| 包 | 职责 |
|---|---|
| 根 | `FluoriteMod`、`FluoriteLifecycle`（mixin 驱动的生命周期，无加载器事件层）、`FluoriteConfig`（1.6k 行：`-Dfluorite.*` → TOML → 默认三层） |
| `client/`, `client/gui/` | `RtVideoOptions`（仅收每帧重读的设置）、二级设置界面（`RtOptionsScreen`/`RtCategoryScreen`）、`WorldRenderScaler`、jitter |
| `mixin/` | 24 个：Vulkan 后端/instance/surface（PQ HDR）、`GameRendererMixin`、`ScreenEffectRendererMixin`（压掉原版水下贴图）等 |
| `platform/` | 加载器抽象全部表面（刻意极小、无事件层）：paths + quads |
| `rt/` | `RtComposite`（2.4k 行帧编排）、`RtContext`、`RtDeviceBringup`、`RtGpuExecutor`（保留队列）、`RtGpuTimers`（**唯一真实的 GPU 计时**）、`RtFrameStats` |
| `rt/accel/` `rt/pipeline/` | BLAS/TLAS/OMM；RT 管线+SBT、DLSS-RR/FG、曝光、display |
| `rt/terrain/` | 地形驻留/网格化/流体、**光源三件套**（`RtLightCollector`→`RtLightHierarchy`→`RtLightGrid` + `RtLightGridManager` 异步发布）、digest（M9 的 `RtSkyLightGrid` 已于 M15.0 删除） |
| `rt/entity/` | `RtEntities`（逐帧实体 BLAS/TLAS/几何表 + **粒子捕获**）、`RtEntityCollectorBase`、`RtEntityCapture`、`RtCuboidEmitter`（绝大多数生物的快路径）、`RtEntityTextures`、`RtParticleCapture` |
| `rt/material/` | decode-once-on-CPU 材质管线（LabPBR 只在 `RtLabPbr` 认识）、发光启发式、IOR 表、JSON overrides |
| `rt/sky/` | `RtSky`：全部大气 LUT 与**烘焙顺序**（链条依赖，顺序封在 `recordBakeIfNeeded` 内） |
| `rt/overlay/` | 共享 overlay buffer + 三个 feature（方块轮廓/glow/名牌） |

### 2.4 ABI 锚点（layout test 是变更闸门）

| 结构 | 大小 | 闸门测试 | 关键事实 |
|---|---|---|---|
| `PackedPathSegment` | **48 B** | `RtPathSegmentLayoutTest` | std430 量化到 16B 倍数：**只剩一个空 uint lane**；再加一个字段进位到 64B = 1440p 下 **+118 MB**。`pathFlags` bits 0–13 已用（9/10 water、12/13 ambient），新 flag 永远优先用位不用 lane |
| `WorldPushConstantsData` | 104 B | `RtMaterialLayoutTest` | 11 个 `uint64_t` 地址 + 3 uint（M15.0 删 `skyLightAddr`：112→104）；Vulkan 保证 128B，**余量 24B**（下一个地址花 8）。每加一个 uint 由 closest-hit 每次命中付费 |
| `MaterialHeaderData` | 80 B | `RtMaterialLayoutTest` | 逐字段偏移全部钉死 |
| `MaterialExtensionData` | 48 B | `RtMaterialExtensionLayoutTest` | Disney 十个标量打进三个 float4；测试断言**逐 lane 偏移** |
| `Light` | 32 B | — | 32 整除 64B cache line（48B 时代一半记录跨行、双倍事务）；面积不存储（`4·|halfU×halfV|` 反推） |

### 2.5 体积介质现状（M13.3 后，M15 重构的起点）

`Medium { float ior; float3 extinction; bool water; bool ambient; }`，栈深 2 具名字段。`integrateSegment`（`volume.slang`）单入口、两分支：

| | 封闭介质（水/玻璃/冰） | 环境介质（空气雾） |
|---|---|---|
| 透射率 | 闭式 `exp(-σt·t)` | 闭式（高度积分 `ambientHeightIntegral`，剖面**在基准高度以下饱和**） |
| 太阳项 | `enclosedSingleScatter`：Snell 折射太阳方向 + 路径缩放；3 条分层**抖动**阴影线（τ=0/1.25/2.5，段内 jitter；`water.sun-shadow` 默认 false 只弃透射率、射线仍发——`waterHitT` 要用） | 闭式源 × `4π·hg`；默认 1 条抖动阴影线（`volumetrics.sun-shadow-rays=1`），0 = 读可见性网格；太阳路径用扩散衰减 σ（multi-scatter 开时） |
| 天空项 | `waterAmbient(=0.05×lightRadiance)` × 上射线深度 × 可见性网格开阔度 ×深度衰减积分 | `fogAmbient(=0.05×lightRadiance)×sunT + multiScatterLUT×SUN_INTENSITY`；天顶开阔度门控（网格 march 子步，telescoping 恒等式保证网格关闭=闭式 no-op） |
| 多重散射近似 | `effectiveAnisotropy`（g_eff = g^(1/(1−ω))）+ `diffuseAttenuation`（K=√(3σa·σtr) 钳 [σa,σt]） | 大气 MS LUT + 扩散衰减；**局部雾自身 MS 无模型（已知 limitation）** |
| 相位 | HG×95% + Rayleigh×5%（`enclosedPhase`） | HG（`fogScatter.w`，默认 0.55） |
| 相机前缀 | Pass B 零消光积分（Pass A 已扣吸收） | **froxel**（64×36×64，指数深度轴，含大气项） |

**M15.2（2026-08-03）已收口的分叉**：froxel 与 marched 共用 `volume_source.slang`；局部雾太阳自衰减补进 froxel，`fogScatter` 两边统一为 albedo。froxel 独有的行星大气项是有意口径差。**M15.0（2026-08-02）已修**：`=`/`+=` 潜伏缺陷（改 `+=` 带注释）、`RtSkyLightGrid` 死代码链整链删除（`WorldPushConstantsData` 112→104B）、`visibility-cell-size=0` 时跳过烘焙 dispatch、七处陈旧注释对齐。

---

## 3. 铁律与不变量索引

> 注释本体是唯一真相源，本表只是地图。**行号会漂移**——用文件 + 符号/关键词定位。改动任何相关代码前先读原注释。

### 3.1 BSDF / 材质

| 锚点 | 铁律 |
|---|---|
| `bsdf.slang` 顶部横幅 | **roughness 是线性的、就是 GGX alpha，永远不要平方。** Disney 论文的每条公式抄过来都必须把 `roughness²` 换成 alpha 原样。搞错同时坏着色、RR 滤波宽度、镜面吸附，且无报错 |
| `math.slang` `ggxD` | 分母 epsilon 已放到 `1e-20`（真除零保护）。**不许调回 `1e-7`**——旧值是被 MIS 取代的意外能量钳，回调会静默删掉已被正确加权的能量 |
| `math.slang` 各向异性注释 | 「isotropic 归约恒等」这个断言曾经错过一次，改前先验证 |
| `world_core.slang` | 有一个阈值「Deliberately NOT `MIRROR_ALPHA_MAX`」，差异承重（透射专用阈值 0.06 的由来见 `docs/MATERIAL_FORMAT.md`） |
| `RtLabPbr.java` | **不要重新引入运行时解码**；「No shader in this renderer knows what a LabPBR channel means」——加 SEUS PBR = 加一个 CPU 解码器，不是第二条 shader 路径 |
| `RtEmissionHeuristic.java` | 发光启发式的资格判定**刻意外置**：只对「已证明属于发光方块状态的 sprite」调用 |
| `RtMaterialOverridesTest` | format-1 文件必须永远渲染不变（缺省参数=缺省，不是「默认成相似值」）——测试钉死的性质 |
| `isDeltaAlpha ⇒ gNormal.w = 0` | 镜面吸附是 RR 保持反射锐利的机制，必须存活 |

### 3.2 介质 / 体积

| 锚点 | 铁律 |
|---|---|
| `medium.slang` `MediumStack` | 深度 2、具名字段、**非数组**（动态索引落 scratch，raygen 已寄存器受限）。`entering` 逐面重推导 ⇒ 走丢的路径下个界面自愈 |
| `medium.slang` `Medium.ambient` | 显式位，不是 `ior==1 && extinction==0` 推断（无色电介质会误答）。**环境介质永不进栈** |
| `medium.slang` `mediumScatterAlbedo` | σs 保持全局、反照率由 `σs/σt` 反推——48B 记录保住最后一个空 lane 的原因。σs∝σa 的灰色反照率教训写在原地 |
| `volume.slang` 透射率包装器 | **阴影 payload 不许增长**（SER 在 `ReorderThread` 溢出全部存活值）；透射率乘法只存在于唯一包装器里，「第四条阴影线不会悄悄漏掉」 |
| `volume.slang` 体积采样 | 采样位置**不得依赖段长**（否则相邻像素按命中距离读出量级不同的答案 = 斑块） |
| `volume.slang` 抖动分层 | 与 M9 被否估计器的区别是**逐帧逐像素抖动**：把稳定块状误差转成时域可滤噪声；但层内 uniform-distance + 闭式权重是**分层近似，不宣称严格无偏**（D9，`f/pdf` 留 M17）。可证伪判据：冻结时域累积必须看到**噪点而非块状** |
| `volume.slang` 源的分频 | 太阳项按太阳可达性门控、环境项按**天顶**可达性——山坡阴影不许抽走雾里的环境光（移除环境光的是屋顶不是山） |
| `volume.slang` telescoping | 可见性=1 时子步分段精确望远镜化回闭式。「新输入缺席时可证明是 no-op 的改动，屏幕上的差异只能来自新输入」 |
| `volume_visibility.slang` | 网格**采样不 storage**（nearest 会把 cell 边界画进雾里）；cell=1 对齐方块中心是一格墙不漏光的机制，cell=2 必然漏光 |
| `volume.slang` 扩散衰减 | K 是教科书结果**刻意不拟合**；钳制边界是物理域不是调参。吸收主导介质里它正确地什么都不做——深水变黑是对的答案 |
| `world_common.slang` `PackedPathSegment` 注释 + `RtPathSegmentLayoutTest` | 花掉最后一个 lane 之前先读测试注释；+118 MB 是代价 |

### 3.3 水

| 锚点 | 铁律 |
|---|---|
| `water.slang` 头注 | 读起来像水不像动玻璃的三性质：尖峰浪形、真深水色散 ω=√(gk)、风+蜿蜒。域世界锚定；**焦散对同一波场求导**所以永远同步 |
| `volume.slang` 折射太阳 | 水下太阳方向必须过 Snell（48.6° 锥；否则日出时光柱水平乱跑）。阴影线朝**折射后**方向发 |
| `water.slang` 色散 | 物理 1× 不可见（三通道差 <1%），默认 50× 是**刻意夸张**；`CAUSTIC_MAX` 夹平是嫌疑未定案。红通道 IOR 下限 1.05 防 `refract()` 静默退化 |
| `ScreenEffectRendererMixin` | 原版水下整屏贴图已压掉：光栅时代替代品，介质已被真实渲染。**没有水线代码**——半潜水线是水面平面自己的地平线，免费。火焰/方块视野遮挡 overlay 刻意保留 |
| `RtFluidMesher` | 从原版 `FluidRenderer` 改编但**不发背面**（原版为背面剔除光栅器双写顶面） |
| `RtComposite` `waterAnchor` | 深度参考是「相机所在水体的水面高度」；出水时不能把全场景深度归零（曾整屏亮如水面） |

### 3.4 光源 / RIS

| 锚点 | 铁律 |
|---|---|
| `lighting.slang` 头注 | **INDIRECT PASS ONLY**——primary pass 不着色 |
| `lighting.slang` `risInitial` | RIS 估计量 `f(y)·W` 对任何严格正目标无偏——目标用降维 BSDF、幸存者用完整 Disney 是**刻意且无偏**的，不要「修」 |
| `lighting.slang` 成本注释 | M=8 时候选行走值 5.9ms/18ms 帧；成本在**依赖加载深度**不在分歧（强制相干只回收 1.3ms）。预采样池是 ReSTIR 整合的一部分，现在不做 |
| `world.rgen.slang` 太阳 MIS | **两个太阳是美术选择**：画的日盘 16.7°（布景，权重恒 1）、NEE 光源 0.6°（方形，`payload.f0` 走辐照度因为辐射亮度会爆 half）。**delta 路径给光源权重 0**（教科书是 1）——否则水面每个波面爆炸。这也是该改动碰不到镜面的原因 |
| `UNWEIGHTED_SPEC_ALPHA_FLOOR` | 发光体在近镜面上唯一的方差护栏，**明确标注非物理**。解除条件=ReSTIR。任何叠在它上面的新逻辑都要先问这一句 |
| `RtLightCollector` | 一发光 quad 一矩形光；quad k = 三角形 2k,2k+1 = 顶点 4k..4k+3 的关系是 `emit()` 必须维持的不变量；辐射=包围矩形均值 ⇒ 总功率守恒 |
| `RtLightHierarchy` | GPU 记录 32B 的理由是 cache line；**不要存可反推的量**（面积） |
| `world.rgen.slang` 发光门 | 不在光 buffer 里的发光体永远 gather（逐位等于无 NEE 路径，无能量损失）；`PAYLOAD_EMITTER_IN_LIST` 防双计 |

### 3.5 天空 / 大气

| 锚点 | 铁律 |
|---|---|
| `RtSky.java` | LUT 是**链**（transmittance → multi-scatter → sky-view → froxel），顺序封在 `recordBakeIfNeeded` 里「没有调用者能弄错因为没有调用者能看见」。一次性烘焙是**性质不是缓存**：太阳进入的是采样处不是烘焙处 |
| `sky_view.comp.slang` | 唯一逐帧的表（依赖太阳），紧挨着「其他表烘一次」的坚持大声写明 |
| `atmosphere_lut.slang` | 相位函数**留在 LUT 外**（Mie 前向尖峰窄于方位轴分辨率；两相位只依赖视线-太阳角，因子化精确）；binding 与数学分离的理由（bake 的描述符集只有一个 binding） |
| `world.rmiss.slang` | miss 里**零维度分支**（M14 原则）：LUT 是唯一接口。日月盘门控：镜面弹射后开、漫反射 NEE 顶点后关（防萤火虫双计） |
| `RtComposite.skyPush` | 推**未染色峰值**，大气染色在 GPU 逐消费点做（`dyeCelestialLight`）——「一份实现而不是两份手工同步」（F5 收口后的形态） |

### 3.6 Wavefront / 追踪

| 锚点 | 铁律 |
|---|---|
| `world_common.slang` 顶部 | `Ptr<T, …, Std430DataLayout>` 的布局参数**承重**：裸 `Ptr<T>` 拿 natural layout（`WorldPush.sunDir` 200 vs Java 写的 208 = 静默错位）。这个 alias 是唯一的布局开关 |
| `world_common.slang` | 64 位设备地址全部住 inline push lanes：「加新 buffer 只查一个结构」 |
| `trace_ser.slang` | payload 在 SER 前后**构建两次**而非跨 `ReorderThread` 携带（72B 钉在寄存器里跨过 SER 必须溢出的那个点） |
| `trace.slang` | 三个 cull-mask 位；`CULL_SELF` 独立成位的理由：「对次级光线可见」与「对从眼睛发出的次级光线可见」是两个问题，第一人称身体是唯一分歧实例 |
| `world.rgen.slang` | 段积分在天空逃逸 break **之前**（面向天空的段是环境介质最长的段——M5 修的 break-before-attenuation） |
| `segment.slang` | 第二条 Fresnel 分支是**数据不是第二份内联 tracer** |
| `world.rahit.slang` | radiance 与 shadow 光线共用同一 Payload ABI（Vulkan 要求）；shadow 复用 `albedo.rgb` 当累积透射率、`hitT` 当最近水面交点 |
| `guides.slang` | **PRIMARY PASS ONLY**；「Never let a TIR reflection become ordinary diffuse/depth」 |

### 3.7 实体 / 粒子 / overlay

| 锚点 | 铁律 |
|---|---|
| `RtEntityCapture` | 实体累加器与地形 `SectionMesh` **同布局**——上传+BLAS 路径逐字复用 |
| `RtEntityTextures` | 槽位按**解析后的 image view** 键控，不按 `RenderType`（energySwirl 每帧新建 RenderType，按它键控每帧漏一槽） |
| `RtCuboidEmitter` | 模板缓存按根 `ModelPart` 键控（`Model.Simple` 包装器逐次提交，按它缓存每帧漏一棵树）；完整有序树验证失败则不写、调用方回退 |
| `RtEntities` | 名牌不进 RT mesh（billboard 每帧毁掉 rigid-reuse/MV）；假 blob 阴影正确丢弃（RT 有真阴影）；`isInvisible()` 整体跳过 |
| `RtParticleCapture` | 粒子颜色作 raw albedo 直通，**vanilla lightmap 刻意不烘进**（RT 自己打光）；相机相对坐标经每帧 offset 转 rebased 空间 ⇒ TLAS identity |
| `RtGlowOutlineFeature` | 「Never makes the entity itself emissive」——glow 是光栅轮廓 |
| `RtWorldOverlay` | overlay 必须画在 display 分辨率（细线过不了 DLSS-RR）；feature 从共享池拿 scratch，**never own one-off pools** |
| `RtBlockOutlineFeature` | 遮挡用 inline `rayQueryEXT` 打 TLAS，不用深度缓冲（`gDepth` 在 RR 内部分辨率） |

### 3.8 平台 / 构建 / 配置 / 诊断

| 锚点 | 铁律 |
|---|---|
| `Platform.java` | `get()` 抛异常**承重**：`FluoriteConfig.resolveConfigPath()` 靠 catch 它回退相对路径，JUnit 才能脱离加载器跑。「Do not soften this into returning null」 |
| `fabric/build.gradle` `verifyCommonIsLoaderAgnostic` | 字节码扫描而非 import grep（接口注入无 import，F6）；allowlist 空且「meant to stay that way」；真正的证明是 `:neoforge:compileJava` |
| `developer_guide.md` | **slangc ≥ 2026.14 硬要求**（四参数 `Ptr` 形式；旧版要么报 39999 要么静默错位）。Vulkan SDK 自带的 2026.1 太旧 |
| `-Xss16m` | 必须是直接 vmArg 不能走 `JAVA_TOOL_OPTIONS`（渲染线程在 VM 解析该变量前定容；NGX init 栈溢出且栈迹无用） |
| `FluoriteConfig` 旋钮退役规则 | 值定了之后必须二选一：要么删旋钮要么留着并写明为何保留。「旋钮在用途完成后留在 UI 里」是禁止态 |
| `FluoriteConfig` | 「关档必须等于已发布行为，否则 A/B 无意义」（M7 经验，适用于每个开关）；`-Dfluorite.*` 扁平命名空间与 TOML 嵌套**刻意独立** |
| `RtVideoOptions` | 只收每帧重读的设置；要重建资源的留在 `-D`/TOML 面（DLSS 档位是唯一例外，理由在原地） |
| `RtTerrainDigest` | 「Do not trust a dump whose `stableDumps` is 0」；只比两次运行 `builds==1` 的 section；流体世界**不存在全局稳定** |
| `RtGpuTimers` | 其他计时器量的都是录制线程 CPU 时间，「for a path tracer says nothing at all」——性能预算必须用时间戳查询 |
| `run/vk_layer_settings.txt` | 没有它验证层静默——「a validation run that prints nothing looks exactly like a clean one」 |
| `RtDeviceBringup` | `fluorite.rt` 在 `vkCreateDevice` 读一次，运行时翻配置不生效，重启才行 |

---

## 4. 方法论

### 4.1 性能测量（唯一有效流程）

1. **GPU 时间戳是唯一真相**（F7：CPU scope 对 path tracer 毫无意义——`traceIndirect` CPU 侧 5µs vs GPU 毫秒级）。看 `gpu.tracePrimary` / `gpu.traceIndirect` / `gpu.skyBake` / `gpu.visBake` 等。
2. **同会话比值法**（F10）：绝对毫秒不可跨位姿引用（M4 基准位姿没记录）。两侧同一存档、固定位姿、不碰输入，比中段固定窗口（如第 4000–8000 帧）的中位数**之比**；门槛写倍率不写毫秒。实测离散度 <0.1%（F9 的 ±8% 噪声底只在相机会动时成立）。
3. **运行时开关优先于 git checkout**：`scatter-source`/`segment-source` 这类同会话 A/B 连构建差异都消除，比 F10 更干净。能用开关做的对照都该优先用开关。
4. **分辨率显式声明**（F8）：`-PbenchWidth=1920 -PbenchHeight=1080`；开发窗口默认 427×240 渲染，不声明的数字全部作废。DLSS 档位按缩放比核对不信标签（F11：quality=0 是 Performance=0.5×，不是 Quality=0.667×）。
5. **采集必须记录完整配置快照**（M9 教训：`subsurface-mode` 不同差 3.5ms，差点报成回归）——世界、位姿、分辨率、全部相关 TOML 值、事件数、窗口口径。
6. **成本不测就会记错**（M8）；**计划里写作缓解手段的定量性质必须先测量或标注未测**（F13：「近乎线性」被引用多次、实测饱和，缓解方案是空的）。

### 4.2 已知成本账本（含口径）

| 项 | 数字 | 口径 |
|---|---|---|
| M4 基准 `gpu.traceIndirect` | 17.394 ms（中位） | 1920×1061/RR Performance/固定出生点——**位姿未记录，绝对值不可比** |
| M6 解析高度雾 | +0.622 ms | 同上会话 |
| M7 Disney 全量 | 1.283× | 同会话比值，10.647→13.662 |
| M8 SSS | thin 1.342× / 游走@4 1.612×、@2 1.567× | 门槛 1.5×=22.15ms；事件数杠杆**饱和**（前 2 事件 3.33ms，第 3、4 只加 0.66ms） |
| 可见性网格烘焙 | 0.072 ms | 64×32×64，每 cell 2 条光线（26 万条） |
| froxel 烘焙 | 0.137 ms | 64×36×64（14.7 万条）；同为一列一线程时曾比 per-cell 慢 18× |
| 雾太阳阴影线 | 2 条 ≈ +2.0±0.2 ms（+13%）；1 条≈一半 | 同会话翻开关；1 与 2 视觉难分 ⇒ 默认 1 |
| M9 无条件 CPU（天光网格时代） | ≈0（测不出） | `bench` 世界；该网格已于 M15.0 删除 |
| **M9 水段散射本身** | **从未测量** | `bench-water` 两机位未建——M15 验收时首采（§7） |

### 4.3 A/B 与验收纪律

- 每个开关的**关档必须等于已发布行为**（否则 A/B 没有对照意义——M7 把 MIS 关档做成带 floor 的已发布行为才让四条验收成立）。
- RR guide 回归协议（R5）：固定场景固定时刻，(a) 参考=RR 关+spp16+maxBounces8，(b) RR 开+spp1；每个子里程碑各跑一次**不批量**。guide 回归表现为 (a)(b) 之间新出现的涂抹/ghosting/亮度偏移。
- 材质 A/B 前**先确认写的字段真的生效**（M7 教训：`base.roughness` 被 LabPBR 纹理静默覆盖过）。
- lang 11 份（`assets/fluorite/lang/`）批处理更新，不逐里程碑零敲碎打；每个选项要 `<key>` 和 `<key>.tooltip`。

### 4.4 水的基准（M9 欠账，M15 起偿还）

MC 相机是**点**，不存在「半潜」机位。两机位分开采：(a) 完全潜入、视线朝水面（长水段+折射出水）；(b) 贴水面上方俯视（入水折射+散射）。`tools/bench-world.sh --adopt bench-water` 建立主副本。`bench` 世界继续测无条件开销（无大水体，水分支不执行——它对水什么都证明不了，别再拿它当「无回归」证据）。

### 4.5 归因纪律（F12——七轮 M9 的核心产出）

**代码里错的东西和用户看到的现象是两个集合，交集靠隔离实验建立，不能靠推理宣称。** M9 七轮里前六个「根因」全是读代码读出来的（其中两个还是真缺陷——修是对的，但宣布它们是根因是错误归因），唯一站住的结论来自 `SCATTER_SOURCE` 四档 + `SUN_SHADOW` 开关的判据链。规程：
1. 先设计**判据表**（每个观察结果指向哪个子系统），后动手；「不要先讲机制」。
2. 隔离开关現役清单：`water.scatter-source`（both/sun/sky/none，none 保 σs 只去 in-scatter——把「太亮」和「太浑」分开）、`volumetrics.segment-source`（both/froxel/marched/none）、`volumetrics.sun-shadow-rays=0`、`volumetrics.visibility-cell-size=0`（可证明 no-op）、debug view 8–19。
3. 修掉顺手发现的真缺陷**不等于**结案；结案标准是判据表闭合。

### 4.6 跨加载器验证

两个 run 目录每次运行都改写存档——比对前必须从纯净主副本还原双方；只比 `builds==1` 的 section；`common/` 纯净性靠字节码扫描 + `:neoforge:compileJava`。全流程见 `docs/PLATFORM_NOTES.md`（那里的规则：**没列出的差异都是 bug**）。

---

## 5. 工具手册

| 工具 | 用法 | 说明 |
|---|---|---|
| `tools/bench-world.sh [name]` | 采集前还原基准世界（默认 `bench`）；`--adopt [name]` 把当前存档提升为主副本 | 主副本在 `run/bench-master/`（未跟踪）。**世界名是参数**：采集只测场景跑到的代码 |
| 基准采集 | `./gradlew :fabric:runClient -PbenchWidth=1920 -PbenchHeight=1080 -PbenchWorld=<name>` | 三个 -P 缺一不可（§4.1）；NeoForge 同旗标 |
| `profileMinecraft.ps1` | `-TargetPid`（0=自动找 MC JVM）`-RecordingName` `-Settings` | JFR 包装；输出 `run/jfr/<name>-<时间戳>.jfr`；交互式（Enter 停止） |
| `runClient.ps1` | 直接跑 | 停 daemon、设 ZGC 等 JVM 参数、强制 `--graphicsBackend VULKAN` |
| frameStats | `-Dfluorite.rt.frameStats=true` | CSV 到 `<gameDir>/rt-frame-stats/frame.csv`；含 GPU 时间戳 scope；hitch 线=1.5× 滚动中位 |
| 验证层 | `-PvkValidation` | 挂 `run/vk_layer_settings.txt`（sync+core）；默认关（开着基准全废） |
| 诊断键 | `diagnostics.heavy-crash-diagnostics` / `terrain-digest` / `terrain-digest-sections` / `cull-trace` | digest 摘要 vs 逐面 cull 追踪是两件仪器 |
| debug 视图 | `composite.debug-view`（视频设置→诊断） | 0–7 既有 guide/材质视图；**8** 体积 in-scatter 原始值 · **9** 段（逃逸/长度/是否水）· **10** 水太阳阴影线 · **11** 焦散色差×20 · **12/13** 透射/多重散射 LUT 对照 · **14/15** sky-view LUT/参照 march · **16** 光染色 · **17** froxel · **18** 可见性网格（R 太阳 G 天顶）· **19** 网格 vs 射线真值示波器 |
| 隔离开关 | §4.5 清单 | 全部在视频设置可翻，同会话 A/B 首选 |
| shader 构建 | `compileShaders`（SPIR-V+spirv-val；`world.rgen` 编两份含 SER 变体）、`generateShaderRecords`（反射 JSON→Java ABI 记录） | **slangc ≥ 2026.14**；工具解析顺序 `-P<name>Path` → 环境变量 → `$VULKAN_SDK/Bin` → PATH；独立 toolchain 在 `F:\MC\Shader\tools\slang-2026.14` |
| NGX | `-PdlssSdk`（属性优先于 `DLSS_SDK`——daemon 缓存环境，报错会叫你 `--stop`）、`-PngxVendorConfig=rel/dev`、`-PngxPlatforms` | shim 构建见 `docs/developer_guide.md` |
| 跨加载器 | `verifyCommonIsLoaderAgnostic`（挂在 `check`） | 字节码常量池扫描 |

注：`compare-digest.sh` **不存在**（旧计划提过）；PLATFORM_NOTES 描述的是手工 diff 两份 dump。

---

## 6. 参考项目政策（HPWater / HPVolumeCloud）

### 6.1 净室约束（全程适用，任何一条单独成立）

`F:\MC\Shader\Reference` 下两个参考实现**只学思路、不搬代码**：

1. **法律**：两仓库都声明「portions derived from Unity HDRP」，HPWater 的文件直接声明在 `UnityEngine.Rendering.HighDefinition` 命名空间/HDRP 包内部——作者无权以 MPL/MIT 覆盖 Unity 代码，且哪些文件属于哪类**无逐文件标注**，所以连「看起来像它的写法」都要避开。
2. **技术**：两者都是光栅延迟管线（gbuffer/RenderGraph/时域重投影/屏幕空间折射），Fluorite 没有任何光栅 pass，这些代码对我们没有可用部分。
3. **正确性**：参考实现里有相机依赖 bug（云密度按相机水平距离下移、层序按相机高度判断），照抄会毁掉反射。

**数值常数一个都不能带**（R20）：HPWater 的 `HenyeyPhase` 漏 `1/(4π)`，整套参数围着 ~4π 能量误差调出来（照抄亮约 12×）；`βR×1e6`、`SCATTER_DECODE_SCALE 0.01`、`min(1/|V.y|,4)` 全是凑数；HPVolumeCloud 的 `Intensity` 量纲是 m⁻² 的凑数常数。**只抄结构，常数一律自己标定。** `THIRD_PARTY_NOTICES.md` 保留思路来源致谢（成本为零，说明推导出处）。

### 6.2 政策升级（2026-08-02，用户指示）

**参考的对象是实现方法与物理选择，绝不照抄任何部分。** 每次借鉴参考项目落地一个子系统前，必须在本文档写出「HP 做法 vs 本项目做法」对比（异同、处理思路差异、推荐及理由、物理差距、性能代价），**交用户裁决后**才动工，结论记入 §10。

### 6.3 HPWater 定案表（M9 已完成的对比，结论生效中）

| 议题 | HPWater | Fluorite 定案 |
|---|---|---|
| 介质参数化 | `absorptionColor`/`scatterColor` 两个独立光谱创作量（逐水面画进 GBuffer） | **采纳思路**：σa（群系色调驱动+`absorb-override` 逐通道）与 σs（全局创作量 `scatter-r/g/b`）独立；「浑浊度」单旋钮已废（它数学上只能把水推白） |
| 积分 | 6 步指数分布行进 + 逐采样点 shadow lookup | 闭式深度衰减积分 + 3 条分层**抖动**阴影线（RT 的解法，不是光栅的） |
| 多次散射 | `lerp(phase, 1, smoothstep(0,0.5,albedo))` | **重推导**：`g_eff = g^(1/(1−ω))` + 扩散衰减（教科书 K，钳物理域） |
| 环境项 | 烘焙间接光 × 强度 | 上射线深度 + 可见性网格开阔度 + 深度衰减（M16 起源换 LUT 辐射，D1-A） |
| 焦散 | 光子抛撒 + 级联图集 + à-trous 降噪 | **不向 HP 靠**（定案）：解析雅可比（3 次波场求值/阴影线，无噪声无缝、逐射线天然进反射折射）+ RGB 三 eta 色散 |
| 交互水仿真 | 2D 波动方程 + 海绵层 + 两次俯视正交渲染取障碍 | 方程与海绵层采纳；障碍改用 **TLAS 俯视短光线**（光追渲染器天然能做）——见 §8.2 |

### 6.4 HPVolumeCloud 对比表（M11 动工前逐项过一遍，「请示」项须用户裁决）

| 议题 | HPVolumeCloud | 本项目方向（D1–D5 决策后） | 状态 |
|---|---|---|---|
| 承载结构 | 光栅 froxel + 屏幕空间 + 时域重投影 | 纯光线函数、世界锚定、进反射（R18 硬规则：`cloud.slang` 禁止出现相机位置） | 已定 |
| 相位 | 双叶 HG（前向 ~0.85 + 后向 ~0.3 出银边） | 采纳结构，常数自定 | 已定 |
| 多重散射 | phi_fwd 扩散项（推导文档正确、实现两处偏离）+ Hillaire 三倍频 | 采纳思路**重推导**：边界可信度 `C_top·C_bottom` 逐源点求值（参考实现提到接收点省 5 倍是错的）、`C_iso` 从**受光边界**量光学厚度（参考参数化反了）、`1/(4πr)` 的 4π 补回、Intensity 量纲重标定 | 已定（D5：云专用近似，不外推到水雾） |
| 步进 | 自适应步长 | 步长上限 `(rangeStart+dist)/8` **从光线起点量**（对二次光线优雅退化）+ 廉价探针跳过空段 | 已定 |
| 降噪 | 时域重投影 | DLSS-RR + 逐帧去相关（蓝噪/抖动） | 已定 |
| 云的挂载点 | 独立 pass | 统一 Medium 的 ambient 非均质分支（D2 框架的第一个非均质客户），在天空逃逸 break 之前 | **落地时请示细节** |
| 云的光照源 | 常数 ambient + 太阳 | LUT 辐射（D1-A 档）或散射顶点 NEE（D1-C 档）；`upwardAO = exp(−sunPathOD·sinθ·k)` 免费环境遮蔽可保留 | **落地时按质量/成本请示档位** |
| 密度模型 | 2D 天气图（覆盖度/类型）× 3D 噪声（基础+侵蚀）× 高度剖面 LUT | 采纳结构、常数自定；双层共用球壳；3D 噪声启动烘焙（`cloud_noise.comp.slang`），不逐步过程噪声 | 已定 |
| 二次光线 | n/a（屏幕空间进不了） | 默认烘进 sky-view LUT；`VOLUMETRIC_IN_REFLECTIONS` 走削减档真行进 | 已定，两路都要 A/B |

phi_fwd 推导要点（落地时照此重推，勿翻参考代码）：τ≫1 时 RTE 退化为扩散方程，格林函数 `e^{−κr}/(4πr)`；`κ = √(3σaσtr)`，g→0 时 σtr=σt、σa=(1−ω₀)σt ⇒ `∫κds = OD·√(3(1−ω₀))`（ω₀=0.999 时常数 ≈0.055——**扩散衰减就是光学厚度乘编译期常数**，搭在本来要跑的太阳自阴影行进上）；源项衰减用 `T_abs = exp(−(1−ω₀)τ)` 而非 `exp(−τ)`（离开直射束的光子只是开始游走，只有吸收真正移除它——τ=20 云心仍亮的机制）。phi_fwd 各向同性 ⇒ 对给定世界位置视角无关，主光线/反射/折射可复用同值。云是解析行进不产生散射顶点，phi_fwd 就是多重散射模型本身**不存在双计**；若 M8 游走将来进云体，按路径深度门控。

---

## 7. 体积介质统一路线图（M15–M20）

> 依据：2026-08-02 决策 D1–D8（§10）。**推荐排序**：M15 → M16 →（M19、M20.1–20.2、M18 可并行穿插）→ M17 → M20.3 → M11 → M12 → ReSTIR → M14 收尾 → 首个正式版 → 可选 feature 评估（§8.6）。
> 理由：M15 是云与 M17 的地基；M16 小而独立；M19/M20 前半与体积无关可交错出可见成果；M17 引入新成本须在云之前定型体积采样纪律；ReSTIR 前 M18 只收集（D3）。
> 每个里程碑标注的 **⚖ 请示点** 按本文档头部硬规则执行。

### M15 介质统一重构（D2：接口统一 + 估计器分派）

**15.0 卫生（先行独立 commit，视觉 diff 应为零）**
- 修 `integrateSegment` ambient 分支的 `seg.inScatter = acc.inScatter` → `+=`（「既吸收又 ambient」介质入栈时静默丢弃 enclosed in-scatter——云的前置）。
- 删除死代码链：`RtSkyLightGrid` 每帧 CPU 扫描+上传（Java 构建/环缓冲/`pc.skyLightAddr`/`worldPush.skyLightOrigin/Dims`）+ shader 端无人调用的 `skyLightFactor`/`skyLightCell` + `sky_froxel.comp.slang` 未读的 `skyLightAddr`。`WorldPushConstantsData.BYTE_SIZE` 会变——同步 layout test 与余量注释。
- `visibility-cell-size=0` 时跳过可见性烘焙 dispatch（现在无条件 131k 条射线打一个点）。
- 注释与行为对齐批处理：bit15 「skip the shadow ray」措辞（实际只弃透射率、射线仍发——`waterHitT` 要用）；`volume.slang` 「Character for character the froxel's source」（已不真）；`world_primary.rgen` 「this pass integrates the camera prefix segment」；froxel 各处「32 slices」陈旧数字；`world_common.slang` 「bits above 11 free」（12/13 已用）；`lighting.slang` 「Light48」；`FluoriteConfig` `Water.SUN_SHADOW` 整段陈旧文档。

**15.1 Medium 采样接口 + 统一体积阴影采样器（2026-08-03 已实现，待游戏内验收）**
- `medium.slang` 收拢为 `mediumSigmaT` / `mediumScatterAlbedo` / `mediumPhaseG` / `mediumProfile`；一个 profile 同时选择 estimator 与 source adapter，避免两组浅查询产生一致性负担。`mediumDensityAt` 留在 Implementation 内部。`integrateSegment` 仍是唯一外部 Interface：**均质封闭介质走精确闭式路径**，非均质 ambient 走解析高度 march。
- **统一体积阴影采样器**：水与雾共享固定光学深度分层（τ 步长 1.25）、段内 jitter、`CULL_SECONDARY_NO_SELF`、shadow trace 与按用途 rehash 的 seed 纪律；均质 τ→distance 与 ambient τ→distance 是两个内部 adapter。
- D9 明确：这是分层近似，不宣称严格无偏。层内按距离均匀采样、使用闭式 view-path 权重；严格 `f/pdf` 修正留到 M17，因为它会改变亮度与噪声。ambient 边界用 8 次二分反演并复用相邻边界；默认 1 ray 不反演。
- D12 审计结论：水的 sun-shadow 关时仍保留最多 3 条。每层的 `waterHitT` 都在测自己的 source path；压成 1 条会在不平水面、洞顶与不同水体高度下引入衰减误差，不属于中性重构。

**15.2 froxel/marched 源对齐（2026-08-03 已实现，待游戏内验收）**
- 新建纯数学 Module `volume_source.slang`（无 bindings/globals/rays），两种 shader stage 共用高度积分、扩散衰减、HG 与 `evaluateAmbientRadianceSource`，避免 descriptor layout 相互污染。
- 按 D10 把 marched 已有的局部雾太阳自衰减补进 froxel；froxel 的 direct path = atmosphere LUT × local fog，raygen 的 `WorldPush` 已经 atmosphere-dyed，所以只乘 local fog。无新增射线，增加解析 ALU/exp。
- `fogScatter` 两边统一解释为 single-scattering albedo：froxel 改为 `σs=fogAlbedo×fogSigmaT`；行星大气仍作为 froxel-only 的附加介质项，理由是它覆盖相机前缀的 aerial perspective，注释明确。
- **临时运行时烟测（非性能验收）**：1920×1080、`bench`、当前配置下稳定区间 `gpu.froxelBake` 中位数约 0.17–0.18 ms；仅证明新增解析衰减未出现数量级异常。没有同位姿、同会话的改前 A/B，禁止据此声称“无回归”或计算倍率。
- ⚖ **M15 完成前请示：`volumetrics.intensity-scale` 的物理语义。** 它仍允许 0–10，而 M15.2 后 `fogScatter.rgb = tint × intensity` 已是反照率；任一通道超过 1 就违反能量守恒。默认 1.0 在当前 tint 下有效，但高档不有效。候选：**A** 将有效反照率逐通道钳到 `[0,1]`，并把 UI/tooltip 改成物理反照率倍率（严格守恒、GPU 额外成本≈0；高档会饱和，旧的过亮观感不能保留）；**B** 把 intensity 从反照率移到 source-radiance gain（介质系数合法、GPU 约多一次 source 乘法，但总辐射仍是艺术性增益，不满足严格能量守恒）；**C** 拆成受限 physical-albedo 与显式 `artistic-gain` 两个旋钮（默认路径守恒、非物理档诚实标名；需配置/UI/lang 与一个标量通路，射线成本不变）；**D** 删除 intensity 旋钮（最严格、最少维护，失去用户亮度控制）。若“确保能量守恒”是硬约束，推荐 A；尚未获用户裁决，代码保持现状。

**验收**：`segment-source` froxel/marched A/B 在前缀深度处无缝；`visibility-cell-size=0` 的 telescoping 归零测试仍成立；`RtPathSegmentLayoutTest` 仍 48；同会话比值 `bench` ≈1.0×（重构应中性）；**顺带偿还 M9 欠账：`bench-water` 两机位首采**，数字进 §4.2 作 M16/M17 的基线；水观感与 M9 收尾一致。

### M16 散射源 Radiance 化 — LUT 档（D1-A）

- 水天空项：`waterAmbient = 0.05×lightRadiance` 退役，源改为与雾同源的 LUT 辐射（`sampleMultiScatter×SUN_INTENSITY` + sky-view LUT 半球均值项；均值经小型 reduce pass 或推送常量——实现方式落地时定）。已有的上射线深度 + 可见性网格开阔度门控保留。
- 雾天空项：`fogAmbient×sunT` 残余常数同规则退役（`fogAmbient.w` 是 SSS 厚度，字段不能删只清 xyz 用途）。
- ⚖ **请示点**：`water.ambient-scale`/`AMBIENT_FOG_FRACTION` 按旋钮退役规则处置（保留为倍率 1.0 还是删除）——带观感对比图请示。
- **可观察改进**：夜晚/黄昏水下亮度随 LUT 正确衰减（旧常数是太阳峰值比例，日落后偏亮）。
- **验收**：全天时段扫描 A/B（黎明→正午→黄昏→夜，R9 同款——NEE 光色敏感的改动会在几个里程碑后被归错因，所以当场留档）；debugView 8/10 对账水雾量级；lang 11 份随 UI 变化更新。

### M17 体积散射顶点 + NEE — 质量档（D1-C）

- 新开关（如 `volumetrics.scatter-vertex`，默认关）：每段按 τ 均匀逆采样一个散射距离（雾的高度积分解析可逆——采样 τ 均匀即按 σt·T 重要性；水均质更简单），在散射点做：
  - 太阳 NEE：1 条阴影线，相位加权（与 15.1 采样器共用纪律）；
  - **volumeNee**（发光方块首次照亮水与雾）：M=1，复用 `findLightGridCell`/`selectLightGridLight`/`proposalPdf`/`lightRadiance` 等选择机制，目标函数换相位——这是 M6 就预留的设计，**不违反 ReSTIR 前向约束**（无复用、无解析 MIS 权重、不动 `evalSampleContrib` 热路径）。挂独立开关默认关。
- ⚖ **请示点**：相位采样 vs 光源方向采样之间是否加 MIS——按落地后的实测噪声带图请示；默认档位（关/仅太阳/太阳+发光体）按实测成本请示。
- **成本闸门**：`bench` + `bench-water` 两机位实测（粗估 traceIndirect +2~5ms），数字报用户后定默认档与质量分级。
- **验收**：岩浆/火把旁的水与雾出现带遮挡的光晕；关档逐位回到 M16 行为；RR 关时误差表现为噪点非块状（§3.2 判据）。

### M18 光源收集层（D3：只收集不采样）

- 新 `rt/light/RtDynamicLights.java`：收集**手持物品光**（主/副手 `BlockItem→Block.getLightEmission`）、**实体附着光**（简表：燃烧实体、发光鱿鱼等）、**发光粒子聚合光**（按空间 cell 聚类为代表光，依赖 M20.1 的发光判定）。产出与 `Light` 32B 同布局的记录 + 动态标记位（`section` lane bit31 空闲）；类型枚举与区域光扩展的落位设计写成注释留给 ReSTIR。
- 上传为独立小 buffer 或 arena 尾段，每帧重建（数量 <百，成本可忽略）；**不接入 alias/grid/采样器**——渲染逐位不变。
- ⚖ **请示点**：S3 死工作处置——`RtLightCollector` 逐帧算好又被 `RtLightHierarchy` 丢弃的 UV lanes（7/11/15/19：`materialId`/`uvHu`/`uvHv`/`uvCenter`，本为 exact-Le fetch 准备）：停算省 CPU，还是保留待 ReSTIR。
- **验收**：新增 debug 视图列出动态光数量/位置；画面 bit-identical（因为不采样）。

### M19 实体 overlay 同步（D6：per-prim aux lane）

- 数据通路（现状四层丢弃逐层接通）：`RtEntityCollectorBase.submitModel` 解码 `overlayCoords`（vanilla 语义：红 lerp 轴 hurt + 白闪轴）→ collector 状态；`RtEntityCapture.addVertex` 已存在但被忽略的 `overlay` 参数接通 → 打包 overlay RGBA 进 `Prim.aux0`（现恒 0，**零结构增长**，实体 mesh 每帧重建数据天然新鲜）；`RtCuboidEmitter.emit` 增加 overlay 参数（快路径覆盖绝大多数生物）；`world.rchit.slang` 实体分支 `albedo = lerp(albedo, overlay.rgb, overlay.a)`。
- `submitFlame` 实现：火焰层捕获为真实发光 cutout 几何进实体 mesh（`Prim.emission=1`，火 sprite 动画来自方块图集）——火自动进反射/GI。
- 冰冻（powder snow 覆盖层）走同一 overlay/额外层机制；隐身保持整体跳过（「隐身生物穿甲不可见」记为已知简化）。
- ⚖ **请示点**：附魔 glint 的近似方案（albedo/emission 调制 vs 完整双层滚动 UV——后者列为可选 feature）带效果图请示。
- **验收**：受伤红闪对照 vanilla 截图；着火实体的火在水面反射里可见；无 overlay 时逐位不变。

### M20 粒子完善（D4-A：几何路线分步）

- **20.1 发光**：粒子类型→emission 表（`flame`/`soul_fire_flame`/`lava`/`end_rod`…）+ 仅对表内类型的贴图亮度启发（遵守「eligibility external」纪律，§3.1）；`world.rchit.slang` `MATERIAL_PARTICLE` 分支允许 emission（现硬编码 0），值走 Prim emission lane（粒子路径现写 0）。
- **20.2 半透明**：`world.rahit.slang` 把 `PARTICLE_BIT` 纳入 stochastic alpha（现仅 `ENTITY_BIT`，粒子落在二值 `ENTITY_ALPHA_CUTOFF=0.1`——烟雾硬边的根因）。
- **20.3 mask 分步开放**：先让粒子进阴影线 cull mask（烟雾投影），⚖ **请示点**：实测 any-hit 成本后带数字请示是否继续开反射/GI 位；`MAX_PARTICLES=1024` 上限重评。
- **20.4** 发光粒子聚合喂 M18 收集层（被采样等 ReSTIR）。
- **验收**：篝火/火焰粒子发光；烟雾边缘软化对照 vanilla；阴影 A/B + bench 数字；非 `SingleQuadParticle` 跳过记为已知缺口（§8.6）。

---

## 8. 其余待办域

### 8.1 M11 体积云（前置：M15；方法对比与请示项见 §6.4）

- **唯一硬规则（R18）**：`traceClouds(ro, rd, tMax, seed)` 内**禁止出现相机位置**——密度场、层序全部逐光线判断（层序逐光线比较两层 `meanDistance`）。验收专项：站在水边看天上的云与倒影，高度形状一致。
- 结构：`cloud.slang` 纯光线函数 + `cloud_noise.comp.slang` 启动烘焙 3D 噪声（构建 glob 已覆盖 `**/*.comp.slang`）；挂統一 Medium ambient 非均质分支（D2）。
- 双层 + 相机邻近淡出：两个乘性项缺一不可——高度邻近（**光线起点**高度 vs 层高）+ 光线邻近（`tPlane` 很小时溶解，处理沿层掠射）。
- 二次光线：默认云烘进 sky-view LUT 一次采样；`Rt.Clouds.VOLUMETRIC_IN_REFLECTIONS` 走削减档真行进（光照步数减半、关侵蚀、更早透射率退出）。两路都要 A/B 截图与开销。
- 出口：主光线与反射中云正确且一致；邻近淡出无跳变；性能门槛按 F10 同会话比值定（旧的 26.1ms 绝对值不可用——位姿不可复现）。

### 8.2 M12 交互水体仿真（前置：M9 ✅）

- 世界锚定 2D 高度场，显式蛙跳线性波动方程；**CFL `c·dt/dx < 1/√2` 必须守住**；三缓冲 ping-pong R16F（`water_sim.comp.slang`）。
- 边界：障碍 Neumann 反射；外圈 ~10% **海绵吸收层**（缺了就是浴缸全反射，参考实现容易忽略的一环）。
- 障碍物取法（与 HPWater 分道）：**每网格单元一条向下 TLAS 短光线**（256²=65k 条/帧，相对路径追踪可忽略）——不需要额外相机/剔除/渲染目标。
- 实体涟漪：`RtEntities` 已逐帧收集实体，CPU 筛水面相交者产出冲量缓冲；**冲量必须钳位**（参考实现钳 0.05——显式积分稳定性护栏不是美术参数）。
- 采样回着色：中心差分→法线，与程序化波谱**世界空间混合**；唯一接入点 `applyWaterWaves`（`water.slang`）——改这一处，主光线/guide/焦散（`causticLanding` 同一条法线路径）自动全部跟上。
- 域跟随：沿用 `waterAnchor` 锚定；**域移动整纹素**否则拖糊（R22，参考实现未解决的问题）。
- 出口：涟漪传播且被方块反射；关闭仿真回到程序化波谱；移动无拖影；涟漪出现在焦散里。

### 8.3 M13 残余

- **3D 噪声雾**：128³ Worley/Perlin FBM 基础 + 32³ 细节，启动一次性烘进 3D 纹理（**不要逐行进步过程噪声**——2080 上 1ms 与 6ms 的差别）。挂 M15 后的非均质 ambient march。
- **froxel 线程映射重审**：一列一线程曾比 per-cell 慢 18×（2304 vs 13.1 万线程）；先加 `gpu.froxelBake` 独立计时区再决策，不拿合并数字做决策。
- 现状说明：froxel **不读**可见性网格（自己发光线）——两套可见性并存是有意的（口径不同），文档化即可。

### 8.4 M14 维度预设 + 配置收尾

- **核心原则：完整大气是代码默认**，只有下界/末地显式退出为 authored LUT；未声明的模组维度自动拿完整大气，**不做 `dimensionType()` 启发式降级**；`world.rmiss` 保持**零维度分支**（差异全部下沉到「谁填 sky-view LUT」：`sky_view.comp` vs `sky_view_authored.comp`）。
- `RtSkyPreset` record（fill/atmosphere/ambient/celestials/stars/fog/双云层）；解析只有两级：`assets/fluorite/fluorite/sky/<dimension>.json`（资源包可覆盖）→ 内置默认=完整大气。加载器克隆 `RtMaterialOverrides` 的形状（format 版本、逐文件 try/catch、校验助手）。
- 天气（rain/thunder 调制云覆盖与雾密度）：预设 record 留钩子**不实现**。
- 大宗每维度数据进 JSON；用户滑条=作用于活跃预设之上的**全局倍率**（UI 保持扁平）。`RtVideoOptions` 规则不破：要重建资源的设置留在 `-D`/TOML。
- 测试：`RtSkyPresetTest` **必须包含「未知模组维度 → 完整大气」断言**（原则的回归防线）。lang 11 份批处理。

### 8.5 ReSTIR 整合（M14 后；约束**现在就生效**）

现在**不要做**的四件事：面光源解析 MIS（ReSTIR 自带广义 MIS——Talbot/pairwise）；自制时域/空域 reservoir 复用；为预采样候选池单独投入；过度加固现有光源网格/alias 表（可能被替换）。现在**守住**：`UNWEIGHTED_SPEC_ALPHA_FLOOR` 不许变承重件（解除条件=ReSTIR 进来一并移除——`ggxD 1e-7` 的老路）。

整合时的好消息（避免当成重写）：「更多光源不更贵」的形状**已经具备**（alias O(1) 选择、每顶点固定 M 候选、幸存者一条阴影线）——ReSTIR 加的是**复用**，MegaLights 加的是把阴影线预算钉死每像素。降维目标/完整幸存者的切分正是 ReSTIR 要的形状。整合时的接入点清单：M18 动态光接入采样；S3 exact-Le fetch（`RtLightCollector` 已备好 UV 帧）；`Light` 记录布局扩展（类型/区域光）；各向异性在 `evalSampleContrib` 的分支在空域复用后会被求值更多次——**重新测，不要假设今天的成本画像还成立**。

### 8.6 可选 feature 区（首个正式版后评估；D4 用户裁定）

- **粒子烟雾体积化（D4-B）**：烟雾/云雾/爆炸尘类粒子把密度注入统一 Medium 非均质场（真体积感：透光、自阴影、雾内相位），火焰类仍为发光几何。依赖：M15 march 路径 + 粒子分类表 + 密度注入网格。**等缺失项补全 + ReSTIR + 首个正式版后再定**（2026-08-02 用户原话）。
- 非 `SingleQuadParticle` 粒子捕获（物品拾取、elder guardian 等，现静默跳过）。
- 名牌 ghost 穿墙显示（现只隐藏，v1 简化）；附魔 glint 完整双层滚动 UV；隐身生物穿甲显示。
- 焦散 `CAUSTIC_MAX` 夹平重审（色散显色阈值 ~100× 的嫌疑，动它会改变焦散观感）。
- 发光体 MIS（ReSTIR 自带，不单独做）。
- NRD + FSR（AMD 可玩性）、LOD（README TODO）。

---

## 9. 弯路与教训档案

> 格式：假设 → 证伪方式 → 教训。被证伪的假设本身是资产。

### 9.1 M9 水体散射（七轮，2026-07-31 收尾）

**参数化三弯路**（全部被用户观察推翻，都是「物理上讲得通」的设计）：
1. 「浑浊度」单标量只加 σs → **数学必然把 albedo 推向 1**：越浑浊只能越白，永远不能暗。O4「白灰难看」由此完全解释。
2. σs ∝ σa（为了反照率「可推导」）→ 常数比=灰反照率；深水极限 σt 从积分约掉、反照率是唯一颜色 ⇒ 每个群系同样乳白。**教训：为非目标属性（可推导性）做的妥协把目标属性（颜色）弄丢了——而可推导性本来就不需要牺牲颜色（σs 全局同样可推导）。**
3. 加深度衰减后仍白 → 根因同上，参数化不改观察不变。
最终形态：σa/σs 两个独立创作量（HPWater 的思路、自己的实现），σs 保持全局保住 48B 记录。

**y≈48 亮度跳变（六个被证伪假设 + 第七次定案）**：①第一人称身体挡阴影线（只对反射里的半截身体成立）②TAA 抖动 ③曝光测光双峰 ④深度积分的水面之上分支 ⑤全屏共用一条相机阴影线（分层后仍跳）⑥`waterAnchor.w` 塌陷 + Snell 光程（真缺陷、修了、但不是跳变根因——**第六次错误归因**）。第七次用 `SCATTER_SOURCE` 四档 + `SUN_SHADOW` 开关的判据链**测出**根因：固定光深的太阳阴影线（二值、无逐帧去相关，跨遮挡边界整屏同翻）。处置：抖动分层化（M13.3 形状），体积阴影本体让给可见性网格+随机射线。**这是七轮里唯一测出来的结论，前六个全是读代码读出来的推断。**

**其他**：水线效果放弃——**MC 相机是一个点**，不存在「半身入水」状态（同一事实否掉「半潜 bench 机位」）；原版水下整屏贴图压掉（光栅时代替代品）；色散物理 1× 不可见（三通道差 <1%，`CAUSTIC_MAX` 夹平是嫌疑），默认 50× 用户目视选定；焦散不向 HPWater 靠（解析雅可比 vs 光子抛撒，§6.3）。

### 9.2 M13.x 体积可见性与光柱（2026-08-01/02）

- 密闭房间雾亮着=弹射段太阳项带相位前向增益无人移除——`SEGMENT_SOURCE` 隔离测出（不是推理出）。修法=世界空间可见性网格（两条光线两个量：太阳/天顶，**不可互换**——山影不许抽走环境光）。
- 光柱不锐的三嫌疑排除法：子步 6→24 无变化（排除采样密度）、关 DLSS 仍溢出（排除降噪器）⇒ 网格带限（1 方块 cell 载不动 9cm 半影）。**锐度=可见性函数的锐度**；工业界每种能画锐光柱的技术，可见性都来自亚分米精度的东西（阴影图纹素或真光线）——体素网格结构上不在名单里。修法=太阳项换逐帧抖动随机阴影线（高频），天空项留网格（真低频）。
- 能量标定：`0.25×太阳辐射`（fogAmbient）与 `0.35×(1−T)`（MULTI_SCATTER_RETURN）两个编造常数在高密度下合成 0.6× 各向同性洪水，淹死 0.16× 的侧向太阳叶——光柱侧面消失、水面爆亮的完整解释。修法=删常数、用物理烘的多重散射表。**教训：两个「temporary」常数各自讲得通，合起来在最该小心的区间最失控。**
- froxel 黑屏：跳过近平面迭代让 alpha 留在初始 0，整屏乘零。**「只想加阴影的改动」也能全黑，边界条件先于功能。**

### 9.3 M8 SSS（成本饱和）

事件上限 4→2 只省 17%（前 2 事件 3.33ms，第 3、4 共 0.66ms）——**「近乎线性」被引用多次从未测量，实测饱和，写进计划的缓解方案在需要时是空的（F13）**。真正的杠杆是「游走被进入的频率」不是「进入后走多远」。处置：`thin` 默认、游走高配。

### 9.4 M7 BSDF

- 材质 JSON 被 LabPBR 纹理静默覆盖（写 0.30 完全看不出磨砂才反查出来）→ **A/B 前先确认字段生效**；若写的是微妙值会被记成「改动无效果」。
- `ggxD 1e-7` 承重史：临时护栏被默认下来变成「移除就炸」，拆掉花了一整轮——`UNWEIGHTED_SPEC_ALPHA_FLOOR` 正走在同一条路上，解除条件已写死（ReSTIR）。
- 两个太阳/delta 权重 0/关档=已发布行为：§3.4。
- 原版电介质粗糙度 0.0025 > `MIRROR_ALPHA_MAX`：照计划字面执行会停用所有玻璃的分裂——**计划落地时的实测修正必须回写计划**。

### 9.5 测量与仪器（F 系列全表）

| # | 事实/教训 |
|---|---|
| F1 | 迁移基线异常有利：官方名编译、无 AW/事件依赖 ⇒ mixin 零成本移植 |
| F2 | 三处 Fabric 事件全部有 mixin 等价物 ⇒ 平台层不需要事件层 |
| F3 | `PackedPathSegment` 16B 量化：+1 uint = 48→64B = +118MB@1440p |
| F4 | `nextRecord` lane 可白拿回（`PATH_HAS_NEXT` 位 + 重算索引）——M9 介质参数的预算来源 |
| F5 | `lightRadiance` 只 GPU 消费 ⇒ CPU 版大气透射删除（已收口：推未染色峰值，GPU `dyeCelestialLight`） |
| F6 | 导入 grep 不足以判迁移完成（接口注入无 import）⇒ 字节码常量池扫描 |
| F7 | CPU 墙钟对 path tracer 无意义（`traceIndirect` 5µs vs GPU 毫秒级）⇒ 时间戳查询 |
| F8 | 基准分辨率必须显式（曾在 427×240 采「基准」且无标注） |
| F9 | ±8% 噪声底只属于会动的相机；固定位姿+同存档 <0.1% |
| F10 | 位姿未记录 ⇒ 绝对毫秒不可比 ⇒ 同会话比值法，门槛写倍率 |
| F11 | DLSS 档位标签错（quality=0 是 Performance）：按缩放比核对不信标签 |
| F12 | （归因纪律，§4.5）代码里的错和看到的现象是两个集合，交集靠隔离实验 |
| F13 | 计划里的定量缓解手段必须先测量或标注未测 |

仪器教训：曝光日志 `now - Long.MIN_VALUE` 溢出为负 ⇒ 永久沉默——「为回答『源是否过亮』而造的仪器整个会话什么都没报，沉默看起来和『没变化』一样」；水系数诊断打印了**被拒模型**的数字（该被对账的那行本身错了）；验证层无 messenger 时静默（`vk_layer_settings.txt` 的存在理由）；归档文件名不带配置 ⇒ 拿游走档比 thin 档差点报回归。

### 9.6 硬件 / 平台

- OMM：**扩展被支持 ≠ 硬件加速**。Turing 走软件路径 AS 构建卡穿 5 秒 TDR（Ada 以下默认全崩）——能力门控按「实际能不能做」（R16，已修；同教训适用于 SER）。
- 摘要比对：流体世界**永不全局收敛**，`builds==1` 是唯一可信集（R17）。
- NeoForge `hidesNeighborFace` 残差：双方各自正确，记录不修（PLATFORM_NOTES 的「列出即非 bug」原则）。

### 9.7 风险登记簿现状（R1–R23）

| # | 一句话 | 状态 |
|---|---|---|
| R1–R2 | NeoForge quad 解码 / SpriteLookup 分歧 | 已按 digest+测试闭环 |
| R3 | 记录悄悄涨到 64B（+118MB） | layout test 钉死 |
| R4–R5 | 粗糙度平方 / RR guide 退化 | 横幅注释+逐里程碑 A/B 协议 |
| R6 | VGPR 占用率台阶 | 升级阶梯：froxel pass → callable → 更紧打包 |
| R7 | SSS 成本超支 | 已发生，thin 默认（§9.3） |
| R8–R9 | MIS/大气收口的亮度漂移被归错因 | 独立 commit + 全天扫描 A/B 留档 |
| R10–R14 | mixin 顺序 / NightConfig / -Xss16m / FG-HDR / RR 前合成涂抹 | 各自缓解在位；R13/R14 在 M13 合成点保持可切换 |
| R15–R16 | TDR / OMM 门控 | 已修 |
| R17 | 摘要永不收敛 | `builds==1` 方法论 |
| R18 | 云的相机依赖 | **前置规则**，M11 出口专项验收 |
| R19 | 云开销 | 二次光线默认 LUT + 削减档 |
| R20 | 照抄参考常数 | 政策 §6.1，常数一律重标定 |
| R21–R22 | 交互水发散 / 域拖糊 | CFL+钳位+海绵层；整纹素移动 |
| R23 | RIS 阴影线绕过雾与焦散 | 已修两半（`visibilityThroughAmbient` + `shadeReservoir` 焦散） |

---

## 10. 决策日志

> 追加式。每条：日期、议题、决策、理由摘要。未来所有 ⚖ 请示点的结论落在这里。

### 2026-08-02 体积介质与渲染补全方向（D1–D8，用户逐项选定）

| # | 议题 | 决策 | 理由摘要 |
|---|---|---|---|
| D1 | 散射亮度 Radiance 化 | **A+C 组合**：先 LUT 辐射统一（M16），再体积散射顶点+NEE 作质量档（M17） | A 近零成本消灭手调常数、水雾同源；C 无偏且让发光体照亮体积，成本 +2~5ms 做成开关档 |
| D2 | 积分器统一 | **接口统一+估计器分派**（M15）：均质封闭介质保留闭式（精确解=快速路径），非均质共享 march；水雾阴影线合并为统一体积阴影采样器 | 统一「接口与采样纪律」而非强行统一「数值方法」；均质闭式在数学上优于 march，为统一二字退化它是纯损失 |
| D3 | 统一光源接口 | **只做收集不做采样**（M18），采样等 ReSTIR | ReSTIR 前向约束（§8.5）明确禁止现在加固采样侧；收集层先落地让数据通路可验证 |
| D4 | 粒子路线 | **几何路线分步现在做**（M20）；烟雾体积化写为可选 feature，**缺失项补全+ReSTIR+首个正式版后再定** | 分步可测可回退；体积化依赖 M15 march 且工程量大 |
| D5 | 多重散射 | **按介质专用近似+统一参数语言**（雾=大气 MS LUT+扩散衰减；水=g_eff+扩散；云=phi_fwd+Hillaire 倍频+双叶 HG） | 三者是 RTE 扩散极限在不同光学厚度区间的近似，各自最准；强行统一降云的质量 |
| D6 | 实体 overlay | **per-prim aux lane**（M19）：`Prim.aux0` 零结构增长，rchit 按 vanilla lerp 语义；`submitFlame` 恢复为真实发光几何 | 语义正确（lerp 非乘法）、成本≈0、不动静态材质 ABI |
| D7 | 文档形态 | **repo 主文档（本文件）+ CLAUDE.md 入口**；四份专题文档保留并被索引 | 编号在 repo 内可解析；专题文档服务特定读者（资源包作者等）不吞并 |
| D8 | 文档语言 | **中文正文+英文术语/符号名** | 阅读顺畅且符号可 grep |

### 2026-08-03 M15 估计器与性能裁决（D9–D12，用户逐项选定）

| # | 议题 | 决策 | 物理与性能理由 |
|---|---|---|---|
| D9 | 统一阴影分层的统计口径 | **固定 τ 分层继续落地，但明确为分层近似；严格 `f/pdf` 延后 M17** | 当前层内按距离均匀 jitter、用闭式 view-path 权重，不严格无偏；现在修正会改变亮度/噪声并增加数学成本，超出 M15 中性重构。多射线雾需 ambient τ 反演；相邻边界复用后最坏约 48 次高度积分，默认 1 ray 为零次反演 |
| D10 | froxel/marched 太阳自衰减对齐方向 | **把局部雾自衰减补进 froxel** | 保留物理正确项；删除 marched 自衰减会让低太阳/密雾产生过亮光晕。新增解析 ALU/exp，不新增射线 |
| D11 | Medium Interface 形状 | **单一 `mediumProfile` 分类；`mediumDensityAt` 私有** | 一个查询同时决定 estimator/source adapter，删除 `mediumSource` + `mediumIsHomogeneous` 两个浅 Module 的隐含一致性；画面不变 |
| D12 | 水在 sun-shadow 关闭时的射线预算 | **仍保留最多 3 条** | 每层 `waterHitT` 测量自己的 source path；只留 1 条虽最多省 2 ray/长水段，但会在不平水面、洞顶和不同水体高度下产生衰减误差 |

**同日确立的硬规则**（见文档头部）：任何方向性决策必须带选项分析（物理差距+性能代价）请示用户后记入本日志。

**当前待请示清单**（动工时逐个触发）：M15 fog intensity/反照率守恒 · M16 旋钮处置 · M17 体积 MIS 与默认档 · M18 S3 死工作处置 · M19 glint 方案 · M20.3 粒子 mask 成本 · M11 §6.4 表中两项「请示」。
