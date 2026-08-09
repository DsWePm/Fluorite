# M15–M17：统一介质与体积光照

这三个里程碑把水、空气雾和 froxel 的参数与光源口径统一起来，并加入真实散射事件。云继续使用专用估计器；统一的边界是接口、能量和采样纪律，而不是所有介质共用一个 march。

## 2026-08-02 总体裁决 D1–D5

| 编号 | 决策 | 理由 |
| --- | --- | --- |
| D1 | 先用 LUT Radiance 统一水/雾天空源，再以散射顶点作为质量档 | 先移除固定颜色和艺术倍率，再增加 Monte Carlo 质量；两步可独立验证 |
| D2 | 统一接口 + 介质专属估计器 | 均质水的闭式解比 march 更快且更准，非均质雾才需要数值路径 |
| D3 | M18 先收集动态光，不采样；采样等 ReSTIR | 避免在即将替换的 alias/grid 路径上继续加固 |
| D4 | 粒子先走几何路线；体积烟雾等 ReSTIR 和首个正式版后再评估 | 将可验证的小步与大型新介质系统分开 |
| D5 | 多重散射按介质专用近似 | 雾、水、云处在不同光学厚度区间，统一一种近似会降低至少一种介质质量 |

## M15：介质接口与 froxel/marched 对齐

### M15.0 卫生

- 修复 ambient 分支把 `seg.inScatter` 覆盖而不是累加的潜伏错误。
- 删除未被 shader 消费的 `RtSkyLightGrid` CPU 扫描、上传、地址和 shader 链，`WorldPushConstantsData` 112→104 B。
- `visibility-cell-size=0` 时跳过 64×32×64 可见性烘焙。
- 同步清理多处与实际行为不符的注释。

### D9–D12：采样器和估计器

- `mediumSigmaT`、`mediumScatterAlbedo`、`mediumPhaseG` 和 `mediumProfile` 成为统一参数接口。
- `integrateSegment` 是唯一外部段积分入口；均质封闭介质走闭式，结构雾开启时环境介质走 march。
- 水和雾共享光学深度分层、段内 jitter、shadow trace、cull mask 与 seed rehash 纪律；τ→distance 保留两个 adapter。
- 分层路径被明确标记为有偏近似，不再宣称严格 `f/pdf`。
- 水体 `sun-shadow` 关闭时仍保留至多三条 source path，因为各层 `waterHitT` 代表不同的水面/洞顶路径；压成一条会制造衰减误差。

### M15.2：跨 stage 源数学

`volume_source.slang` 是无 binding 的纯数学模块，froxel compute 和 marched raygen 共用高度积分、扩散衰减、相位与 ambient source。局部雾太阳自衰减补入 froxel；`fogScatter` 两侧统一解释为 single-scattering albedo。froxel 额外积分行星大气属于有意差异。

D13 将旧 `volumetrics.intensity-scale` 语义收口为 0–1 albedo multiplier，并逐通道确保 `σs≤σt`。D14 保持空气雾太阳可见性与水体太阳遮挡为两个独立设置。D16 把水吸收/散射拆成强度和 RGB shape，旧配置迁移后逐通道系数等价。

## 水下相机前缀回归：D21–D23

用户用 RR 关闭和 `water.scatter-source=none/sky` 单变量实验确认：Pass A 已提前把水段 Beer absorption 乘入 Fresnel 叶，Pass B 却用零消光被动介质重建相机前缀，因此水散射恒零。

最终修复：

- D21：Pass A 不预扣介质能量；Pass B 以真实起始 `Medium` 一次消费完整 `SegmentIntegral`。
- D22：世界/维度变化在下一次 DLSS-RR evaluate 请求 history reset，不重建 NGX feature。
- D23：debug view 8 使用固定逐像素 seed，冻结空间方差，不再整屏同帧闪烁。

封闭水房间的实验还排除了“封闭水段被当成空气雾”：关闭水 source 后 debug 8 纯黑且不受空气雾开关影响；穿出水面后的空气雾本身合法，只是前景水散射缺失时显得突出。

## Slang 2026.14 活动介质错编：D24–D27

真正导致“进世界数秒后水散射消失”的后级故障是工具链表示问题。queue 中 WATER=1 正确，extinction 也正确，但 indirect raygen 中同时存活的 current WATER(1) 与 outer AMBIENT(2) 会被错误合成 3；profile 因此切到 ambient，水积分返回零。

失败路线必须保留，因为这些“更干净的重构”都已被真实 GPU 否决：

1. 两个 shader bool 改成单 `uint flags`。
2. ordinary `in` 改成内部 `__ref PathSegment`。
3. 复制 `MediumStack` 叶字段并调整先后顺序。
4. 同时存活两个独立 `Medium`。
5. 原地修改唯一 `seg.medium`。
6. current/outer 拆成六个 primitive 标量。

