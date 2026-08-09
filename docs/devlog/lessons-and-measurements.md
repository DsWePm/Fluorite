# 测量、诊断与失败经验

本日志保存跨里程碑的事实编号和取证经验。当前必须遵守的操作步骤在 [`docs/DEVELOPMENT.md`](../DEVELOPMENT.md)；这里解释这些规则来自哪些失败。

## F 系列索引

| 编号 | 已验证事实 |
| --- | --- |
| F1 | 官方映射名编译且无 Access Widener/事件依赖，使双平台迁移异常有利。 |
| F2 | 三处 Fabric 事件都有 mixin 等价入口，平台层不需要事件总线。 |
| F3 | `PackedPathSegment` 按 16 B 量化；多一个 uint 会让 48→64 B，1440p 约增加 118 MB。 |
| F4 | `nextRecord` 可由 `PATH_HAS_NEXT` 和固定索引重算回收，bit 优先于 lane。 |
| F5 | 天体 Radiance 只在 GPU 消费，CPU 大气染色是重复实现，已删除。 |
| F6 | import grep 不能证明 loader 纯净；字节码常量池和另一加载器编译才是证据。 |
| F7 | CPU 录制墙钟不能衡量 path tracer；性能只认 GPU timestamp。 |
| F8 | 基准分辨率必须显式记录；曾在 427×240 误采“基准”。 |
| F9 | 移动相机的 ±8% 噪声不能外推到固定位姿；固定世界/位姿可低于 0.1%。 |
| F10 | 位姿无法复现时绝对毫秒不可比，必须同会话翻开关并报告倍率。 |
| F11 | DLSS 档位标签曾与实际缩放比不符，按比例验证，不信名字。 |
| F12 | 代码缺陷与用户现象是两个集合，交集由隔离判据证明，不能靠阅读代码宣布。 |
| F13 | 计划中的定量缓解手段必须已测或明确标“未测”。M8 事件上限就曾被错误假定为线性。 |
| F14 | shader 编译和 `spirv-val` 只证明静态契约；后端错编必须由真实 GPU 边界 probe 裁决。 |
| F15 | probe 会改变 shader 活跃范围和寄存器分配；带 probe 成功必须 cleanup 后复验。性能实验同样受 observer effect 影响。 |
| F16 | float lane 搬整数位型必须穷举 round-trip；overlay 的 `0x7FFFFFFF` NaN 由测试而非推理抓出。 |
| F17 | 同一位姿/构建跨批次仍不可比；每批第一次运行必须整体丢弃，分子分母须同批且都不是首次。 |
| F18 | 球壳求交应按起点所在区域分支，不能按根的符号猜语义。 |
| F19 | 空画面各环节症状相同，诊断要返回组成分量和断点，不要只画结论。 |
| F20 | “看起来必炸”的浮点公式也先测量；云壳 fp32 误差远小于预期，真正根因在别处。 |
| F21 | RT 世界坐标默认相对 terrain rebase；世界固定场必须恢复绝对坐标后再锚定。 |
| F22 | 注释声称的唯一入口如果调用方还能拼等价逻辑，就不是不变量；应以 API 结构强制。 |
| F23 | 诊断采样路径必须与真实被诊断路径一致；屏幕铺图不能验证世界→域映射。 |
| F24 | Vulkan 未定义压缩 BLAS 是否保留可更新构建属性，验证层也不追踪；需要 refit 的 BLAS 不压缩。 |
| F25 | N 项求和系统必须能逐项关闭；水波平行网格由频带范围滑条一次定位，五轮代码猜测均失败。 |
| F26 | “为什么不做”的注释最危险，因为它会给后续读者一个不再检查的理由。 |
| F27 | `frac(sin())` 哈希存在实测碰撞和大坐标退化，整数哈希才满足世界锚定场。 |
| F28 | 域扭曲只打散相近尺度结构；`A/L` 过大使雅可比翻转，当前使用 0.25 安全上限。 |

## 性能测量的失败史

### 只认 GPU 时间

曾经 CPU 侧记录 `traceIndirect` 约 5 微秒，而 GPU 实际需要数十毫秒。CPU 只测到命令录制，不能用来预算路径追踪。`RtGpuTimers` 的 timestamp zone 是唯一有效来源。

### 同会话与同批次

M17 采集发现：

