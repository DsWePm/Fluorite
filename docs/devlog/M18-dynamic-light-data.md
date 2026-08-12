# M18：动态光源数据层

M18 建立了动态有限光源从游戏提交语义到统一 `Light` 记录的 CPU 数据链。它只收集、聚合、编码和上传，不参与当前帧的 alias/grid、NEE 或 GI；真正采样统一留给 ReSTIR，避免把新功能接入即将替换的旧选择器。

## D98A–D100A：第一条手持光 tracer bullet

PR #27 先接通主手/副手中发光 `BlockItem`：资格来自默认 `BlockState.getLightEmission()>0`，颜色、覆盖率和 HDR Radiance 来自同一 state 解析出的 `EmissionSummary`、`emissionStrength` 和真实 item tint。每只手的真实提交 quad 聚成一个有限球，保持 `πA·Le` 总功率。

动态记录复用 32 B `Light` ABI：bit31 为 sphere，radius 位于 `halfUxy` 低 half，`le` 为 R11G11B10。记录上传到 graphics-timeline 守卫的独立逐帧 buffer，但不进入 `WorldPush`、descriptor 或 shader。地形 collector 的 80 B worker 记录和 exact-Le UV lanes 保留，避免 ReSTIR 接回纹理精确 Radiance 时重新扫描 footprint。

## D101A：无白名单的实体发光资格

实体附着光只接受三种可证明来源：

- `submitFlame` 实际提交的火焰 quad。
- 模型 submission 自报的 block light 高于实体所在地环境 block light。
- 资源包材质明确提供的 LabPBR/override emission。

Minecraft 的 Glowing 状态描边只是轮廓效果，不是辐射源；glint 是反射/艺术 sheen，也被明确排除。这样无需维护生物名称表，模组只要沿用提交语义即可进入同一接口。

外部 fullbright 的 0–15 block-light excess 仍只是游戏语义到 HDR 基线的代理，不是绝对辐射测量；材质明确自发光时，材质语义优先，避免两者叠加。

## D102A：每实体四类独立代理

每个实体最多产生左手、右手、火焰、身体自发光四个球。合并发生在类别内部，不把不同位置、颜色和语义的来源压成一个球。火焰和身体都从真实提交 quad 的面积、纹理均值、覆盖率和 tint 反算球 Radiance，保持总功率。

完整实体纹理和非 block atlas sprite 使用资源周期缓存的 alpha-premultiplied 线性 RGB 均值；资源重载同时清除缓存。无法读取的动态纹理宁可不生成代理并增加 `dynamicTextureSummaryMisses`，不回退到固定白色或橙色。

## D103A / M20.4：世界锚定粒子 cell

前 1024 个 `SingleQuadParticle` 按世界坐标 `floor(x,y,z)` 进入 1×1×1 block cell，和相机可见性解耦，因此粒子移出屏幕不会立刻失去未来照明资格。每格输出一个球：

- 发光资格继续使用 M20 的 `particleReportedBlockLight - worldBlockLightAtParticle`。
- 颜色来自当前 sprite 的 alpha-premultiplied 线性均值乘粒子 RGB tint、alpha 和统一 `EMISSIVE_STRENGTH`。
- 几何面积来自实际 billboard 半尺寸；球心按发光功率加权。
- 半径同时包围格内位置分布与单粒子 billboard 支撑范围，再降低球 Radiance 以保持格内总 `πA·Le`。

CPU 为 O(P)，每个粒子一次自报光查询和一次世界 block-light 查询；sprite 像素只在资源周期首次遇到时扫描。空间聚合误差受一格 cell 限制；它是有限面光代理，不是粒子体积发光模型。

## D104A：稳定动态 source key

Sphere 的 `section` bit31 保持类型标记、bit30 保留，低 30 bit 写确定性 source key。实体 key 来自 entity id 与四类 source kind；粒子 key 来自世界 cell 坐标。它跨帧、跨 rebase 稳定，不增加记录尺寸或 GPU 读取。

30-bit 哈希不是无碰撞身份。未来 ReSTIR temporal reuse 必须同时验证位置、半径和 Radiance；实体 id 被复用时也使用同一验证拒绝旧 reservoir。若实测证明碰撞不可接受，再裁决 64-bit sidecar，不能悄悄占用 bit30。

## 成本边界与诊断

静态最坏上限是 1024 个普通实体各四灯加 1024 个粒子 cell，即 5120 条、160 KiB/帧；真实场景通常远低于这个上限。当前 GPU 光照成本严格为零，因为 buffer 未绑定；成本只有 CPU 判定/聚合、一次 host-visible 写入和一次 flush。

新增诊断包括：`dynamicFlameLights`、`dynamicBodyLights`、`dynamicBodyAuthoredCandidates`、`dynamicBodyFullbrightCandidates`、`dynamicParticleCandidates`、`dynamicParticleCells`、`dynamicTextureSummaryMisses`，以及阶段 `entity.dynamicAttachedLights`、`entity.dynamicParticleLights` 和上传阶段 `entity.dynamicLights`。

契约测试覆盖 32 B 编码、bit30 保留、source key、普通球与 cell 包围球的功率守恒。Fabric/NeoForge Java 编译、Shader 全量编译和 Fabric 测试通过。用户明确决定跳过本轮游戏内性能采集；固定动态光场景的运行时中位数转入 ReSTIR 前测量欠账。届时必须覆盖燃烧实体、发光生物和密集发光粒子，普通世界不能替代该数据。

## ReSTIR 接入前的硬边界

M18 不改变现有 primitive，因此外部 fullbright layer 的代理资格当前不会反过来改变直击表面的 emission。ReSTIR 真正采样这些记录前，必须让直接命中与代理共用同一 emission 语义，否则会出现“可被 NEE 选中但正面看不发光”的能量不一致。

粒子当前 RT primitive 也没有保存 vertex alpha；M18 的代理按真实粒子 alpha 缩放功率，而直接命中路径仍属于 M20 的 review 欠账。该差异必须在 ReSTIR 前全项目 review 中结算，不能通过调低动态灯常数掩盖。

另一个需要独立复现的旧风险是实体火焰：直接命中 primitive 写了 emission mask，但现有可见材质解析的是非 emitting sprite variant；无 LabPBR `_s` 时材质 strength 可能为零。M18 火焰代理读取 emitting variant以获得正确 side-band 数据，但 D98A 禁止本里程碑借机改变画面。review 必须在无 PBR 资源包下验证并修正直接命中路径。