第 6 条在带 caller/pre/post 读取时一度连续正确，删除诊断后立即复发；诊断读取改变了活跃范围/寄存器分配，属于 observer effect。

最终 D27：分类状态直接从 48 B queue 的 `pathFlags` 解码成唯一 32-bit `activeMediumFlags`，低 16 位 current、高 16 位 outer；进入/退出整体更新。IOR/extinction 仍是普通活动值，打包只规避编译器，不是第二套物理模型。

验证：raw 路径 197 条、cleanup 后普通 profile 211 条真实 RTX 2080/NeoForge 长跑均零回退，terrain resident 在期间持续增长。静态 shader 编译和 `spirv-val` 不能证明该类问题已消失。

归因边界只到“Slang→SPIR-V→驱动编译链上的表示/活跃性错编”。没有最小复现和 SPIR-V 差分，不能百分之百区分 Slang 生成错误与驱动被合法 SPIR-V 触发。升级 Vulkan SDK、Slang 或驱动后都必须保留 D27，用相同存档和 cleanup 复验后再请示是否移除。

## M16：散射源 Radiance 化

`sky_medium_reduce.comp.slang` 用一个 256-lane workgroup遍历三张 192×128 sky-view LUT，按折叠方位和非线性高度轴积成立体角正确的 `1/(4π)` 上半球平均。水、marched fog 和 froxel 共享 `mediumSkyRadiance`。

旧 `water.ambient-scale`、`AMBIENT_FOG_FRACTION` 和重复 multi-scatter 加项被删除。`WorldPushData` 增加唯一 `mediumSkyRadiance`；sky-view→reduction→froxel 的 barrier 留在 `RtSky` 链内。

### D20：日出日落连续性

首轮视觉验收确认正午、黄昏、夜晚色温与亮度一致，没有 water/froxel/marched 接缝，但天体中心跨地平线时仍整项蓝↔橙跳变。

根因不是 sky reduction，而是水的 `sunY` 整项门、compute 只向天体中心发阴影线，以及“中心方向颜色 + 抖动方向可见性”的不相关组合。

修复后每次既有太阳阴影查询先采同一枚方形有限面积天体点；方向、大气 Radiance、相位、遮挡和水面 Snell 折射全部来自同一样本。不增加阴影射线数，增加两枚 RNG 和方向/LUT 运算。0-ray 可见性网格只保存一阶矩，是有意有偏近似。

## M17：散射顶点和发光体 NEE

每段按 `σt·T` 采一个散射事件；标量 `σ` 驱动 pdf、RGB 系数进入 f，灰介质下权重化简为 `albedo·(1-T)`。均质水与高度/结构雾只在 τ→位置 adapter 上不同。

同一事件承载太阳、局部水天空开放度和可选发光体 NEE，避免把一段写成多个互相独立的“首次散射”。D15 的水天空结构性缺陷因此修复：天空深度和开放度在真实散射点求值，头顶一块方块不再使整屏水散射归零，洞口横移也不再一方块级跳变。

### D29–D31 与性能

| 项 | 结果 | 裁决 |
| --- | --- | --- |
| `SegmentIntegral` 6→12 floats | 1.001× | 没撞到 R6 占用率台阶，保留 |
| 散射顶点 | 0.930×，节省 2.93 ms | 默认开启；水从最多三条阴影线降到一条 |
| 体积发光体 NEE | +20.9 ms；另一批 +22.4 ms | 默认关闭，等 ReSTIR |

D31 的判据实验把阴影 trace 静默掉后反而慢约 5.2 ms，说明射线不是成本驱动因素。负成本是 F15 observer effect：移除 trace 改变活跃范围/占用率。真正问题在逐段执行的光网格依赖链和寄存器压力，只能通过减少调用次数或 ReSTIR 预采样结构解决。

2026-08-05 游戏内验收通过：D15 修复生效，散射顶点噪声可接受，日志无异常。未完成项是 `bench-water-top`、按实际噪声需求裁决相位/光源方向 MIS，以及 ReSTIR 后重做发光体体积 NEE。

## 这一阶段形成的日志方法

介质/ABI 故障的运行期证据至少覆盖 queue 解包、函数入口、首次积分前、首次积分后/profile 四个边界。probe 本身会改变 shader，因此任何成功版本都必须删除一次性观察点后，用原始世界再次长跑。将 GPU 数据、GC、terrain 或 pipeline 状态写在同一条日志里并不证明因果。

详细通用流程已保留在主文档；当时的完整数值和 F14/F15 经验见 `lessons-and-measurements.md`。