- 重新构建后的首次运行，`gpu.froxelBake` 约 0.582 ms；同批其余运行约 0.253–0.256 ms。丢弃每次运行前 15% 仍不足以消除这个整次偏移。
- 两批之间 `tracePrimary` 可从约 0.61 漂到 0.93 ms，同时 `traceIndirect` 向相反方向变化，不能简单命名为 GPU 降频。
- 因此每批开头先运行一个 `DISCARD`；所有比值的分子分母来自同批、同世界、同位姿且均非首次运行。

水下采集还出现一次约 2.4 ms 的中途阶跃下降。terrain 构建早已落定，数据否决了“区块流式加载”解释；只有关闭 in-scatter 的档没有阶跃，因此最多只能说它与 in-scatter 成本相关。M17 数字使用阶跃前的稳定窗口，不给未知现象命名。

### 世界必须覆盖被测代码

- 旧 `bench` 没有大水体，无法证明 M9/M17 水体成本。
- `bench-particles` 未建立时，少量随机粒子不能定价粒子阴影。
- 配置脚本曾把 `particle-shadows` 插进错误 TOML section；两次运行实际读取同一个默认值，险些报告“零成本”。脚本必须在采集前后验证 key 的 section 和生效值。

## GPU shader 运行期诊断

固定流程：

1. 先运行 `generateShaderRecords compileShaders` 和目标加载器的 resources/Java 编译，确保运行目录不是旧 SPIR-V。
2. 使用原始复现世界，不用简化场景替代真实路径。
3. 先写判据表；每个 probe 字段必须能区分至少两个假设。
4. 结构传值至少观察 queue 解包、函数入口、首次积分前、首次积分后/profile。
5. probe 成功后删除一次性读取，再以普通 profile 复验。
6. pipeline cache、GC、terrain、TLAS 和时域后处理都只能是候选；同一时间变化或同一日志出现不构成因果。
7. 工具链升级后保留规避层，比较旧/新 SPIR-V、raw probe 和 cleanup 长跑，再决定能否删除。

日志文件名必须带加载器、方案和配置，例如 `codex-neoforge-21A-packed-flags-raw-stdout.log`。失败日志和 observer-effect 假成功日志同样是资产。

## 设备故障取证

GPU device fault 没有 Java 栈时，故障地址表的资源名可能是唯一入口。M12.5 的 `prev='terrain section ... BLAS compacted backing'` 直接指向 TLAS 读取已释放 BLAS；第二次 `IP_UNKNOWN` 则来自 AS flags/尺寸不一致。两次症状不同，不能因为发生在同一功能上就假设是同一个 bug 残留。

验证层沉默不等于干净：没有正确 messenger/设置时“没有输出”和“没有问题”外观相同；KHR AS 的部分跨命令状态本身也未被 validation layer 追踪。

## R 系列历史索引

| 编号 | 风险 | 当前处理 |
| --- | --- | --- |
| R1–R2 | NeoForge quad/SpriteLookup 分歧 | digest 与双侧测试闭环 |
| R3 | 路径记录增长 | 48 B layout test 钉死 |
| R4–R5 | roughness 平方、RR guides 退化 | shader 横幅与专项 A/B |
| R6 | raygen VGPR 占用率台阶 | M17 增长实测未撞台阶；仍是未来扩展风险 |
| R7 | BSSRDF 超支 | 已发生，thin 默认 |
| R8–R9 | MIS/大气亮度漂移被错误归因 | 独立改动和全天扫描 |
| R10–R14 | mixin、配置、栈、FG/HDR、RR 前合成 | 对应缓解已落地，长期契约留在主文档 |
| R15–R16 | TDR 与 OMM 软件路径 | 能力门控，不按厂商/扩展名猜硬件 |
| R17 | 地形摘要永不全局收敛 | 只比较 `builds==1` |
| R18 | 云依赖相机 | `cloud.slang` 禁止相机位置，所有射线从自身起点求交 |
| R19 | 云 march 成本 | off/reduced/full 隔离开关已就位，正式成本仍待采 |
| R20 | 照抄参考常数 | 净室政策；4π 事故证明常数必须重推 |
| R21–R22 | 水仿真发散、域拖糊 | CFL、钳位、海绵层、整纹素锚定 |
| R23 | RIS 阴影绕过雾/焦散 | ambient visibility 与焦散两半均已修 |
| R24 | Slang 活动分类错编 | queue `pathFlags` 解码为唯一活动字；升级后仍需真实 GPU 复验 |
