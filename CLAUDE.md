# Fluorite — agent 入口

Minecraft 26.2 的 Vulkan 硬件光追渲染 mod（Fabric + NeoForge，包名 `io.github.dswepm.fluorite`）。

**先读 `docs/DEVELOPMENT.md`** —— 当前架构、文件地图、铁律、方法论、风险和真实待办的唯一主文档。已经结束的实现过程、实验、失败路线和决策依据在 `docs/devlog/`；代码注释里的 `M*`/`R*`/`F*`/`D*`/`S3` 从 `docs/devlog/README.md` 定位。专题文档：`docs/WAVEFRONT_PLAN.md`（pass/队列 ABI）、`docs/PLATFORM_NOTES.md`（跨加载器）、`docs/MATERIAL_FORMAT.md`（资源包材质）、`docs/developer_guide.md`（构建）。

## 最高频铁律（全文见 DEVELOPMENT.md §3）

1. **任何方向性决策必须请示用户**（列选项 + 物理差距 + 性能代价）。进行中的选择留在 DEVELOPMENT.md 待办，结案依据归入对应 devlog。禁止擅自决定。
2. **roughness 是线性的、就是 GGX alpha，永远不要平方**（`bsdf.slang` 顶部横幅）。
3. `PackedPathSegment` 钉死 48 字节、只剩一个空 uint lane；动它之前先读 `RtPathSegmentLayoutTest` 的注释（+118 MB 的账）。新状态优先用 `pathFlags` 空位。
4. `MediumStack` 深度 2、具名字段非数组；环境介质（空气/雾/云）**永不进栈**；阴影 payload 不许增长。
5. `F:\MC\Shader\reference` 下的 HPWater/HPVolumeCloud 是**净室参考**：只学思路与结构，代码与数值常数一个都不抄（R20：它们的常数围着能量误差调出来，照抄亮 ~12×）。
6. 未来云代码（`cloud.slang`）里**禁止出现相机位置**（R18）——云必须在反射里成立。
7. 性能结论只认 **GPU 时间戳 + 同会话比值法**（固定位姿、同存档、中段窗口中位数、记录完整配置快照）；运行时隔离开关优先于 git checkout A/B。
8. 每个开关的**关档必须等于已发布行为**，否则 A/B 无意义。

## 常用工具速查

- 基准：`tools/bench-world.sh [name]` 还原世界 → `./gradlew :fabric:runClient -PbenchWidth=1920 -PbenchHeight=1080 -PbenchWorld=<name>`。帧统计走配置键 `frame-stats.enabled`（`run/config/fluorite.toml`，改前先关游戏），输出在 `run/rt-frame-stats/frame.csv`。**不要在 gradle 命令行加 `-Dfluorite.rt.frameStats=true`**：`-D` 设的是 Gradle 守护进程的系统属性，而构建里没有任何 `-D` 转发到客户端 JVM（`fabric/build.gradle` 只传 `--enable-native-access` 与 `-Xss16m`），所以那个写法静默无效——曾按它跑过一次并以为统计开着。同名系统属性本身是有效的，但必须作为客户端 JVM 的 vmArg。
- 诊断：视频设置 → Fluorite Settings → 诊断 → 「调试视图」1–29（1–7=guide buffer；8–25=体积/水/LUT/可见性，22=云链路、23/24=水仿真、25=雾密度阶段；26/27=降雨材质与水洼，由 pass A 拥有；29=MC 天光场）。12–16/19/25/28 已退役且编号不复用。**给用户报选项时用界面上的中文名，不要报编号**——枚举下标是代码里的东西，用户对应不上。隔离开关 `water.scatter-source`、`volumetrics.segment-source` 等。
- 构建硬要求：slangc ≥ 2026.14（`docs/developer_guide.md`）；`-Xss16m` 必须是直接 vmArg。

语言约定：代码注释英文；项目文档中文正文 + 英文符号名。设置只维护 `en_us`、`zh_cn`、`zh_tw`。

## Agent skills

### Issue tracker

项目问题与 PRD 使用当前仓库的 GitHub Issues。具体约定见 `docs/agents/issue-tracker.md`。

### Triage labels

问题状态使用 `needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`。具体映射见 `docs/agents/triage-labels.md`。

### Domain docs

本仓库采用单一上下文，面向人类的项目文档同时也是 agent 的事实来源。阅读顺序与文档职责见 `docs/agents/domain.md`。
