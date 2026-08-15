# Fluorite 开发文档

> Fluorite 的当前架构、规则、危险事项和待办以本文件为准。正文使用中文，代码符号、配置键和文件名保留英文，方便直接搜索。
>
> 已结束的实现过程、实验数据、失败路线和决策依据不在这里展开；从“已取得的成果”进入 [`docs/devlog/`](devlog/README.md) 追溯。GitHub Issues 跟踪仍未结束的问题。

## 1. 项目与当前状态

### 1.1 项目是什么

Fluorite 是面向 Minecraft 26.2 的客户端 Vulkan 硬件光线追踪 mod。它取消原版世界绘制，使用自己的 wavefront path tracer、DLSS-RR/显示管线和光栅 overlay，再把合成结果交回 Minecraft main render target。

- 代码包名：`io.github.dswepm.fluorite`。
- 双加载器：Fabric + NeoForge。
- `common/` 是两侧共同编译的源码目录，不是独立 Gradle 子项目。
- 当前规模（2026-08-12）：166 个 Java 文件、约 36.1k 行；40 个 Slang shader 文件、约 11.2k 行。
- 源自 Caustica，按 LGPL-3.0-or-later 延续双版权；参考来源见 `THIRD_PARTY_NOTICES.md`。

### 1.2 当前基线

远端 `main` 是唯一当前基线；最近完成验收的功能基线为 M23。Fabric 与 NeoForge 共用渲染实现；未跟踪的 `.vscode/` 和 `neoforge/.eclipse/` 属本地 IDE 状态，不应被清理或提交。

测试机事实：

- RTX 2080 Mobile（Turing，8 GB）+ i7-8750H，屏幕上限 1920×1080。
- 正式性能基准必须显式使用 1920×1080，不能让窗口尺寸隐式决定结论。
- SER/OMM 扩展可见不代表硬件高效；能力门控按实际行为，不按 NVIDIA/AMD 名称或扩展存在性。
- DLSS-RR 可用；FG 默认关闭。没有降噪器的 AMD 路径仍是产品缺口。
- Y 轴约定是 `jitter-sign-y=-1`，向 NGX 上报时再次翻转。新增 depth/motion/NGX 代码必须说明处在约定哪一侧。

### 1.3 已取得的成果

每行只说明现在具有什么能力；完整过程由日志保存。

