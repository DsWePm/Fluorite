# M00–M08：平台、渲染地基、BSDF 与 BSSRDF

本日志记录 Fluorite 从单加载器项目成为 Minecraft 26.2 双平台 Vulkan 路径追踪器，以及后续材质和介质工作的地基。当前规则和文件入口见 [`docs/DEVELOPMENT.md`](../DEVELOPMENT.md)。

## M0–M4：Fabric / NeoForge 迁移

迁移采用“极小平台层 + 共享 common 源码”的结构。`common/` 不是 Gradle 子项目；Fabric 和 NeoForge 各自把它作为源码目录编译。加载器差异被压缩到 `platform/` 的 paths、quad 与 sprite 接口，没有再造事件总线。

关键结论：

- 官方映射名下源码本身可双侧编译，旧 Fabric 三处事件都有 mixin 等价入口，因此事件层不是必要抽象。
- 判断 common 是否纯净不能只 grep import；接口注入可能没有显式 import。最终闸门是字节码常量池扫描 `verifyCommonIsLoaderAgnostic` 加 `:neoforge:compileJava`。
- 地形摘要只能比较 `builds==1` 的稳定 section。流体世界会持续变化，不存在“全世界完全收敛”的时刻。
- NeoForge `hidesNeighborFace` 的少量摘要差异是双方各自正确的加载器语义，已记录在 `docs/PLATFORM_NOTES.md`，不作为 bug 修复。

M0–M4 的地形摘要基线为 986/989 一致；平台细节与复测流程保留在 `docs/PLATFORM_NOTES.md`。

## M5：wavefront 渲染地基

M5 固定了此后所有渲染工作的结构：

- Pass A 只解析相机首个电介质边界、Fresnel 分裂和 RR guides。
- Pass B 独占弹射、着色和所有 `SegmentIntegral`。
- `PackedPathSegment` 固定 48 B；通过 `PATH_HAS_NEXT` 和可重算索引回收了 `nextRecord` lane。
- 修复天空逃逸段在体积衰减之前 `break` 的问题。最长的环境介质段正是面向天空的段，积分必须发生在退出之前。
- ABI 反射记录进入 codegen 和 layout tests，避免 Java/Slang 只改一侧。

详细 pass/队列契约见 `docs/WAVEFRONT_PLAN.md`。

## M6：统一介质框架的起点

M6 建立 `Medium`、深度 2 的具名 `MediumStack`、`volume.slang` 和唯一段积分入口。封闭均质介质与环境介质从一开始就刻意采用不同估计器：前者有精确闭式解，后者需要高度积分；统一的是接口、能量契约和采样纪律，不是强迫它们共用同一个循环。

当时同时确立了三条长期规则：

- 环境介质不进栈；无色电介质不能靠 `ior==1 && extinction==0` 被误判为空气。
- 阴影 payload 不增长，体积透射率由唯一包装器累积。
- `integrateSegment` 是高频热路径，任何新增工作都必须按“每弹射段都会付费”评估。

解析高度雾的首轮实测开销约 `+0.622 ms`。

## M7：Disney principled BSDF

M7 加入 sheen、clearcoat、各向异性、粗糙透射与太阳 MIS，并保留降维 RIS 目标、完整幸存者求值的无偏结构。最重要的代码契约是：本项目的 `roughness` 已经是线性 GGX `alpha`，从 Disney 论文转写公式时不得再次平方。

实施中确认：

- 材质 JSON 写入的 roughness 可能被 LabPBR 纹理覆盖；A/B 前必须证明字段真的生效。
- `ggxD` 的 `1e-7` 曾是临时能量钳，MIS 正确后改成真正的除零保护。不能把旧钳制重新当稳定器。
- 两个太阳是美术与采样的有意分工：大日盘负责布景，小面积太阳负责 NEE。
- 原版玻璃 roughness 0.0025 高于旧镜面阈值；照计划字面统一阈值会静默停用玻璃分裂，因此透射阈值保持独立。

实测 `gpu.traceIndirect` 为旧路径的 `1.283×`，低于当时的 1.5× 成本门槛。

## M8：随机游走 BSSRDF

随机游走实现正确，但成本实验推翻了“事件数近似线性”的计划假设：事件上限 4→2 只节省约 17%，因为前两个事件已经承担大部分开销。真正的杠杆是进入游走的频率，不是进入后少走两步。

最终裁决：

- `thin` 为默认路径。
- random walk 保留为高质量档。
- 游走实测 `1.567×`，超过 1.5× 门槛。
- `subsurface.slang` 中两个 `PROVISIONAL` 常数尚未标定，不能把当前观感当物理校准结果。

## 这一阶段留下的长期经验

| 编号 | 结论 |
| --- | --- |
| F1–F2 | 官方名源码和 mixin 等价入口使双平台迁移不需要大型适配层。 |
| F3 | `PackedPathSegment` 按 16 B 量化；多一个 uint 会让 48 B 进位到 64 B，在 1440p 增加约 118 MB。 |
| F4 | 能用 bit 和可重算索引表达的状态，不应占用新 lane。 |
| F5 | 天体光只由 GPU 消费后，CPU 大气染色应删除，统一为 GPU `dyeCelestialLight`。 |
| F6 | loader 纯净性靠字节码与双侧编译证明，不靠 import grep。 |
| F7–F10 | 路径追踪器性能只认 GPU 时间；分辨率、世界、位姿和同会话比值必须明确。 |
| F11 | DLSS 档位名称可能错误，按实际缩放比核验。 |
| F13 | 计划中的定量缓解措施若未测量，必须标为未测，不能当承诺。 |
