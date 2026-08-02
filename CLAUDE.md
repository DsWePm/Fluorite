# Fluorite — agent 入口

Minecraft 26.2 的 Vulkan 硬件光追渲染 mod（Fabric + NeoForge，包名 `io.github.dswepm.fluorite`）。

**先读 `docs/DEVELOPMENT.md`** —— 工程知识的唯一汇总：现状、架构地图、铁律索引、方法论、工具、路线图（M15–M20）、参考项目政策、教训档案、决策日志。代码注释里的 `M*`/`R*`/`F*`/`D*`/`S3` 编号全部在那里解析。专题文档：`docs/WAVEFRONT_PLAN.md`（pass/队列 ABI）、`docs/PLATFORM_NOTES.md`（跨加载器）、`docs/MATERIAL_FORMAT.md`（资源包材质）、`docs/developer_guide.md`（构建）。

## 最高频铁律（全文见 DEVELOPMENT.md §3）

1. **任何方向性决策必须请示用户**（列选项 + 物理差距 + 性能代价），结论记入 DEVELOPMENT.md §10 决策日志。禁止擅自决定。
2. **roughness 是线性的、就是 GGX alpha，永远不要平方**（`bsdf.slang` 顶部横幅）。
3. `PackedPathSegment` 钉死 48 字节、只剩一个空 uint lane；动它之前先读 `RtPathSegmentLayoutTest` 的注释（+118 MB 的账）。新状态优先用 `pathFlags` 空位。
4. `MediumStack` 深度 2、具名字段非数组；环境介质（空气/雾/云）**永不进栈**；阴影 payload 不许增长。
5. `F:\MC\Shader\Reference` 下的 HPWater/HPVolumeCloud 是**净室参考**：只学思路与结构，代码与数值常数一个都不抄（R20：它们的常数围着能量误差调出来，照抄亮 ~12×）。
6. 未来云代码（`cloud.slang`）里**禁止出现相机位置**（R18）——云必须在反射里成立。
7. 性能结论只认 **GPU 时间戳 + 同会话比值法**（固定位姿、同存档、中段窗口中位数、记录完整配置快照）；运行时隔离开关优先于 git checkout A/B。
8. 每个开关的**关档必须等于已发布行为**，否则 A/B 无意义。

## 常用工具速查

- 基准：`tools/bench-world.sh [name]` 还原世界 → `./gradlew :fabric:runClient -PbenchWidth=1920 -PbenchHeight=1080 -PbenchWorld=<name>`，配 `-Dfluorite.rt.frameStats=true`。
- 诊断：视频设置 → Fluorite Settings → 诊断 → debug view 8–19（体积/水/LUT/可见性）；隔离开关 `water.scatter-source`、`volumetrics.segment-source` 等。
- 构建硬要求：slangc ≥ 2026.14（`docs/developer_guide.md`）；`-Xss16m` 必须是直接 vmArg。

语言约定：代码注释英文；`docs/DEVELOPMENT.md` 中文正文 + 英文符号名。