| 里程碑 | 当前成果 | 历史 |
| --- | --- | --- |
| M0–M4 | Fabric/NeoForge 双平台迁移完成，common 纯净性和地形摘要验证流程建立。 | [平台迁移](devlog/M00-M08-foundations.md#m0m4fabric--neoforge-迁移) |
| M5 | 双 pass wavefront、48 B segment ABI 和天空逃逸段体积所有权固定。 | [wavefront 地基](devlog/M00-M08-foundations.md#m5wavefront-渲染地基) |
| M6 | `Medium`/`MediumStack`、统一段积分入口与解析高度雾建立。 | [统一介质起点](devlog/M00-M08-foundations.md#m6统一介质框架的起点) |
| M7 | Disney BSDF、太阳 MIS、各向异性、clearcoat、sheen 和粗糙透射完成。 | [Disney BSDF](devlog/M00-M08-foundations.md#m7disney-principled-bsdf) |
| M8 | 随机游走 BSSRDF 完成，因成本超门槛保留为高质量档，`thin` 默认。 | [BSSRDF](devlog/M00-M08-foundations.md#m8随机游走-bssrdf) |
| M9 | 水体 σa/σs、闭式散射、折射太阳、天空开放度、焦散与色散完成。 | [水体介质](devlog/M09-M10-water-atmosphere.md#m9-最终成果) |
| M10 | transmittance、multi-scatter、三张 sky-view 和 aerial froxel LUT 链完成。 | [LUT 大气](devlog/M09-M10-water-atmosphere.md#m10lut-大气) |
| M11 | 世界锚定低层体积云与单层解析高云、天气云型、自阴影和扩散近似完成；D61 修正体积太阳源的 4π 能量错误；D169–D175 删除 PolyHaven 上层高云、分布/云型改用独立 2D 天气图、步长改按穿越长度并加噪声 mip 预滤波、太阳自阴影按垂直厚度取程、低层改用 curl 域扭曲塑形并按自有时钟演变、厚度旋钮变为上限而每朵云自有深度。 | [云系统](devlog/M11-clouds.md) |
| M12 | 256² 交互水体高度场、CFL、Neumann 障碍、海绵层和实体/方块冲量完成。 | [交互水体](devlog/M12-water-simulation.md#m12交互水体仿真) |
| M12.5 | 水面顶点位移与 BLAS refit、路 3′ 形变覆盖、完整波谱重做完成；FFT 明确不实施。 | [水面真形变](devlog/M12-water-simulation.md#m125水面真形变) |
| M13 | 世界空间可见性、随机体积阴影、3D 结构雾、统一风向和连续天气 forcing 完成并合入 PR #22。 | [雾与天气](devlog/M13-fog-weather.md) |
| M14 | 版本化维度 Provider/preset、地狱本地光/均匀雾和末地 HDR/Kerr 技术 Provider 已合入；末地美术路线等待 Blender 动态 HDRI 素材替换。 | [维度 Provider](devlog/M14-dimension-presets.md) |
| M15–M17 | 水/雾共享介质接口和 Radiance 源；水下前缀、Slang 活动状态和散射顶点完成，水天空开放度跳变修复。 | [统一介质与体积光照](devlog/M15-M17-medium-lighting.md) |
| M18 | 手持、实体火焰、发光模型层和粒子 cell 已统一编码为未绑定的 32 B 动态球灯，并写入稳定 source key；真正采样等待 ReSTIR。 | [动态光源数据层](devlog/M18-dynamic-light-data.md) |
| M19 | 受伤 overlay、实体火焰和近似 glint 已进入路径追踪并通过视觉验收。 | [实体 overlay](devlog/M19-M20-entities-particles.md#m19实体-overlay) |
| M20.1–20.3 | 粒子发光、stochastic alpha 和可选阴影完成；阴影默认关。 | [粒子](devlog/M19-M20-entities-particles.md#m20粒子) |
| M21 | 世界锚定降雨暴露、连续湿润历史、程序化水坑/涟漪、RT 水花与受统一光源照明的 HDR 雨丝完成并通过功能视觉验收。 | [雨天表面系统](devlog/M21-rain.md) |
| M22 | ACES 2、分区调色、镜头效果、RGB 颗粒、Bloom/Flare 与 EV 域自动曝光完成并通过功能视觉验收。 | [后处理与镜头效果](devlog/M22-post-processing.md) |
| M23 | 完整可移植 TOML 预设、严格事务导入、LIVE/RESTART 分层和原生文件交互完成并通过功能验收。 | [配置预设](devlog/M23-config-presets.md) |

### 1.4 尚未完成的主线

以下项目没有因旧计划或已合并 PR 自动获得完成状态：

- 间歇性水面消失与水下曝光闪烁：GitHub [Issue #20](https://github.com/DsWePm/Fluorite/issues/20)，等待下一次现场证据。
- M13 最终性能与专项复验：结构雾 0/A/0、12→24 步、D72 水波天气过渡、D73 焦散天气衰减。
- M14 末地美术替换：当前 HDR/Kerr 技术 Provider 已由 PR #26 合入；后续由用户在 Blender 预渲染包含星空、透镜黑洞和动态吸积盘的循环 HDR 全天球序列，再替换当前实时黑洞。素材到位前不决定帧格式、分辨率、帧率、循环长度、插帧与照明采样方案。
- M11 云成本结算：D169–D175 的观感已通过游戏内验收，但**两项性能代价未测**——掠射穿薄云且始终不饱和的射线从约 9.5 步变为最坏跑满 96 步（D172），域扭曲每次非 cheap 密度多一次 3D fetch 且位于 march 最热路径（D173）。需按铁律 7 用比值法量 `gpu.cloudMarch`，再决定是否调整步数预算。
- ReSTIR 前全项目 review、诊断清理和性能欠账结算。
- ReSTIR 整合。
- 云向地面/水面投影阴影，以及焦散读取二维云太阳透射率图。

## 2. 架构地图

### 2.1 一帧如何流动

`RtComposite` 是渲染编排中心：收集世界/实体/天气状态，更新 LUT、云雾和水仿真资源，构建追踪参数，依次录制 Pass A、Pass B、DLSS-RR、曝光、overlay 和显示合成。

1. **Pass A — `world_primary.rgen.slang`**：每像素一条相机光线，捕获 RR guides 与运动矢量；最多消费一个 delta 电介质分裂，向固定队列写 1–2 条 `PackedPathSegment`。它只解析边界、IOR/Fresnel、分支和 ray-cone，不施加被消费相机前缀的吸收或散射。
2. **Pass B — `world.rgen.slang`**：弹射循环、太阳 NEE/MIS、RIS、SSS、体积段、云和俄罗斯轮盘。所有完整 `SegmentIntegral` 都归这里所有。
3. **相机前缀合成**：空气用 froxel 的 in-scatter/transmittance；水下用真实起始水 `Medium`。最终形式是 `prefix.inScatter + prefix.transmittance × leafRadiance`。
4. **显示**：raw HDR 进入 DLSS-RR/曝光/显示映射；细线 overlay 在 display 分辨率光栅化，因为它们无法可靠穿过 RR。

队列固定为 `2 × width × height`，`MAX_PATH_SEGMENTS==2` 与缓冲大小是同一事实。分裂资格由材质 model 决定，不由最终叶集合反推。

详细队列和 pass ABI 见 `docs/WAVEFRONT_PLAN.md`。

### 2.2 Shader 文件职责

| 区域 | 文件 | 职责 |
| --- | --- | --- |
| 入口 | `world_primary.rgen.slang` | Pass A、guides、队列写入 |
| 入口 | `world.rgen.slang` | Pass B、弹射、体积、debug views |
| 命中 | `world.rchit.slang` / `world.rahit.slang` | 材质求值；alpha/stochastic alpha；阴影透射累积 |
| 天空 | `world.rmiss.slang` | 大气 sky-view/日月，或末地 HDR 环境/Kerr 盘面 miss |
| 末地环境 | `environment.slang` | 全天球坐标、Kerr transfer/穿盘路径解码、运行时动态盘有界 `Le/T` 积分、HDRI 旋转、环境天体 NEE |
| 公共 ABI | `world_common.slang` / `world_core.slang` | `WorldPush`、地址、材质、segment、Light、bindings、payload |
| 材质与光照 | `bsdf.slang` / `lighting.slang` / `light_sampling.slang` / `subsurface.slang` / `rain_surface.slang` | Disney、共享 Light/alias/grid 采样、RIS/NEE、BSSRDF，以及共享降雨暴露与能量分层水膜 |
| 介质 | `medium.slang` / `volume.slang` / `volume_source.slang` | Medium 参数、段积分、跨 compute/RT 的纯源数学 |
| 大气 | `atmosphere*.slang` / `sky_*.comp.slang` | transmittance、multi-scatter、sky-view、reduction、froxel |
| 可见性 | `volume_visibility*.slang` | 世界空间太阳/天顶可见性网格 |
| 云 | `cloud.slang` / `cloud_field.slang` / `cloud_noise.comp.slang` / `cloud_weather.comp.slang` / `cloud_warp.comp.slang` | 世界锚定低层 3D 体积 march、单层解析高云、共享 Radiance 光照和二次光线预算。`cloud_field` 是**无入口无绑定的场模块**：三个 bake 共用同一份构造，两份拷贝一旦被单独编辑就会悄悄改变整片天空 |
| 雾 | `fog_noise.comp.slang` | 128³ 结构雾一次性烘焙 |
| 降雨暴露 | `rain_exposure.comp.slang` | 世界锚定、沿全局雨向的首命中深度图；供所有路径顶点与可见雨丝共享 |
| 水 | `water.slang` / `water_wave.slang` | 水材质、波场、法线、焦散 |
| 水仿真 | `water_sim.comp.slang` / `water_obstacle.comp.slang` / `water_deform.comp.slang` | 高度场、障碍、顶点位移与 refit 输入 |
| 追踪 | `trace.slang` / `trace_ser.slang` / `segment.slang` | 普通/SER TraceRay、分段数据 |

`shaders/display/` 负责 HDR/SDR、直方图曝光和 UI composite；`shaders/overlay/` 负责方块轮廓、glow、名牌，以及 RR 后/曝光前的 HDR 雨丝。

### 2.3 Java 文件职责

| 路径 | 职责 |
| --- | --- |
| `FluoriteConfig` / `FluoritePresetService` | `-Dfluorite.*` → TOML → 默认值三层配置；外部值编解码、版本化可移植预设、事务替换与待重启值保护 |
| `client/RtVideoOptions`, `client/gui/` | 每帧可读设置与二级分类 UI |
| `platform/` | 唯一 loader 抽象表面：paths、quads、sprite lookup |
| `rt/RtComposite` | 帧编排、WorldPush、资源生命周期、Pass/compute 顺序 |
| `rt/RtEnvironmentForcing` | 一帧一次读取天气/时间，解析为雾、云、水的最终 forcing |
| `rt/RtCloudLighting` | D61 的云扩散源尺度，CPU 侧能量标定 |
| `rt/sky/RtHighCloudTextures` | 校验并上传两份高云 KTX2；资源失败时绑定零光学厚度回退，禁止留下空 descriptor |
| `rt/sky/RtSky` | 大气 LUT、体积可见性、降雨暴露和水模拟资源及其唯一烘焙顺序 |
| `rt/sky/RtSkyPreset` / `RtSkyPresets` / `RtDimensionControls` | 版本化维度 Provider/preset、资源包覆盖、未知维度回退与玩家逐维度修正 |
| `rt/sky/RtEnvironmentTextures` / `RtKtx2` | resource epoch 内 KTX2 校验、环境/transfer/disk 数组上传与逐维度 layer 映射 |
| `buildSrc/.../GenerateEndEnvironment` | 构建期把许可的 10K HDR 转为 4K KTX2，并离线生成 Cartesian Kerr-Schild transfer 与盘 `Le/T` |
| `rt/terrain/` | 地形驻留、section 构建、流体、静态发光 quad、light hierarchy/grid |
| `rt/entity/` | 实体/粒子捕获、逐帧 BLAS/TLAS、overlay aux 数据；从真实 item submit 变换提取手持动态光；CPU 稀疏雨滴落点 |
| `rt/light/` | 共享 32 B `Light` CPU 编码、动态球灯记录和功率守恒 quad→sphere 聚合 |
| `rt/material/` | CPU decode-once 材质管线、LabPBR、发光资格、IOR 和 JSON overrides |
| `rt/accel/` | Vulkan buffer/image、BLAS/TLAS/OMM |
| `rt/pipeline/` | RT pipeline/SBT、DLSS-RR/FG、曝光和显示 |
| `rt/overlay/` | display-resolution 共享 overlay buffer、既有 feature，以及独立 HDR 雨丝 pass |
| `rt/RtGpuTimers`, `RtFrameStats` | GPU timestamp 与 CSV；性能结论的唯一数据源 |

计时名称是运行期 ABI：每个 `RtFrameStats.FRAME.stage("…")` 的 CPU scope，以及
`RtGpuTimers.create(...)` 中每个 `gpu.*` zone，都必须同时出现在 `RtFrameStats.FRAME` 的 stage 注册表。
漏掉 CPU 名称会在对应条件分支首次执行时立即触发 RT failure latch；漏掉 GPU 名称则会等帧环异步回读
timestamp 时才触发，二者都会回退原版。`RtPostProcessingContractTest` 会扫描全部直接 CPU 调用点并跨文件
核对 GPU zone；新增、删除或重排计时区时必须运行该测试，不能只以 shader 编译或首帧成功作为通过标准。

### 2.4 ABI 锚点

| 结构 | 当前大小 | 闸门 | 危险点 |
| --- | --- | --- | --- |
| `PackedPathSegment` | 48 B | `RtPathSegmentLayoutTest` | 只剩一个空 uint lane；进位到 64 B 在 1440p 约 +118 MB，优先使用 `pathFlags` bit |
| `WorldPushConstantsData` | 112 B | `RtMaterialLayoutTest` | 12 个 64-bit 地址；Vulkan 128 B 保证下只余 16 B，每个新增字段由 closest-hit 高频读取 |
| `WorldPushData` | 1104 B | `RtSkyMediumLayoutTest` | 独立 GPU 数据，不受 128 B push-constant 限制；`cloudEvolution` 与 D173 的 `cloudWarp` 各是独立 16 B lane，M21 使用 8 个降雨向量；构造是 positional，Java/Slang 必须同步 |
| `MaterialHeaderData` | 80 B | `RtMaterialLayoutTest` | 逐字段偏移钉死 |
| `MaterialExtensionData` | 64 B | `RtMaterialExtensionLayoutTest` | Disney 与 format-3 天气响应 lanes 逐位钉死 |
| `Light` | 32 B | `RtDynamicLightContractTest`、`RtLightHierarchyTest` | 32 B 对齐 cache line；bit31=动态球灯，半轴/半径按类型解释；矩形面积由 halfU/halfV 反推 |

`Ptr<T,...,Std430DataLayout>` 的 layout 参数承重；裸 `Ptr<T>` 会使用 natural layout 并静默错位。

### 2.5 统一 Medium 的边界

`Medium { ior, extinction, flags }`，栈深 2 且为具名字段。WATER/AMBIENT 使用原子 `uint flags`。环境介质永不进栈；Pass B 为规避 R24，直接从 queue `pathFlags` 解码唯一 `activeMediumFlags`。

| 能力 | 水/封闭介质 | 空气雾 |
| --- | --- | --- |
| 透射 | 均质闭式 `exp(-σt·t)` | 结构关：解析高度积分；结构开：有限步非均质 march |
| 参数 | `mediumSigmaT` / `ScatterAlbedo` / `PhaseG` / `Profile` | 同一接口 |
| 段入口 | `integrateSegment` | 同一入口 |
| τ→位置 | 均质解析 | 高度/结构密度反演 |
| 太阳 | 同一有限面积天体采样、阴影纪律和 `E·phase` 能量口径 | 同一口径 |
| 天空 | `mediumSkyRadiance` + 局部水深/开放度 | `mediumSkyRadiance` + 天顶开放度 |
| 多次散射 | `g_eff` + 扩散衰减 | 大气 MS LUT + 扩散衰减；局部雾自身 MS 尚无独立模型 |

刻意不统一的部分：均质水不退化成 march；低云保留球壳 3D march，高云用解析薄层 Beer 积分；云整体仍是纯光线函数；froxel 与 marched 永久并存，因为 froxel 只能覆盖相机前缀，进不了反射和 GI。

### 2.6 天空、云、天气和水

- `RtSkyPreset` 选择通用 `SkyProvider` 和介质能力；shader 只看 provider/capability，不读取或猜测维度 ID。
- 大气 Provider 中，`RtSky` 是 LUT 链的唯一顺序权威：transmittance → multi-scatter → sky-view → medium-sky reduction → froxel；其他 Provider 不运行无意义的大气 bake/reduction。
- `mediumSkyRadiance` 是天空 Radiance 的 `1/(4π)` 立体角平均；水、雾和 froxel 共读。
- 大气 Provider 的 `lightRadiance.xyz` 历史名字实际承载天体 irradiance `E`；体积单次散射必须写 `E·phase`，禁止恢复 D61 的 `4π`。Environment Provider 复用该 lane 传盘面倍率，读取前必须先按 provider 分支。
- Environment Provider 的可见盘面、BSDF 命中与直接光都调用同一个 `Le(direction)`。方形天体只作方向 proposal；NEE 使用 `Le/pdf`，空方向贡献零，不能再放一份独立颜色或功率的假面积光。
- 云是世界锚定纯光线函数，`cloud.slang` 禁止出现相机位置。低层对流云是真 3D 密度 march；高云是两个薄球壳上的光学厚度场，沿射线解析积 Beer transmittance。二次光线 off/reduced/full 只改变允许的成本，不改变云所在世界。
- 高云源贴图只拥有形状/光学厚度，禁止采样素材 RGB、太阳、地面、曝光或烘焙光照；当前 `lightRadiance` 与 `mediumSkyRadiance` 是唯一照明来源。高云是**单层**：10 层随机云片阵列，与低层云按每条射线的真实交点排序。上层的周期形状场已于 D169 删除——它处处有值，会把云片之间本该露出的天空全部填满，而那正是卷云天空唯一不能丢的性质。
- `RtEnvironmentForcing` 一帧一次读取时间、rain、thunder。GPU 只看到最终 density/coverage/type/scatter/contrast/wave state，不读取原始天气。
- Weather Effects 拥有全局风 heading 和天气响应；雾、低云、高云、水波保留速度和相对偏转。
- 水波的十个波长与绝对相位固定；天气用 20 秒连续频带重加权，不改 `k/ω`。
- 焦散强度和色散属于 Water；天气只能提供自动衰减。

### 2.7 光源、实体和粒子

当前静态光源链是 `RtLightCollector → RtLightHierarchy → RtLightGrid/RtLightGridManager`。一块发光 quad 对应一个矩形光源；GPU `Light` 为 32 B。RIS 使用降维目标选择，幸存者用完整 Disney 求值，结构保持无偏。静态 collector 的 80 B worker 记录继续保留 exact-Le UV 三组 half2 lane；当前 hierarchy 虽不上传这些 lane，但删除它们会让 ReSTIR 接回精确纹理辐亮度时重新扫描发光 footprint。

太阳/月亮走独立有限面积天体接口。实体火焰、glint 和粒子 emission 已能在直接命中、反射和偶然 GI 中发光，但不进入静态 light buffer，因此不会被 NEE 选中。

M18 的已批准边界是“收集但不采样”：动态记录使用同一 32 B ABI，bit31 标记 sphere，半径放在 `halfUxy` 低 half；当前单独的逐帧 buffer 不进入 `WorldPush` 或 descriptor，因此画面结构上保持 bit-identical。手持 `BlockItem` 只在默认 `BlockState.getLightEmission()>0` 时有资格；颜色和辐亮度来自该 state 解析出的现有材质 `EmissionSummary/emissionStrength` 与实际 item tint，不允许名称白名单、固定橙色或另一套亮度表。每只手的真实 submit quads 聚成一个球，球面积等于发光覆盖面积并反算辐亮度，保持 `πA·Le` 总功率。位置来自提交姿态下的发光功率重心。真正加入 NEE/GI 等 ReSTIR。

粒子目前仅完整支持 `SingleQuadParticle`。粒子阴影有独立 cull bit，默认关闭；反射/GI 不随阴影开关自动开启。

## 3. 铁律与危险契约

代码附近的注释是局部权威；本节是入口。修改相关区域前必须读原注释和 layout test。

### 3.1 所有方向性决策先请示

方法选择、物理近似、默认档、性能换质量和架构所有权都必须先向用户提供：

1. 可选方案。
2. 每项与物理准确结果的差距。
3. GPU/CPU/显存成本的实测或明确标注的估计。
4. 推荐理由。

用户批准后才能实施。进行中的选择留在本文件待办；结案后把完整依据迁入对应 devlog，并在本文件更新长期规则或成果摘要。禁止擅自决定。

### 3.2 BSDF 与材质

- `roughness` 是线性 GGX `alpha`，永远不要平方。
- `ggxD` epsilon 是除零保护，不是能量 clamp；不许调回 `1e-7`。
- LabPBR 只在 CPU `RtLabPbr` 解码；不得重新引入 shader runtime decode。
- 发光资格由外部证明后才调用 `RtEmissionHeuristic`，避免普通亮纹理被误判成灯。
- delta 路径的太阳 MIS 权重 0 是当前发布行为；不要按教科书字面改回 1。
- `UNWEIGHTED_SPEC_ALPHA_FLOOR` 是明确非物理的临时方差护栏，解除条件是 ReSTIR；新功能不得依赖它。
- `Prim.normal.w` 是 emission mask，不是 HDR 强度；实体材质本身必须带强度。

### 3.3 介质与体积

- `MediumStack` 深度 2、具名字段、非数组；动态索引会让寄存器受限 raygen 落 scratch。
- 环境介质不进栈，ambient 不能由 IOR/extinction 猜测。
- R24 规避层不得在无真实 GPU cleanup 证据时删除；indirect raygen 禁止同时保存两个聚合 `Medium` 分类状态。
- 阴影 payload 不增长；透射累积只通过唯一包装器。
- 体积采样位置不能直接依赖段长，否则相邻命中距离会形成块状偏差。
- 分层 jitter 是近似，不宣称严格无偏；冻结时域后误差应是噪点，不是块。
- 太阳可见性与天顶开放度是不同物理量，禁止用一个替代另一个。
- 可见性=1 时子步必须 telescoping 回原闭式；关闭新输入必须可证明 no-op。
- `mediumSkyRadiance` 与天体 `E` 量纲不同；所有归一化 phase 已含 `1/(4π)`。

### 3.4 水与形变

- 水下太阳阴影方向先过 Snell 折射。
- 焦散对同一波场求导；波形、法线、几何和焦散必须共享数学。
- 色散默认是美术夸张，不得称为物理 1×。
- 相机水深参考是相机所在水体表面；出水时不能把全场景深度归零。
- CFL、Neumann 边界、海绵层和冲量钳制是稳定性契约。
- 需要 refit 的 BLAS 不压缩；AS flags 在尺寸查询、创建和构建录制三处一致。
- 形变开档在世界加载时快照；关闭可即时停止 dispatch，开启需要重载世界。
- 波长改变会重解释绝对相位；天气不能实时改 `k/ω`。
- 所有世界锚定场都先恢复 terrain rebase 之前的绝对坐标。

### 3.5 天空与云

- `RtSky` 内 LUT dispatch 与相邻 barrier 不得拆散。
- sky-view 相位留在 LUT 外；Mie 前向峰不能被低分辨率方位轴烘平。
- `world.rmiss` 保持零“维度 ID”分支；只允许对通用 `SkyProvider` 分支。新增维度行为进 preset/provider，不把注册表名字写入 shader。
- `cloud.slang` 禁止相机位置；低云密度、高云纹理散布、层序和 LOD 均按每条射线自身起点与绝对世界坐标。
- 云太阳源为 `E·phase`，恢复 `4π` 会让无源云反照率超过 1。
- 关闭 cloud multi-scatter 必须删除整项扩散源，不能只把衰减率设成 1。
- 高云两层总光学厚度相加并按 `T=exp(-τ)` 合成；旧高云 3D morphology/march 已删除，不得把其参数语义接回。
- 未来二维云影必须沿太阳对当前云光学模型求 Beer transmittance：低云积分 3D 密度，高云取那一张薄层的 `τ`；禁止直接拿 raw coverage/shape noise 当阴影。**低云的密度现在还要过 D173 的域扭曲**，绕开它会让阴影与投下它的云不是同一个形状。

### 3.6 Wavefront、Vulkan 与资源生命周期

- `PackedPathSegment`、WorldPush 和材质 ABI 任何改动都先跑对应 layout test。
- SER 前后重建 payload，不把 72 B payload 跨 `ReorderThread` 保活。
- radiance/shadow ray 共用 Payload ABI；新增 shadow 状态不能另起结构。
- 段积分发生在天空 break 之前。
- 水面/介质边界不能由 Pass A、Pass B 分摊一半能量。
- 加速结构资源所有权显式记录，不从异步路径状态推断。
- 验证层沉默不代表正确；device fault 地址和资源名必须归档。

### 3.7 实体、粒子和 overlay

- 实体累加器与 terrain section mesh 同布局，复用上传/BLAS 路径。
- 实体纹理槽按解析后的 image view 键控，不按每帧新建的 RenderType。
- 名牌和细线 overlay 保留在 display-resolution 光栅 pass。
- 粒子颜色是 raw albedo，不烘 vanilla lightmap；路径追踪自己打光。
- float lane 搬整数位型必须完整 round-trip 测试。
- 粒子 shadow、reflection、GI 是三项不同能力，不能用一个 cull bit 一起上线。

### 3.8 平台、配置、语言和文档

- `Platform.get()` 抛异常的行为承重；测试环境依赖 catch 后回退路径，不能软化成 null。
- slangc 最低 2026.14；Vulkan SDK 自带旧版本不能替代。
- `-Xss16m` 必须直接作为 JVM 参数，不能依赖 `JAVA_TOOL_OPTIONS`。
- 设置用途结束后要么删除，要么记录长期理由；禁止废弃旋钮留在 UI。
- `RtVideoOptions` 只放每帧可重读设置；需要重建资源的设置保留在 TOML/`-D`。
- 新增/修改设置只保证 `en_us`、`zh_cn`。包括 `zh_tw` 在内的其他语言允许依赖 `en_us` fallback；禁止填英文占位凑齐键，也不把它们列入验收阻塞项。
- GitHub Issues 是未完成问题跟踪器；已结案过程进入 devlog。
- `.claude/plans` 是历史考古，不是当前事实源。

## 4. 开发与验证方法

### 4.1 诊断顺序

遇到视觉故障时：

1. 先把现象写成可观察变量，不先命名机制。
2. 为候选子系统设计判据表，每个实验说明什么结果支持/否决什么。
3. 优先使用运行时隔离开关和共享真实路径的 debug view，保持同世界、同位姿、同会话。
4. 修到顺手发现的真缺陷不等于结案；只有原现象的判据链闭合才算根因。
5. 记录失败路线。被证伪方案不能换个名字重新进入代码。

高价值隔离开关：`water.scatter-source`、`volumetrics.segment-source`、`volumetrics.sun-shadow-rays=0`、`volumetrics.visibility-cell-size=0`、`volumetrics.clouds`、`cloud-sun-steps`、`cloud-secondary`、雾结构开关和粒子阴影。

### 4.2 性能测量

正式结论必须满足：

- 1920×1080 显式启动。
- 固定 world、camera pose、存档和配置快照。
- 使用 `RtGpuTimers`/frame CSV，不使用 CPU 录制时间或 FPS 猜测。
- 每批第一次运行整体丢弃。
- 分子分母来自同一批次且均非首次运行。
- 每次运行丢弃前 15% 暖机帧，再在预先定义的稳定窗口取 GPU zone 中位数，并报告分块离散度。
- 采集期间不碰输入。
- 开关关档必须等于发布行为，否则 A/B 无对照意义。

场景必须覆盖被测代码：普通 `bench`、`bench-water-bottom`、`bench-water-top`、未来 `bench-particles` 不能互相替代。

### 4.3 已知成本账本

| 功能 | 当前证据 | 口径 |
| --- | --- | --- |
| M6 解析高度雾 | +0.622 ms | 历史同位姿 GPU A/B |
| M7 Disney BSDF | 1.283× | 低于 1.5× 当时门槛 |
| M8 random walk | 1.567× | 超门槛，thin 默认 |
| 可见性网格 | 约 0.072 ms | 64×32×64；关闭会跳过 dispatch |
| froxel bake | 约 0.28–0.64 ms | 隔离计时，不是统一硬件承诺 |
| M9 分层水散射 | 10.49 ms | `bench-water-bottom` 同批窗口 |
| M17 散射顶点 | 7.56 ms；相对 0.930×，省 2.93 ms | 同上，默认开 |
| M17 发光体 NEE | +20.9 ms | 默认关；阴影线不是主要成本 |
| `SegmentIntegral` 6→12 floats | 1.001× | 未撞 R6 台阶 |
| M12.5 水形变 | 小场景约 4 section / 0.27 ms | 海洋场景未测，不可外推 |
| M11 云 | 未测；D172/D173 又各加了一笔未量化的代价 | R19 活跃风险，优先级已上升 |
| M13 结构雾 | 未完成正式 0/A/0 | 不得仅凭“12 步”估成本 |
| 粒子阴影 | 未测 | 默认关，需 `bench-particles` |

室内 `gpu.traceIndirect` 下降曾被观察到，但机位、分辨率和配置不完整，不能作基线；按用户决定在 ReSTIR 后再诊断。

### 4.4 Shader 运行期日志

介质/ABI 类故障必须：

1. 先运行 `generateShaderRecords compileShaders` 和目标加载器 resources/Java 编译。
2. 使用原始复现世界。
3. 同时记录 queue 解包、函数入口、首次积分前、积分后/profile。
4. 只增加能区分假设的字段。
5. 删除一次性 probe 后，再用普通路径长跑。
6. 工具链升级后比较 SPIR-V 与真实 GPU 行为，不能把 SDK/驱动/Slang 升级自动视为修复。

`diagnostics.water-medium-trace` 使用完成环槽低频回读，不制造当前帧 stall。Issue #20 根因确认前不得删除该诊断及水面 cross-check 分支。

### 4.5 跨加载器验证

两个 run 目录都会改写存档。比对前从纯净主副本还原；地形摘要只比较 `builds==1` section。运行 `verifyCommonIsLoaderAgnostic` 和 NeoForge Java 编译。未列入 `docs/PLATFORM_NOTES.md` 的加载器差异默认按 bug 处理。

## 5. 工具与操作入口

### 5.1 构建

Windows：

```powershell
.\gradlew.bat :fabric:build
.\gradlew.bat :neoforge:build
.\gradlew.bat build --rerun-tasks --no-daemon
```

定向 shader/ABI：

```powershell
.\gradlew.bat generateShaderRecords compileShaders
.\gradlew.bat fetchEndHdr
.\gradlew.bat generateEndEnvironment
.\gradlew.bat :neoforge:processResources :neoforge:compileJava
```

slangc 解析顺序：Gradle `-P<name>Path` → 环境变量 → `$VULKAN_SDK/Bin` → PATH；当前独立工具链位于 `F:\MC\Shader\tools\slang-2026.14`。

当前 D93A-R `generateEndEnvironment` 冷生成实测约 8 分 32 秒，输入未变时由 Gradle 增量缓存跳过。D87C 裁决为不把 89 MiB 母版放进 Git/LFS：默认由 `fetchEndHdr` 下载到已忽略且不受 `clean` 影响的 `.gradle/fluorite-assets/`，并严格校验 SHA-256 `dad11594…fd393d90`；来源不可用时可用 `-PendHdrSource=<path>` 提供同一哈希的本地副本。生成物进入 loader 的资源 classpath/jar，运行时只校验和上传，不重新追踪 Kerr，也没有“首次进游戏后落盘的 LUT 缓存”。旧生成器耗时与被替换路线见 M14 开发日志。

### 5.2 运行与基准

| 工具 | 用法 |
| --- | --- |
| `tools/bench-world.sh [name]` | 从 `run/bench-master/` 还原基准世界；`--adopt [name]` 建立主副本 |
| Fabric | `.\gradlew.bat :fabric:runClient -PbenchWidth=1920 -PbenchHeight=1080 -PbenchWorld=<name>` |
| NeoForge | `.\gradlew.bat :neoforge:runClient -PbenchWidth=1920 -PbenchHeight=1080 -PbenchWorld=<name>` |
| frame stats | `-Dfluorite.rt.frameStats=true`，输出 `<gameDir>/rt-frame-stats/frame.csv` |
| validation | `-PvkValidation`，需要 `run/vk_layer_settings.txt` |
| JFR | `profileMinecraft.ps1 -TargetPid ... -RecordingName ...` |
| 常规启动 | `runClient.ps1`，设置 Vulkan backend、JVM 和 ZGC 参数 |

`frame.csv` 每次会话重建，下一次启动前先归档。`compare-digest.sh` 不存在；平台摘要按 `docs/PLATFORM_NOTES.md` 手工比较。

### 5.3 Debug views

| 编号 | 用途 |
| --- | --- |
| 0–7 | 关闭、normal、albedo、depth、roughness、motion、specular、specular motion |
| 8 | 首叶体积 in-scatter，固定逐像素 seed |
| 9 | 首叶段长度/逃逸/水介质 |
| 10 | 体积太阳可见性 |
| 11 | 水底焦散/色散诊断 |
| 12–16 | transmittance、multi-scatter、sky-view、LUT vs reference march、天体染色 |
| 17 | aerial perspective froxel |
| 18 | 世界空间体积可见性网格 |
| 19 | 网格与真光线可见性 profile |
| 20 | 正常合成实际使用的相机前缀 in-scatter |
| 21 | 完整 pre-RR 合成的前缀有/无 A/B；storage-image Y 约定使条带显示上下反向 |
| 22 | 云链路分量探针 |
| 23 | 水仿真原始高度场 |
| 24 | 水面真实世界→域 reach/坡度 |
| 25 | 雾 base/detail/resolved/final density 四阶段 |

debug 20/21/25 和水体 probe 属 review 候选，不是永久产品功能；删除前必须确认不再服务任何未结 Issue。

### 5.4 GitHub Issues

问题和长期任务使用当前仓库 Issues。创建前搜索重复项；已结束过程不继续堆在 Issue 或主文档，迁入 devlog 后关闭。状态约定见 `docs/agents/issue-tracker.md` 和 `docs/agents/triage-labels.md`。

## 6. 参考项目政策

`F:\MC\Shader\Reference\HPWater` 与 `HPVolumeCloud` 只用于比较实现方法和物理选择。

硬规则：

1. 不复制代码、表达式排列、文件结构或数值常数。
2. 两项目含 Unity HDRP 来源且逐文件许可边界不清，必须保持净室距离。
3. 它们是光栅/RenderGraph/屏幕空间管线，不能把成本模型直接移植到纯光线架构。
4. HPWater 相位漏 `1/(4π)`，HPVolumeCloud 含量纲不明的强度常数；所有常数由本项目重推和标定。
5. 参考实现含相机依赖云场等结构缺陷；Fluorite 的云必须对反射射线成立。
6. 外部 shader 页面必须先核对逐文件许可。Shadertoy 页面没有显式许可证时采用网站默认的 CC BY-NC-SA，不能把代码复制、翻译或改写进 Fluorite；只能提炼不受版权保护的通用方法，并以不同的数据流、公式和常数独立实现。本轮雨滴参考页仅提供“随机分布事件 + 浅水反馈”的讨论入口，获批实现选择了无状态解析事件场，没有复制其反馈代码。

每次准备借鉴新方法时，先向用户提交：参考做法、本项目候选做法、异同、物理差距、性能代价和推荐。用户批准后才实施。历史对比见 M9/M11 devlog。

## 7. 活跃风险与危险事项

| 风险 | 当前状态与要求 |
| --- | --- |
| 路径记录/寄存器 | 48 B segment 只余一 lane；raygen 仍可能撞 VGPR 台阶，任何新增热路径都实测 |
| R18 云相机依赖 | 当前规则有效；未来阴影图也必须世界锚定，不能把相机位置写回云密度 |
| R19 云成本 | 低层 march、高云表和 secondary 从未正式定价；高云常驻原始载荷约 **13.98 MiB**（D169 删除上层后从 23.1 降下来），默认查询为 3×3 候选算术/通常 1 次纹理读取；不得把候选循环误报成固定 9 次读取，也不得未测就扩预算。**D172/D173 又各加了一笔未量化的代价**：掠射穿薄云且不饱和的射线最坏跑满 96 步，域扭曲每次非 cheap 密度多一次 3D fetch |
| R20 参考常数 | D61 已证明 4π 错误会跨雾/水/云扩散；所有新源项先做能量契约测试 |
| R24 Slang 错编 | D27 规避层保留；升级版本不自动删除 |
| 水形变内存 | `deform-mode=all` 消除重建闪烁，但含水 section 放弃压缩并常驻输入；海洋场景未测 |
| 间歇性水故障 | Issue #20 等现场；禁止视觉猜测修复，诊断不得提前删 |
| 云天空遮蔽 | 当前 `τup` 为局部密度解析近似，可能高估塔底、低估塔顶；review 时裁决 |
| 高云视觉 | D160 距离假设和 D161/D162 旧形态均未通过复验；D163–D168 已以两层解析光学厚度场替换旧体积卷云，天顶环应从结构上消失，但必须经游戏内天空、反射、昼夜和天气复验后才能关闭风险 |
| 粒子阴影 | 成本未知、默认关；需要真实烟柱场景 |
| BSSRDF/glint/焦散常数 | `PROVISIONAL` 或美术夸张，不得宣传为物理标定 |
| 语言 | 只保证 `en_us`、`zh_cn`；仓库中的 `zh_tw` 是不完整的旧资源，不列入当前维护和验收范围 |
| 资源生命周期 | `RtComposite.lutSampler` 已知未销毁，进入 ReSTIR 前 review 修复 |
| 末地环境成本 | 三张 KTX2 原始载荷/GPU 常驻约 74.7 MiB；Fabric jar 内 DEFLATE 实测合计约 24.2 MiB（HDRI 15.0、transfer 9.1、稀疏 disk 0.15 MiB）。可见 miss 读取 transfer、disk，并仅在逃逸时读 HDRI，环境 NEE 读取同一 disk `Le`；1080p GPU 时间待验收实测，未测前不宣称成本接近太阳 |
| 末地资源兼容 | 只接受 4096×2048 R11G11B10 13 mip HDRI，以及 2048×1024 RGBA16F transfer 与 disk；任一资源失败只回退该维度到完整大气，不能留下未绑定 descriptor |
| 末地母版可用性 | D87C 不跟踪 10K 母版；首次干净构建依赖外部 URL。下载必须匹配固定 SHA，禁止网站替换后静默更新；来源失效时只能提供已归档的同哈希副本，不得擅自接受新素材 |
| M21 降雨成本 | 暴露图每帧最多更新 8 个 32² tile（8192 条 RayQuery），近区 4 tile 高频循环、远区 4 tile 渐进轮询；默认雨丝 4096 条。D129A 为每条进入屏幕宽边界的雨丝最多增加一条天体阴影线和一条统一局部光源阴影线，并常驻 256 KiB 的 16384×`float4` 辐亮度缓存；compute 视锥预剔除和缺少有效光源会减少实际射线数。尚无游戏内 GPU 中位数，不能宣称成本可接受；性能日志必须同时核对 `gpu.rainExposure`、`gpu.rainStreak`、`rain.exposureCache` 与查询/上传计数 |

## 8. 待办与未来架构

### 8.1 当前未结问题

#### Issue #20：水面消失与水下曝光闪烁

下一次触发时：水下朝曝光方向停留约 3 秒，再朝缺失水面停留约 3 秒，立即记录现实时间，并记录天气、区块加载、雾/水波/形变开关。用同一段日志核对 terrain water instance、CPU/GPU 起始 Medium、Radiance/segment/visibility 的 NaN 或突变。

完成标准是日志或最小复现证明根因，Fabric/NeoForge 构建通过并完成视觉回归。旧“submerged 与参考水面矛盾”探针未触发，已降级为候选，不允许据此直接改代码。

#### 高云替换待视觉验收；低层云形态待裁决

游戏复验确认 D160 的距离预算/渐隐不改变天顶环，D161 的低层差分平流也没有产生目标中的云体生长。D163–D168 随后完成结构替换：旧高云体积 march 和 morphology 已删除，高云为确定性散布的 CC0 透明云片光学厚度阵列，位于薄球壳上，由现有太阳与天空 Radiance 动态重照明。（D163–D168 当时还有一层从 Furry Clouds 提取的周期形状场，已于 D169 删除，理由见 §5 高云条目。）完整失败路线、素材处理、许可和性能边界见[开发日志](devlog/M11-clouds.md#d163d168两层解析高云替换)。

本轮游戏验收检查：天顶环是否消失；下层云片是否随机但不随相机滑动；上层是否无平铺接缝、镜像轴和原 HDR 场景残影；日出/正午/夜晚与天气变化是否只改变当前光照而无烘焙色；直接天空与水面反射是否命中同一高云。低层体积云的生长感与小云包复杂度仍是独立待办，不得用提高高云成本来掩盖。

### 8.2 M13 验收债务

- 同场景雾结构关→开→关，确认两次关档画面和 GPU plateau 一致。
- 跨 rebase、暂停、午夜、风漂移不跳。
- froxel/marched/both 不出现位置、色温和前缀接缝。
- 正对太阳浓雾不恢复 D61 式自发光晕影。
- 记录 12/24 march steps 的 `gpu.froxelBake`、`gpu.traceIndirect` 和总 GPU。
- 复验 D72 20 秒水波天气过渡与 D73 浓云/浓雾下焦散衰减。

这些是验证债务，不是未实现功能。

### 8.3 M14：维度预设与配置收尾

当前架构合同：

- 完整物理大气是代码默认；未知或无效的模组维度自动取得完整大气，不做 `dimensionType()` 启发式降级。
- `RtSkyPreset` 从 `assets/fluorite/fluorite/sky/<namespace>/<dimension>.json` 加载，允许资源包覆盖；format 版本、逐文件隔离、有限值/范围/能量校验是加载契约。
- 参数所有权顺序固定为：preset 基础值 → 该 preset 允许时的 D70 天气/时间 forcing → 用户全局修正 → 玩家逐维度修正。资源包负责物理基线；全局开关仍有最终总门控权，逐维度页只关闭或缩放一个明确维度。
- M14 的 `WorldPush.skyProvider/environmentFlags` 当时花掉既有 8 B padding，结构大小保持 944 B；M21 加入降雨向量后达到 1072 B，D161 的独立 `cloudEvolution` lane 后为 1088 B，D173 的 `cloudWarp` lane 后当前为 1104 B。shader 只按能力分支；`environmentFlags[8..15]` 是 resource-epoch 环境数组 layer，低位仍是介质能力。
- 雾 `heightScale<=0` 是明确的均匀介质编码；高度雾必须为正。关闭结构时必须在纹理 fetch 和数值 march 前退出。
- 大气表、云噪声和雾噪声当前共用一次性静态 bake。内置地狱三者都不需要，因此完全跳过；资源包的非大气维度若开启云或结构雾，会为保证纹理已初始化而顺带烘焙暂时不用的大气表。只有实测首次加载成本值得优化时才拆生命周期。
- `light_sampling.slang` 是 RT 与 froxel 共用的 32 B `Light`、alias、局部 grid 采样接口；维度不能复制一套光源格式。
- 只保证 `en_us`、`zh_cn` 两份语言文件；其他语言资源可存在，但不作为当前功能的维护或验收承诺。

已批准的 Provider：

- 主世界：现有 Rayleigh/Mie/臭氧大气、日月、星空、云、高度/结构雾和天气 forcing。
- 地狱：无太阳/月亮和地球大气；本地发光体为主，froxel 用共享 Light/grid 做一次 NEE 并发阴影线；另有极低中性白保底环境光。雾为关闭噪声的均匀白雾；“维度设置”提供只影响地狱的雾开关、0–2 浓度倍率和 0–8 环境光倍率，全局体积雾仍是总开关。全局与地狱浓度倍率合并后的最终玩家增益仍夹在 2；环境光倍率统一缩放表面保底光、逃逸背景与介质环境源，不改变局部发光体功率；保底光不受遮挡是 D78A 明示的非物理可读性近似。浓度上限是当前 DLSS-RR 对高密度远景体积重建绿色的产品安全边界，不是物理介质上限。
- 末地：format 2 `environment` preset 关闭地球大气、日月、云、天气和体积雾；全天球纹理覆盖岛屿上下全部方向。D87A 使用 TonyS / Space Spheremaps 的 CC BY 4.0 **HDR Multi Nebulae 1**：10K RGBE 只作离线母版，构建期按球面立体角做面积缩放，输出 4096×2048 R11G11B10 HDRI 与 13 级能量保持 mip；运行时不读取 10K 母版。D87C 决定母版不进 Git/LFS，构建只接受固定 URL 或本地覆盖中 SHA-256 为 `dad11594…fd393d90` 的精确字节。README 与第三方声明必须保留来源、许可和变换说明，禁止把素材用于 AI 训练/抓取。
- D90A 用无极轴坐标奇点的 Cartesian Kerr-Schild 3+1 Hamiltonian 与解析导数离线积分，替换会在自旋轴产生竖缝的 Boyer–Lindquist RK4。固定参数为 `a*=0.9`、观测倾角 60°、观测半径 50M、顺行 ISCO≈2.32M；离线盘捕获上限为外半径 12M。2048×1024 RGBA16F transfer 只存逃逸后的三维方向或捕获类别；另外两张同尺寸 RGBA16F 图分别存第一段局部穿盘 chord 的 entry/energy 与 exit/angular-momentum，不再烘焙静态盘颜色。最终半精度 KTX 的旧接缝两侧五组回归探针均小于 2°。
- D93A-R 在运行时沿 Kerr chord 做固定 12 点、五层动态噪声的有限厚度盘积分，使用有界 `Le/T`，并保留 Novikov–Thorne 径向次序和 Kerr `g⁴`。可见 miss、BSDF 路径和盘面 NEE 调用同一个盘求值器；方形天体接口只提出方向并计算 `Le/pdf`，不能另设代理功率。实现只借鉴公开参考项目的思路、公式和参数比例，全部代码与噪声独立编写，不复制其无许可证源码或资产；不加入 bloom，也不声称是字面准确的天体光谱模拟。
- D85A 的盘 proposal 半角保持 `0.36 rad`；当前生成得到 19,169 条有效 chord，全部落在 proposal 支持内，最大 chord 长 6.99M。方形切平面采样仍必须使用逐方向 `1/cos³` Jacobian 的精确 solid-angle pdf；普通 BSDF continuation 保留，未来参数若产生 proposal 外高阶像也不能被 diffuse 防重计门控隐藏。
- D86A 固定 Kerr transfer 与 disk chord 为最近邻采样：一次 fetch 保持 escape/capture 和路径有效性拓扑严格。4096×2048 HDRI 使用 U 重复、V clamp 的线性 mip 过滤；若将来实测约 `0.176°` transfer 离散导致可见锯齿，再单独裁决同类别四点插值，不能直接恢复裸线性过滤。D91A 只给直接相机 miss `-1 mip` 的重建偏置；反射、折射、漫反射和 debug 射线继续使用原 ray-cone LOD。该偏置提高低内部渲染分辨率下的 HDRI 清晰度，但不是严格像素积分，验收必须检查移动时的星点闪烁。
- 末地控制彼此独立：星空亮度和吸积盘亮度均为 `0–8×`、默认 `1×`；盘外半径为 `4–12M`、默认 `8M`；盘厚度为基准半厚度 `0.55M` 的 `0.25–2×`、默认 `1×`；HDRI 绕真实 Kerr 自旋轴的连续游戏时间旋转为 `0–1°/s`、默认 `0.02°/s`。旋转只变换逃逸后的 HDRI 方向，不带动黑洞或盘；任何控制都不能通过暗改另一项来补偿。
- 已批准的后续美术路线是用 Blender 预渲染的循环动态 HDR 全天球序列替换当前实时 Kerr/盘面组合。输入必须是完整 `2:1` 等距柱状、线性 scene-referred HDR、首尾可循环的逐帧全天球；不能把经过显示映射、曝光钳位或 SDR 编码的结果当辐亮度。用户提供素材后再根据实际帧数和体积，在 GPU 纹理数组、相邻帧流式上传或 HDR 视频解码之间裁决，不能预先默认其中一种。
- 动态 HDRI 必须暴露相互独立的亮度与对比度参数。亮度是线性辐亮度倍率；推荐的对比度是在正值 luminance 的 log2 域围绕明确 pivot 调整，再按亮度比例恢复 RGB，以保持色相、非负值和 HDR 高光层次。对比度会改变总能量，因此可见天空、反射/折射、环境平均值和 NEE 必须读取同一变换，禁止只做显示后处理。具体范围、pivot 和默认值等素材到位后由用户批准。
- 预渲染序列若把星空与黑洞/盘合成在同一层，只能天然获得“整个动态环境”的统一亮度/对比度；若未来仍要分别调星空和盘，Blender 必须额外输出分层序列或 mask，不能从合成 HDRI 稳健反推。它用于照亮场景时还需在“逐帧环境重要性分布”和“与动画匹配的有限面积代理光”之间裁决：前者更一致但有逐帧数据与采样成本，后者成本接近现有天体 NEE 但只是近似。素材到位前不得擅自选路线。
- 环境 KTX2 是构建期生成、jar 内只读资源；resource reload 时旧 image/view 在 pipeline descriptor 释放后销毁并重建。维度 preset 或任一资源无效时只让该维度回退完整大气，其他环境 layer 继续工作。

M14 的地狱部分已完成视觉验收；实现、裁决和 RR 浓雾边界见[开发日志](devlog/M14-dimension-presets.md)。末地当前实时实现已由 PR #26 合入，作为可运行的技术 Provider 保留，但不再继续视觉调参；等用户提供 Blender 动态 HDRI 后恢复美术替换。届时必须验收：全天球投影与循环接缝、相邻帧平滑性、直接视线与反射/折射的一致动画、亮度/对比度对可见与照明的统一作用、自动曝光下的稳定性、加载体积与 VRAM、1080p `gpu.traceIndirect` 与总 GPU。若素材只有合成层，则不再承诺独立的星空/盘参数。

### 8.4 M18 与 M20.4：动态光数据层

D98A 把本阶段限定为收集、编码和上传但暂不采样：

- 主/副手 `BlockItem` 光。
- 燃烧实体、发光生物等实体附着光。
- 发光粒子按空间 cell 聚合的代表光。
- 产出与 32 B `Light` 兼容的记录、动态标记，并为类型/区域光源留位。

PR #27 已接通第一条 tracer bullet：主/副手中默认 `BlockState` 发光的 `BlockItem`。`RtEntities` 从 `LivingEntity.getItemHeldByArm` 建立资格上下文，`RtEntityCollectorBase` 在 vanilla 与 Fabric 的真实 `submitItem` 路径观察最终姿态、sprite 和 tint；不改变实体 primitive 的现有材质或几何。每只手的发光 quad 按材质 `EmissionSummary`、`emissionStrength` 和 state emission 聚为一个有限球灯：球心为发光功率重心，球面积为发光覆盖面积，球 Radiance 反算为保持 `πA·Le` 总功率。

D99A 使用共享 32 B `Light` ABI：bit31 为 sphere 类型，radius 写入 `halfUxy` 低 half，`le` 继续是 R11G11B10。动态记录位于 graphics-timeline 守卫的独立逐帧 host-visible buffer；`FrameEntities.dynamicLightAddr/count` 只暴露 CPU 诊断/未来入口，当前 `WorldPush`、descriptor、alias/grid 和 shader 采样均不读取它，所以画面应 bit-identical。`RtFrameStats` 提供 `dynamicHeldBlockCandidates`、`dynamicLightsCollected`、`dynamicLightUploadBytes` 和 `dynamicLightFlushes`；候选大于零而 collected 为零说明真实 item submit 未产出可解析的发光 block-atlas quad。

D100A 保留 `RtLightCollector` 的 80 B worker 记录和 exact-Le UV lanes。现有 terrain digest 为 30,940 灯；相对假设的 64 B 记录额外约 495,040 B（0.47 MiB）worker 内存。CPU 额外工作发生在每个 emitter 已完成 16×16 主扫描之后，只是约六次双线性求值和三次 half2 pack，明确估计远低于 collector CPU 的 1%，且 hierarchy 不上传这些 lane、GPU 成本为零。收益是 ReSTIR 接回纹理精确 Le 时无需重新扫描 footprint 或修改 source ABI。

D101A–D104A 完成剩余数据层：实体只接受实际火焰、submission block-light excess 或材质 emission，排除 Glowing outline 与 glint；每实体按左手、右手、火焰、身体分成最多四球；前 1024 个 `SingleQuadParticle` 进入世界锚定 1 格 cell，纹理均值、tint、alpha、billboard 面积和格内分布共同决定守恒球；sphere `section` 低 30 bit 写跨帧/跨 rebase 稳定 source key，bit30 保留。完整依据、成本上限和诊断见[开发日志](devlog/M18-dynamic-light-data.md)。

真正让动态灯参与 NEE/GI 属于 ReSTIR；本阶段禁止接入旧 alias/grid。用户明确决定跳过本轮游戏内性能画像；固定燃烧实体、发光实体和密集发光粒子场景的阶段中位数、候选/cell 数与上传字节已转入 §8.11 的 ReSTIR 前测量欠账。静态最坏上限为 5120 条、160 KiB/帧，但不能用该上限代替实际中位数。

### 8.5 M21：雨天粒子、积水与浸湿表面

本里程碑必须在 ReSTIR 前完成，范围由三部分组成：

- 雨天新增可见雨滴粒子，并与现有粒子捕获、透明、阴影和未来动态光接口兼容。
- 在受雨外表面生成世界锚定的程序化水坑结构；水坑不能随相机游动，也不能仅作为屏幕空间贴花假装存在于反射/折射路径中。
- 所有真正暴露于降雨的外表面获得连续浸湿响应；至少要区分表面粗糙度/高光变化与有厚度积水，不能把“湿”和“水坑”写成同一个二值材质标记。

D105A–D114A 已批准以下架构与量化边界：降雨暴露使用世界锚定、沿全局风向偏转的降雨方向深度图，一次 bake 后由所有路径顶点共享；低/高档为 256²/512²，默认高档、每 texel 1 方块，边缘向“干燥”淡出，CPU 以 4 方块粗网格缓存群系降水类型；偏转角范围 0–30°、默认 8°。湿润状态分为连续的 `wetFilm` 与 `puddleStorage`；满雨浸湿/干燥默认 8/120 秒，水坑填充/蒸发默认 45/300 秒，日照与云量联动干燥默认开启，夜间和厚云下最低约为晴朗白昼的 25%。动态物体仍没有稳定的逐物体身份历史，不能把世界列缓存误称为实体级历史。

D115A–D120A 把首版的全图逐帧 RayQuery 和单一全局储量改为分层更新。暴露图以 32² tile 工作：每帧 4 个中心 3×3 近区 tile 高频循环，另 4 个远区 tile 按中心向外轮询，最多 8192 条 RayQuery；低/高档远区约 14/62 帧完成一轮。地图锚点保持稳定，玩家的入雨位置离中心约 32 格才重定位；重定位先把旧坐标清为未解析，再从玩家附近渐进补齐，禁止把旧世界坐标的深度直接当成新坐标。CPU 的 4×4 群系降水缓存滑动复用重叠区域，并采用相同的近场优先/远场轮询；未加载区块必须保持“未知”并在流式加载后重试，禁止把暂时缺少客户端区块误记为无雨群系。

湿润历史放在 GPU 的双缓冲 `R32G32_UINT` 图中，每个 texel 保存当前与前一层两组“half 深度 + 8 bit 水膜 + 8 bit 积水”，高档总常驻约 4 MiB。新屋顶出现时，屋顶作为当前层从干燥开始，原本湿润的地面移到前一层并按干燥计时退去；屋顶移除后可按深度重新匹配地面。该结构只承诺同一雨线下两层表面，不能表示任意多层建筑，也不替代移动实体的身份历史。曝光读取使用四邻域“先比较、后插值”。普通方块和树叶只允许几何法线向上的顶面取得湿润响应；草、花、作物和 `BushBlock` 等小型透雨植物是明确例外，只要方向曝光成立，其可见顶面与侧面都获得水膜。水平表面的干湿边界通过双频世界坐标 warp 破坏轮廓，垂直普通方块不再出现轴向不一致的干湿线。

湿表面采用能量分层的水膜 GGX，固定水 IOR 1.333（法线入射 F0 约 0.0204），并让 NEE、续接采样和 RR guide 使用同一模型；水膜强度/粗糙度默认 1.0/0.08。薄水膜先按临时校准项（默认 15%，范围 0–100%）把原材质微法线向几何宏观法线连续压低，水坑再按其羽化覆盖与校准强度追加压平，两段使用补集相乘合成；水膜强度与水坑羽化都连续，因此水坑—水膜、水膜—干燥、水坑—干燥三类边界不会切换法线模型。植物侧卡只压回自身竖直宏观平面，不会被错误拉向世界向上。水坑是独立的平滑、周期、世界锚定 FBM 场，受材质蓄水能力控制；选区资格只读取 closest-hit 携带的几何顶面标记，不允许材质微法线参与坡度判定，避免强法线贴图在平坦积水中打出错误的干燥亮洞。水坑不生成真实水体几何，不使用视差，也不限制为圆形或固定最大面积；“尺度”只改变噪声特征，不裁切连通水坑。积水通过独立薄水层覆盖、GGX 粗糙度、共享的湿润材质颜色端点和法线压平建立“浮在表面”的观感，而不是任意提高水 F0。毫米级清水不再使用无量纲 Beer–Lambert 项重复压暗底层；仅保留 0–25% 的额外美术压暗，默认 8%，并以补集形式叠加在普通湿润响应之后。覆盖、尺度与雨滴涟漪强度默认 0.35、8 方块、0.35。材质格式升级为 v3，可选声明吸水、变暗、水膜保持和蓄水参数，未声明时采用 0.50、0.20、0.65、0.35；原生水、玻璃与粒子不获得方块水膜。

材质响应拆为三层：普通湿膜负责变暗与 GGX 薄层，水坑负责近水平表面的独立静水平滑，雨滴冲击同时作用于水坑和原生水面。冲击采用无状态的世界锚定随机事件场：3×3 候选单元具有独立周期、空窗、落点、寿命、半径、环宽和强度，不增加状态纹理或显存；基础环宽默认 0.04 方块，并按 ray footprint 加宽、同步补偿峰值，避免细环成为 RR 可抹除的亚像素噪声。单环形状借鉴二维波动方程从圆形扰动产生阻尼波列的数学特征，以平滑因果前沿和指数衰减的交替峰谷作解析近似；没有复制外部 shader 代码，也没有引入反馈纹理。它能提供稳定的随机观感，但不是反馈浅水求解，不承诺波纹传播、碰撞或相互干涉。小草、花和作物自身可被淋湿但不遮住下方地面；叶片采用“前两层透雨、第三层遮挡”的有限近似。雨丝随机身份由绝对世界网格坐标产生，滑动窗口不能改变重叠区域的位置或相位；近相机剔除读取整段到相机的最近距离，而不只读取线段中心。Debug View 26 显示 `R=原生水材质、G=方块水膜、B=降雨暴露`；Debug View 27 显示 `R=局部水坑储量、G=程序化选区与坡度、B=最终水坑响应`。两者都是临时 review 诊断，ReSTIR 前通盘 review 时重新判断是否保留。

可见降雨采用混合方案：密集雨丝在 RR 之后、曝光之前进入 HDR 独立 pass，低/中/高实例上限为 2048/4096/8192（默认 4096），另有 0–2 密度倍率，默认速度 24 方块/秒、长度 0.7 方块。D129A 在绘制前以 compute 每实例计算一次直接光，位置函数由 compute 与六顶点程序化几何共享，禁止在每个顶点重复追踪。天空使用 GPU 归约的 `mediumSkyRadiance`；天体辐照度与统一 `Light`/alias/grid 选出的一个局部面光源均使用归一化各向同性相位 `1/(4π)`，并各自最多发射一条不透明阴影线。响应系数 `0.004×4π` 保持旧太阳项的能量尺度，旧的固定白色辐亮度下限已经删除，因此无天空、无天体、无局部光时必须输出黑。该模型是稳定而廉价的单次散射近似：支持昼夜、色温、火把/方块/生物/粒子光与直接遮挡，但不追踪雨滴 GI、反射中的雨丝，也不模拟球形水滴的高频焦散或方向性闪光。

涟漪与 RT 水花共享 D127A 确定性事件定义：Java 与 Slang 使用相同的 0.5 方块周期格、4096 方块世界周期、整数溢出哈希、事件时钟、落点和种子；密集涟漪仍在着色点局部求值，CPU 只从刚进入活动期的事件中分批选择水花子集，因此不增加 GPU 事件缓冲或 readback。CPU 用高度图反投同一倾斜雨向，只接受靠近事件中心的向上碰撞面，`Fluid.ANY` 让原生水面也能命中；最终水花 XZ 保持事件中心。设置控制相机附近活动池的目标数量 0–256（默认 96），约以 `目标数/10` 条成功碰撞线每 tick 维持；碰撞失败、遮挡、视锥和总粒子预算会让实际屏幕数量低于目标。水花生成 Fluorite 自有的中性程序化水冠与小水滴，不再主动生成原版蓝色 `ParticleTypes.RAIN`，并在 RT 粒子捕获处排除 `ClientLevel` 独立生成的 `WaterDropParticle`，避免新旧水花重叠。水花复用现有粒子 BLAS，当前只进入主摄像机光线与可选阴影，不进入反射/GI；96/256 个水花分别约为 2688/7168 个三角形。M21 不实现雪。

视觉验收前保留一组明确标注的临时校准项：全局湿润变暗（0–8×）、水膜覆盖、水坑层覆盖、水坑粗糙度、水坑额外压暗（0–25%，默认 8%）、水坑法线压平、水膜法线压平、涟漪宽度，以及 RT 水花尺寸/可见度/颜色明暗。涟漪原有强度项明确为法线扰动强度并扩为 0–3×。这些旋钮不得被误当成长期配置 ABI；验收确定数值后固化为常数并删除对应临时设置与 `rainCalibration0/1`。D127A 的目标水花数是性能/密度设置，不随美术固化一起删除。

上述代码、format-3 材质格式、`en_us`/`zh_cn` 文案和跨 pass 契约测试已完成，并于 2026-08-14 通过功能视觉验收；完整实现与验收记录见[开发日志](devlog/M21-rain.md)。以下项目继续作为回归标准：

- 晴→雨→停雨的 8/120 秒水膜与 45/300 秒水坑连续变化；正午晴空比夜间/厚云干得快，但不能突然跳变。
- 屋檐下和洞内保持干燥；新遮挡出现后，原湿地面按计时干燥而非瞬间切干。普通方块与树叶只有顶面出现水膜，普通垂直面不得出现任一轴向的干湿边界；暴露的小草、花和作物顶面/侧面都有水膜且不阻断草地下方湿润，叶冠可形成有限遮挡。
- 水坑为平滑、不规则、可连通的世界噪声场，固定在几何顶面；材质法线贴图不得改变水坑选区或打出干燥亮洞。水坑—水膜、水膜—干燥、水坑—干燥三种边界的法线都必须连续；水坑与普通水膜共享湿润颜色端点，默认额外压暗最多 8%、任意设置下最多 25%，不得再出现水坑底色骤黑。相机移动、terrain rebase、RR 开关和低/高暴露图切换时不游动、不翻转。水面不获得方块水膜；水坑和原生水面都出现尺寸可调的小型雨滴圆环，落点、间隔、寿命、半径和强度不得形成固定网格或同步节拍。
- Debug View 26 中，暴露原生水为洋红、暴露湿方块为青色。Debug View 27 中，R 应随局部积水历史上升，G 应在近水平表面形成世界固定的不规则区域，B 只在储量、选区和材质亲和度同时成立时出现；若 R/G 有值而 B 黑，优先检查材质亲和度或水坑总开关。普通画面水面不得在水材质与方块水膜之间闪烁。改变全局风向或 0–30° 倾角时，暴露、雨丝和飞溅方向一致。
- 雨丝受场景深度和屋顶暴露遮挡，密度只改变实例数而不二次放大单条透明度；零天空/零天体/零局部光时必须为黑，夜间远离光源时不得保留固定白色亮度，靠近火把、方块光、生物或粒子光时应取得对应颜色与距离衰减，天体和局部光被不透明几何遮挡时均应失去直接项。飞溅不得在有完整洞顶的洞穴地面生成。
- 性能日志中，CPU 群系缓存不再首帧突发查询整张高档 16,384 粗格；每帧仅尝试与 8 个曝光 tile 对应的最多 512 个粗格，已经解析的格子不重复查询，未加载区块在以后轮次重试。高/低档每次上传完整缓存分别为 65,536/16,384 B；流式加载持续解析期间可能逐帧上传，稳定后每个 ring slot 只补齐最新 generation。曝光每帧上限仍是 8192 条 RayQuery；随机涟漪从 4 个固定候选改为最多 9 个随机候选，其着色成本与 `gpu.rainExposure`、`gpu.rainStreak` 都必须游戏内实测，不能由候选数推断。

### 8.6 M22：后处理与调色管线

M22 已于 2026-08-14 完成功能视觉验收：实现 ACES 2、scene-linear 分区调色、景深、动态模糊、畸变、色散、暗角、RGB 颗粒、Bloom、五边形 Lens Flare 与 EV 域自动曝光。完整 D130–D155 决策、失败路线和验收记录见[开发日志：M22 后处理、输出变换与镜头效果](devlog/M22-post-processing.md)。以下只保留当前架构和长期风险。

D130A–D133A 已批准 M22 的公共边界。顺序固定为 `RR/雨丝 → 自动曝光测光 → scene-linear HDR 镜头效果 → 曝光/调色/Output Transform → UI`；测光不读取暗角或创意调色后的结果，UI 不参与景深、动态模糊、色散或暗角，DLSS-FG 接收已完成后处理的 hudless 画面。空间效果按需分配一个 display-resolution `RGBA16F` scratch，全部关闭时不分配、不 dispatch；深度和速度优先从现有 render-resolution guides 局部重建，只允许在证据表明边界质量不足后增加紧凑辅助缓冲，禁止预先常驻第二张全分辨率 ping-pong 图。

AgX 保留为兼容默认项；ACES 2 LUT 与精确模式实现固定版本的完整 Output Transform，不使用 fitted curve。scene-linear BT.709/D65 分别输出 Rec.709/sRGB SDR 或 Rec.2020/PQ HDR；官方版本、许可证、预计算参数和逐像素对照测试必须保持可追溯。65³ LUT 在极端饱和色域压缩边界存在已知误差，升级 LUT 或插值方法仍需新的质量/显存/性能批准。

镜头效果全部默认关闭，诊断视图完整旁路。动态模糊、景深与光学高亮按需复用唯一 full-resolution `RGBA16F` scratch；分层景深另按需分配 signed CoC、tile 和半分辨率 near/far 图。固定 descriptor set 代表两个合法 ping-pong 方向，禁止在已录制 command buffer 内重写。所有镜头效果都是屏幕空间近似：不能恢复画外或被遮挡辐亮度，也不等同于路径追踪薄透镜、光谱色散或物理镜片组。

艺术调色按全局、暗部、中间调、亮部和明度范围组织，烘入 dirty-rebaked 65³ scene-linear LUT；稳定帧只读取一次。自动曝光使用 256-bin log2 直方图、50%–95% 测光窗口和 EV 域指数适应；自动补偿与手动绝对 EV 是独立设置。

胶片颗粒、Bloom 与 Lens Flare 彼此独立且默认关闭。Bloom 和 Flare 使用独立阈值；Bloom 最粗层必须保持连续滤波核，禁止重新用稀疏 tap 间距表达半径。Lens Flare 的局部峰值、五边形 bokeh、固定鬼影/光环/streak 是 image-based 美术模型，不符合严格镜片物理，也不参与路径追踪能量守恒。

后处理根菜单只保留曝光、输出变换，以及同一行的艺术调色/镜头效果入口；子菜单文案只维护 `en_us` 和 `zh_cn`。已结束参数范围与 D155 九宫格修复详见 M22 开发日志。

深度语义警示：`gDepth` 从来都是 Vulkan 硬件 reversed-Z（near=1、far=0），不是线性深度；旧 Java 资源标签“guide linear depth”已经改正。镜头 shader 必须通过当前逆投影矩阵重建距离。`gMotion` 仍以 render-resolution 像素为单位；任何显示分辨率 pass 必须乘 `displaySize/guideSize`，不能直接把它当 UV 或 display-pixel 位移。

### 8.7 M23：TOML 配置预设导入/导出

M23 已于 2026-08-14 完成功能验收；D156–D159、失败路线和验证记录见[开发日志](devlog/M23-config-presets.md)。当前 format 1 是完整可移植配置而非 merge：未知、错误类型、越界、非有限值和版本不符均整单拒绝；本机路径与诊断项不进入预设。活动文件通过同目录临时文件、`.bak` 和原子 rename 替换。

应用策略默认 `RESTART`，只有显式 allow-list 为 `LIVE`。pending 值必须覆盖以后普通保存写出的旧运行值；`-Dfluorite.*` 始终拥有最高优先级。设置中心底部的导出、导入和恢复默认均横跨两栏；恢复默认必须二次确认。UI 只维护 `en_us` 与 `zh_cn`。

危险事项：浮点范围必须在设置真正存储的 float 域判断 clamp，再转回 TOML 外部域。禁止把 TOML double 与 float 边界提升后的 double 做精确比较，否则 `0.002f` 这类合法边界无法通过自身导出→导入。闸门必须同时覆盖“精确边界可往返”和“明确越界仍拒绝”。

### 8.8 ReSTIR 前全项目 review

ReSTIR 之前进行一次通盘 review，而不是零散顺手修：

- 审阅所有 shader/Java 接线、过期注释、ABI 和关闭档。
- 修复 `RtComposite.lutSampler` 生命周期。
- 结算 M11 云成本与天空遮蔽近似。
- 复核 M17 体积 MIS/default、M18 S3、M20.3 粒子 mask 成本。
- 审阅 Issue #20 诊断；只删除已无价值的 probe，未结故障依赖的诊断必须保留。
- 清理已结束诊断、临时 debug 和对应文档。
- 修复能确定的 bug；不以“准备做 ReSTIR”为理由带病进入新架构。

### 8.9 ReSTIR 整合

硬依赖：M14 完成、M18 数据层可用、全项目 review 完成。

现在不要提前做：独立面光源解析 MIS、自制 reservoir 时空复用、预采样池专项优化、过度加固可能被替换的 alias/grid。

已有可复用形状：alias O(1) 选择、每顶点固定候选、幸存者一条阴影线、降维选择目标与完整幸存者求值。整合点包括动态光、exact-Le UV、`Light` 类型/区域扩展、体积发光体 NEE 和 `UNWEIGHTED_SPEC_ALPHA_FLOOR` 的移除。所有成本画像在空域复用后重新测量。

动态记录接入采样前必须先统一直接命中语义：vanilla external-fullbright 模型层目前只向 M18 提供 side-band 代理资格，不会因 D98A 改写 primitive emission；粒子 vertex alpha 也尚未写入 RT primitive。ReSTIR 不得制造“能被 NEE 选中但正面看不发光”或代理功率含 alpha、直击功率不含 alpha的双重口径。30-bit source key 只用于候选身份，temporal reuse 必须同时验证位置、半径和 Radiance 以拒绝哈希碰撞与 entity id 复用。

全项目 review 还必须核对 `submitFlame` 的直接命中材质：当前捕获 primitive 写 emission mask 1，但可见材质通过非 emitting sprite variant 解析；没有 LabPBR `_s` 的资源包可能因此得到零材质 emission strength。M18 side-band 火焰球故意读取 emitting variant，但受 D98A 约束不能顺手改写现有 primitive。修复前先用无 PBR 资源包复现，禁止用动态灯常数补偿。

### 8.10 云向地面、水面与焦散投影

当前云只有云内自阴影。目标接口是概念上的 `cloudSunTransmittance(worldPosition)`，统一给地面、水面和焦散查询太阳透射率；它必须覆盖低层 3D 云和 D163/D169 的**单层**高云薄层。

#### 后端一：云体太阳阴影 march

从世界点沿同一有限面积太阳样本穿过世界锚定云：低层 march 3D 密度，高层在两个球壳交点直接采样并累加 `τ`，最后返回 `T=exp(-τ)`。

- 更接近当前 3D 云物理，能表达局部厚度、层高和视差。
- 有限步数和有限太阳样本仍是近似；软阴影可能带噪声。
- 每个表面光照查询增加一次 cloud march，成本最高，必须独立计时和质量档。

#### 后端二：太阳方向二维 Beer 透射率图

预先沿太阳方向对同一云光学模型积分，生成世界/太阳空间二维 `T` 图；低云来自 3D 密度，高云来自那一张薄层的光学厚度，消费者约一次纹理采样。

- 性能适合大面积地面、水面和高频焦散查询。
- 分辨率、覆盖、更新频率和投影会损失局部视差；太阳移动时需要更新。
- 禁止直接采 raw cloud coverage/type/shape noise。它没有完整沿光线厚度，也不满足 Beer 能量关系；高云形状必须先换算为其正式 `τ`。

已批准的消费者路由：地面和水面提供精确/近似切换；水底焦散始终采二维图，避免逐焦散点嵌套 march。D73 当前天气 load 只作过渡，未来替换其云输入，不重写 `1+S(focus-1)` 焦散模型。

未裁决：投影/级联、范围、分辨率、更新频率、时间滤波、面积太阳样本预算和 UI 所有权。实现前必须再次请示，不得自行选值。

### 8.11 测量欠账

- M11：云开关、低云自阴影步数（D171 后低太阳最多 +3 步）、单层解析高云、secondary 三项成本；常驻资源现为高云约 13.98 MiB + 噪声体 9.14 MiB（D172 的 mip 链 8/7）+ 天气图 32 KB + 扭曲体 128 KB。另加 D172 的步数预算与 D173 的每样本 fetch。
- M12.5：海洋场景 refit、显存和 all/near 档。
- M13：结构雾 0/A/0 和 12/24 步。
- M16：默认非零太阳角半径跨日出/日落连续性与成本。
- M17：`bench-water-top`。
- M18：固定燃烧实体、external-fullbright/材质发光实体和密集发光粒子场景，记录动态附着光、粒子 cell、上传阶段中位数及条目/字节上限。
- M20.3：`bench-particles`、1024 上限和 reflection/GI 位。

### 8.12 首个正式版后再评估

- 可选 sky-view 全天预烘焙：先独立测量 `sky-view bake` 的 GPU 时间；仅在占用显著时评估约 128 个、在地平线附近加密的太阳高度切片，并以每帧轻量 compute 插值回现有三张 sky-view LUT，保持 miss 侧采样数不增加。是否落盘、缓存格式和失效键届时重新裁决；不属于 M14 或首发必做范围。
- 粒子烟雾/爆炸尘注入统一非均质 Medium。
- 非 `SingleQuadParticle` 捕获。
- 完整双层滚动 glint UV。
- `CAUSTIC_MAX` 重审。
- 隐身穿甲、名牌 ghost。
- NRD + FSR、LOD。

## 9. 文档维护规范

### 9.1 单一项目上下文

本文件同时给人类开发者和 agent 阅读，不建立内容分叉的“人类版/机器版”。先解释是什么、为什么、下一步读哪里，再列符号和机器规则。

文档职责：

- `docs/DEVELOPMENT.md`：当前架构、文件地图、规则、危险事项、成果摘要和真实待办。
- `docs/devlog/`：已结束里程碑、实验、决策、失败路线和验收。
- GitHub Issues：未结束问题、需要证据或协作的任务。
- 专题文档：`WAVEFRONT_PLAN.md`、`PLATFORM_NOTES.md`、`MATERIAL_FORMAT.md`、`developer_guide.md`。
- 代码注释：局部不变量和最靠近实现的危险说明。

不创建与本文件重复的 `CONTEXT.md` 或 ADR。若以后需要改变这一结构，先向用户说明索引与重复维护成本。

### 9.2 结案时怎么更新

1. 完成过程和数据写入对应 devlog。
2. 主文档成果表只加一句摘要和链接。
3. 仍有效的架构/铁律更新到对应章节和代码注释。
4. 已结束待办从主文档删除；未结残余拆成明确验证债务或 GitHub Issue。
5. 检查所有“待验收/待裁决/下一步”是否仍与 Git 历史一致。
6. 只保证 `en_us`、`zh_cn`；其他语言不列入维护和验收承诺。

代码注释中的 `M*`、`D*`、`F*`、`R*` 通过 [`docs/devlog/README.md`](devlog/README.md) 查找。外部 `.claude/plans` 已被本仓库文档取代，只允许考古，不继续追加。
