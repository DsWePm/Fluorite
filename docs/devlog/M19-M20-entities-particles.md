# M19–M20：实体 overlay 与粒子

这两个里程碑补齐原版光栅路径中不会自动进入路径追踪器的实体状态和粒子材质语义。M19 已完成 overlay、火焰与近似 glint；M20.1–20.3 已完成粒子发光、半透明和可选阴影。动态光源采样仍等待 M18/ReSTIR。

## M19：实体 overlay

`submitModel`、`submitBlockModel` 和 `submitItem` 解码 vanilla `overlayCoords`，经 `RtEntityCapture.currentOverlay` 传到逐 primitive aux lanes，再由 `world.rchit` 按 vanilla lerp 语义混合。

overlay 是提交级状态：进入提交时设置，结束时清零，防止 leash、文字等无 overlay 几何继承上一个实体的受伤红色。

### F16：整数位型不能盲穿 float lane

最初计划用一个 float lane 搬 `0xAARRGGBB`。白色 RGB 会把指数位填满；alpha=127 时编码成 `0x7FFFFFFF`，成为 NaN，JVM 允许规范化其位型并静默改色。

最终分为：

- `aux0` 保存 `0x00RRGGBB`，最大值不会进入 NaN 指数域。
- `aux1` 保存真正的 float 强度。

穷举 round-trip 测试抓住了这一点。任何未来通过 float lane 搬整数位型的设计都必须对完整取值域做相同测试。

### vanilla 语义

vanilla overlay 是 `mix(overlayColor.rgb, color.rgb, overlayColor.a)`，alpha=1 表示无覆盖。受伤行使用纯红；白闪随 u 坐标改变。混合在 sRGB 解码之前执行，以匹配 vanilla 截图，而不是追求线性空间的另一种结果。

## 实体火焰

`submitFlame` 按 `FlameFeatureRenderer` 的层叠 billboard 结构捕获真实 cutout 几何，因此火焰进入反射和偶然 GI。

首轮“看得见但不发光”的根因是把 `Prim.normal.w` 当强度；它实际只是发光遮罩，最终还要乘材质头里的 HDR 强度。`entityFallbackId` 的强度为零，遮罩 1 仍然不发光。修复改用火焰精灵自己的块图集发光材质，使实体火和方块火共享强度语义。

代价：火焰 billboard 随相机旋转，燃烧实体可能失去 rigid-reuse；失败表现为少一次复用，不是火焰方向卡住。

## D28：附魔 glint

用户选择近似档：一个 `Prim.flags` bit 驱动紫色 tint 和 HDR sheen，不增加几何或贴图采样。glint pass 与本体共面，因此取得 decal rank，避免 BVH 平局闪烁。

首轮常数 0.30 把 HDR 发光量误当 0–1 乘子，远低于材质管线的 `EMISSIVE_STRENGTH=5` 基线；修正为 2.0，使其低于真实发光方块但可见。该值是 `PROVISIONAL` 美术常数。

完整 vanilla 双层滚动 UV 仍是可选后续：视觉更忠实，但需要额外几何或贴图采样、UV 变换和 lane/材质位置。

2026-08-05 复测通过：受伤红闪、无 overlay 实体、火焰反射、自发光、glint 和共面稳定性均正确。

## M20：粒子

粒子不索引普通 `MaterialHeader`，所以发光、stochastic alpha 和阴影能力必须显式传输，不能假设实体材质机制自动生效。

### D32 / M20.1：发光超额法

vanilla 发光粒子通过 `getLightCoords` 把自报方块光抬到世界方块光之上。最终判据为：

`particleEmission = particleReportedBlockLight - worldBlockLightAtParticle`

火焰在暗洞或火把旁都保留自己的超额；经过火把的烟雾自报值与世界光相同，减完为零，不会把火把重复计入一次。相比粒子类型表，这让 vanilla 决定什么发光，模组粒子也自动兼容。代价是每粒子一次 level brightness 查询。

粒子没有材质头可提供强度，因此捕获侧保存最终 HDR emission，而不是像实体火焰那样保存遮罩。

### M20.2：半透明

旧粒子全部掉进二值 alpha cutoff，烟雾边缘因此变硬。`SingleQuadParticle.Layer.translucent` 现在经 primitive flags 驱动与实体相同的 stochastic alpha。

### M20.3：阴影

粒子使用独立 `CULL_PARTICLE_SHADOW` TLAS 位，只进入阴影线；反射和 GI 仍关闭。billboard 面向相机，从反射方向看可能侧对，因此“挡光”和“在反射里出现”不能共用一个开关。

水体向上探测的三类 visibility 没有加入粒子：它们测水柱深度和天空开放度，飘过的烟雾不应改变几何水深。

两次性能采集均无效：第一次脚本把 key 写进错误 TOML section，第二次 `bench` 世界只有约 15% 帧出现少量粒子且不一定挡住光路。没有证据前粒子阴影默认关闭。

## 尚未完成

- `bench-particles` 固定烟柱世界、`MAX_PARTICLES=1024` 上限和反射/GI 位的成本裁决。
- M20.4：将发光粒子按空间 cell 聚合，交给 M18 动态光收集层。
- 非 `SingleQuadParticle` 粒子仍未捕获。
- 自发光实体和粒子目前只有直击/偶然 GI；`RtLightCollector` 只收地形 quad，它们不会被 NEE 选择。真正成为光源需要 M18 数据层和 ReSTIR 采样。
