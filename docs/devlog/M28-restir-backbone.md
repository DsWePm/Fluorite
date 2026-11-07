# M28：统一 ReSTIR 路径复用骨架与雾照明重构

进行中。计划、已批决策与切片见 `docs/DEVELOPMENT.md` §8.13；论文依据见 `Papers/M28-restir-backbone-survey.md`。本日志随切片落地追加。

## S1：可见性网格 → 方向性天空场（已落地，2026-09-03 通过游戏内验收）

提交 `5dca3d8`（方向场 + 扇区归约 + 双模烘焙）、`a19be77`（出格雾段读钳制场）、`8eca9f4`（开关上诊断页）、`d10cbe2`（+Y 面钳制 + D194 例外移交水路）。开关「方向性天空场」「出格雾段遮挡」均在诊断页，Live，关档 = 已发布行为；方向场切换强制网格历史复位。

### 验收记录（2026-09-03，游戏内）

- **封闭深洞远雾**（有效站位，非洞口）：开「出格雾段遮挡」后约 32 格外的发白雾圈消失，远近一致变暗；野外远景无变化。
- **深水回归**（D194 当年的场景）：水下 16 格以上散射持续存在，无固定深度台阶。
- **高洞顶（#73 直接目标）**：+Y 钳制开启后，头顶 16 格以上的雾不再按露天照亮。
- **方向性**：单侧开口洞内，靠近洞口的雾更亮、朝洞口渐强。
- `gpu.visBake` 的 4× 射线代价（131k→524k/帧）**尚未按铁律 7 实测归档**，是 S1 剩余的唯一测量欠账。

## D209：+Y 出界从「返回全开」改为随开关钳制，D194 例外移交水路（用户批准方案 B）

D178 当年留下 +Y 不夹，两条理由：开阔度沿 +Y 单调非减（顶格是下界，夹取把下界当测量值）；水下天空项在水面上方半格取样，相机深于网格垂直半程时该点出格，顶格还泡在水里，夹取读「有顶」会把深水散射整个掐掉（实测回归，固定深度台阶）。

用户 2026-09-03 现场复现了这条残留的样子（高洞窟里往上看雾照旧亮），在 A（网格加高）/B（+Y 也夹、水路自兜）/C（不动）中裁决 **B**。落法：钳制挂在「出格雾段遮挡」同一开关下（关档 = 逐字旧答案），`waterSurfaceSkyOpenness` 自己判断取样点出网格顶并返回 1.0——例外从「被钳制函数普遍承担」变成「造成它的消费者自己承担」，两半由 `RtCelestialMediumSamplingTest` 同一测试钉死、不许单独落地。**深水散射复验是本开关的强制验收项**（已过）。

踩坑记录：改采样函数时曾把标量模式的 φ 字面量顺手改写（`6.28318530718*u2` → `1.57079632679*(u2*4)`），数学等价但末位不等——off 档方向序列会有 last-bit 漂移。铁律 8 的「逐位等价」审的是字面量级别，不是数学级别。当天修回：扇区模式在调用端乘 0.25 归一，采样函数一字未动。

## D210：有顶水体的天空项随相机位置跳变 —— 段起点采样的既有近似，记档接受（用户裁决 A），拆段正解入 S6

### 现象与机制

用户用「散射光源」（水设置页的太阳/天空隔离诊断）发现：有顶水体在「仅天空」下，**从露天侧看顶下的水有散射、潜到顶下看同样的水就没有**，边界随相机位置跳变。

机制不是网格错：水闭式解对**每个段只测一次**天空 gate（段起点一次 up-ray + 一次 `waterSurfaceSkyOpenness`），然后解析积分整段。从露天侧看，那些水下段从露天侧入水、延伸进顶下——起点 gate 读开阔，「开阔」被带完整段（含顶下部分）；潜入后，相机前缀段起点就在顶下，gate 读封闭 → 0。同一片水随相机被两种切段渲染，切换即跳变。

这是 M13 头注释里「遮挡边界把整段一起翻转」的**闭式解版本**：雾 march 用逐子步采样修掉了它，水闭式解为保解析积分仍然一段一个 gate。它不是 D210 当年否掉的「二值射线」伪影——檐下/檐外 1–2 格的平滑梯度证明网格部分在正确工作——而是段结构的近似。

### 裁决

- **A（已执行）**：记为水体闭式解的既有已知近似，不单独开工。
- **C（入 M28 S6 范围）**：段跨开阔度遮挡边界时拆段分别积分——雾 march 三段式的水体版。动水路径核心，等 S6（雾照明接统一链路，水体路径本来要动）一并做，届时自备开关与验收。

### 深度对照的旁证

同一顶下潜到 16 格以下散射回满额——gate 出网格顶走 D194「上方=开阔」回退。已知取舍：浅顶水下暗、深顶水下亮，同一顶两种答案方向不一致。若 S6 的拆段落地，这条不一致一并消除。

## D211：开关位与 M17 碰撞 —— 两开关自落地起被焊死 ON（帧统计仪表抓出）

### 经过

S1 验收后用户报告「开关与否画面没有明显变化」。受控重测（带新加的 CSV 开关状态列）得到硬数据：`gpu.visBake` 对五段开关状态**零响应**（段 2/3/4 统计完全相同：底 0.282ms、尖峰 22–24%；段 1 的差异伴随 traceIndirect 64→44ms，是场景变化不是模式变化）。静态复查 flags 组装、烘焙派发、shader 位三处均「无误」——直到在组装代码里看到一条不属于自己的 `flags |= 1 << 27; // sample one scattering event per segment (M17)`。

### 根因

两个开关最初以 `WorldPush.flags` bit 27/28 落地（`5dca3d8`/`a19be77`）。当时的位审计**只 grep 了 Slang 侧的 `1u << 2x` 模式**，断言 27/28 空闲。实际上 Java 组装侧早有属主：**bit 27 = M17 SCATTER_VERTEX（默认开），bit 28 = 其嵌套的 VOLUME_EMITTER_NEE（用户 D207 起常开）**。后果：两个开关自落地起被常开的旧位焊死在 ON——烘焙一直跑 4 射线方向模式、march 一直跑扇区天空项、出格钳制一直生效；UI 拨动无效；**铁律 8 在此期间被破坏**（S1 默认档不等于已发布画面）。反向污染同样存在：拨「方向性天空场」会顺带置起 M17 的散射顶点位。

### 修复

两个开关迁入 WorldPush 专属新 lane `volumetricSwitches`（bit 0 方向场、bit 1 出格钳制；struct 1248→1264 B，偏移 1248）。Slang 五文件 8 处消费者改线；Java 撤销两个 flags 位、按位置传新参；layout test 钉 1264 与偏移；M17 两位的所有权恢复独占。CSV 两列保留（记 config 值，与 lane 同源）。

### 教训（踩坑归类：断言先于验证的审计版）

1. **「位空闲」必须枚举该字的所有写入方，跨语言边界**。Slang 读侧 grep 不见 ≠ 空闲；Java 的 `1 << 27`（无 `u` 后缀）不在当时 grep 的模式里。任何一个字的占用清单都要以「写它的代码」为准，而不是「读它的代码」。
2. **捕获必须自带归因**。本次若无 CSV 开关列，「开关没反应」会继续被归因为「场景里看不出差别」（第一次会话就是这么错过的）。D208 的教训第二次应验后才变成机制。
3. **测试 A/C 的通过判定作废重跑**：两臂当时完全相同（都 ON），用户看到的「通过」是方向场/钳制的常开效果，不是 A/B。修复后需真 A/B 重测；测试 B（深水）与位无关，仍有效。

## D212：S1 的 visBake 实测归档 —— 开关验证闭环（2026-09-04）

D211 修复后的受控会话（`frame-20260904-s1-acceptance.csv`，11542 帧，1080p，RTX 2080 Mobile）：

| 段 | 「方向性天空场」 | `gpu.visBake` 底值 | 远射线尖峰占比 |
|---|---|---|---|
| 0–912 | 关 | 0.084 ms | 0.0% |
| 913–5881 | 开 | 0.278 ms | 24.8% |
| 5882–11541 | 关 | 0.094 ms | 0.0% |

同会话 A→B→A：开关状态与 GPU 成本严格同步，方向场代价 **+0.19 ms/帧**（比值 3.0×；外推为 131k→524k 射线 ≈ +0.17 ms，吻合）。尖峰群体（扫到远处的射线 ×4 暴露）随开关出现/消失，是模式切换的第二个独立印证。标量档 0.084–0.094 ms 与旧账本 0.042 ms 的差异属场景/时钟口径，不构成矛盾——比值法下只认同会话。

S1 的测量欠账就此结清。D211 期间被作废的视觉 A/B（测试 A/C）已用真开关重跑；「出格雾段遮挡」在会话内完成四轮切换对照。

## D213：快速移动时前方雾闪烁 —— 领先边缘新鲜格子被钳制放大（邻居播种修复）

### 用户隔离实验钉死的机制（2026-09-06/07）

五步隔离（主视图 / 体积天空开阔度调试图 / 两开关逐一切换 / 密度扫描）：

| 观察 | 结果 |
|---|---|
| 主视图异常 | 仅移动时出现，站定即消 |
| 开阔度调试图 | 移动时同样闪，且 pattern 与主视图雾暗部吻合 |
| 关「方向性天空场」 | 异常仍在（与方向场无关） |
| 再关「出格雾段遮挡」 | **主视图异常消失**（钳制是放大器） |
| 雾密度扫描 | 主视图异常只在中段密度可见（太高被近雾饱和遮挡、太低雾贡献可忽略）；调试图闪烁与密度无关 |

**机制**：网格锚定相机、按整格平移。移动时运动方向最前沿的一排格子刚进入窗口、无重投影源，其值是 1–2 次抛硬币（部分开阔处方差最大），逐帧 0↔1 晃动。「出格雾段遮挡」让远雾恰好读这个方向的边界格——最新鲜的格子——于是远雾跟着闪、成块明暗（用户截图 .11 的前方暗斑即其形状）。站定后窗口不再平移，边界格收敛，异常消失。

**调试图站定也闪的两个已理解成分（记档不动）**：①调试图把收敛后的微小 EMA 波动经 7 色分档放大成颜色跳变（纯显示）；②水下格子的天顶射线打到逐帧形变的波面（BLAS 每帧 refit），其开阔度真实地随波晃——水路天空项有水面以上取样保护，画面不受影响，仅调试图可见。

### 修复：邻居播种（用户批准）

重投影找不到源的新格子，沿窗口位移反方向读一个窗内有历史的邻居，以其值作先验、置信度 `SEED_CONFIDENCE = 4` 起步继续 EMA——硬币帧被平滑延续取代，格子仍收敛到自己的答案。标量模式种 R+G，方向模式种 4 bin + meta 计数。**只有无历史分支执行播种：有重投影源的格子逐字节走原路径，稳态（站定世界）不受影响，故不设开关**。reset 帧全网格无有效邻居，不播种（罕见：传送/改格尺寸/切模式），一秒收敛照旧。代价：仅新鲜格子（领先边缘一排）每格多一次 history fetch；深度移动时种子逐帧改进（种子源自己也是前几帧的种子）。

### 验收

游戏内复验待做：中段密度、快速直线移动看前方——闪烁/暗斑应显著减弱或消失；站定 3 秒收敛行为不变；开关 A/B 与 D212 的结论不受影响（播种与两开关正交）。

## D214：出格钳制的已命名代价被高密度放大 —— 用户裁决第二级粗网格（S1.5）

D213 落地后用户复验：移动闪烁完全消失；但一个**站定即可触发、位置锁定**的暗区显现——发生在山体侧坡或有遮挡的方向，换位消失、回位复现，仅「出格雾段遮挡」开时存在。开阔度调试图显示该方向边界格读 0.6–0.8（部分开阔），形状贴着山体轮廓。

**机制**：D178 落地时就写明的代价——边界格无法区分「山后是露天」和「洞里还是洞」，山侧坡挡掉边界格半球的一部分，远雾乘上这个分数。用户常用雾密度 14 把它从"微妙"放大到"可见"。调高格尺寸（现有旋钮）无效——边界只是推远，部分遮挡的边界格依然存在。

**用户裁决**：第二级粗网格（文档里一直推迟的"远场结构"候选，锚点条件「S1 落地后按需重评」已满足）。

### S1.5 实现

- **结构**：64×32×64 @ 8 格/格 = 覆盖中心 ±256/±128/±256 格；**世界锚定**（不随相机平移——领先边缘问题不再出现），中心迟滞 64 格（雨暴露图先例），重定位一帧复位。
- **自举**：无历史的远格以**细网格钳制值作种子**（置信度 4）——远场从"今天的答案"出发收敛到实测，最坏情况 = 现状，无回归窗口；重定位后同样自举。
- **刷新**：轮询 1/32 每帧（约 4k 格，标量 4k / 方向 16k 射线/帧），FAR_ACCUM_MAX=16（约 8 秒收敛/遗忘）。
- **消费者**：march 出格段经 `beyondGridSky` 缝隙——远场（含方向模式扇区加权）→ 钳制回退 → 发布态全开，三档策略一个接缝；水路/froxel 本片不动（后续候选）。
- **成本**：显存约 4 MiB；`gpu.visFarBake` 独立时间区；`volumetricSwitches` bit 2，不占已满的 flags 字；WorldPush +16B → 1280（`visFarGridOrigin` lane）。
- **已知风险（命名）**：细/粗边界接缝——粗格 8 格均值对细格面值的跳变，游戏内可见再加过渡混合；距离 >256 格的雾仍钳制到远场边界（远优于细边界，且该距离透射率已很低）。

## D215：S1.5 的调试视图事故与远场格尺寸收敛（2026-09-09）

### 调试视图「没有 31」的三重根因（用户三次报告全部命中）

用户连报「找不到 31」「顺序不依次」「一共 21 个选项」。我方最初误判为构建新鲜度问题。用户授权直接操控游戏后实证：**调试视图从来是循环按钮**——`RtVideoOptions.debugView()` 用 `OptionInstance.Enum(List.of(0..11, 17, 18, 20, 21, 22, 23, 24, 26, 27))`，手工维护的 **21 个值，31 不在其中**。lang 键存在无关紧要——列表是视图可达性的第四份副本，没人记得更新它。连点 31 次落在 10 号（31 mod 21 = 10），数学钉死。

**修复**：按用户要求改为 `IntRange(0, 64)` 真滑条——新视图随编号落地即自动可达，不再维护列表；formatter 对退役编号显示「视图 N（已退役）」而非泄漏键名。雨面契约测试的钉子随重构迁移。教训（D211 的 UI 版）：**视图注册表只允许存在一份；第四份副本就是丢失视图的地方**。

### 远场格尺寸 8→4

滑条修通后用 31 号视图（固定 96 格采样）实测：靠近山体全屏蓝（band 0，开阔度 0.05–0.24），远离后橙黄（band 2–3），全程头顶无遮挡、全程室外。梯度证明远场在正确工作——山体方向的封闭是真封闭；残余的「还是暗」定位为 **8 格粗体的坡旁偏差**：露天空气点的三线性邻域多半是含地形粗格，读数被拉低。

**修复**：`FAR_CELL` 8→4（同点阵同射线成本，reach ±256→±128，仍覆盖雾可见距离；用户在游戏内确认该距离足够）。偏差随格尺寸减半。复验待用户进行。

### D215 复验补充（2026-09-09）：4 格也无改善——坡旁偏差假设被否，问题挂起

`FAR_CELL` 8→4 后用户复验：暗区依旧。**「粗格把坡遮挡糊进邻格」的假设被否**——分辨率翻倍毫无效果说明远场值低不是邻域混叠，而是那些格子本身就收敛在低值（或烘焙有系统性错误）。用户裁决先记档继续其他工作。

**挂起时的已知事实**（全部经过隔离验证）：
- 暗区仅「出格雾段遮挡」开时存在（钳制/远场是放大器），位置锁定，室外、头顶无遮挡；
- 31 号视图（96 格采样）：近山全屏蓝（band 0，0.05–0.24），远离后橙黄（band 2–3），梯度连续；
- 移动闪烁已由 D213 邻居播种消除；8 格与 4 格无可见差异。

**下一步探针（重启调查时第一件事）**：在暗区位置做一次**真值对照**——临时探针从同一雾点投 64 条半球射线直接算开阔度，与 31 号视图的远场读数并排显示。真值 ≈ 0.2 → 远场是对的，暗是物理（该点真的看不到多少天空），转美术/密度权衡；真值 ≈ 0.8 → 远场烘焙有系统错误（格心入几何、坐标空间、EMA 链路逐项排查）。

## D216：S2 pass B 第一步 —— 路径 reservoir 落显存，弹射循环记录初始候选（2026-09-09）

S2 设计定稿（用户裁决 B）与 scaffolding 之后的第一个集成片：**缓冲分配 + 初始候选记录**，尚无消费者——本帧路径被写进 reservoir，但没人读它，因此开档画面与关档仅存在舍入级差异，时域合并（重放 + GRIS）留给下一片。

### 落地内容

- **缓冲**：`composite.path-reservoir` 开（RtComposite.ensureOutput）每像素每 parity 一条 48 B 记录，1080p 约 199 MiB；开关进 ensureOutput 重分配条件（拨动即时生效，不等 resize）。关档 = 不分配、WorldPush 地址推 0，显存零开销。分配即 `vkCmdFillBuffer` 清零（`m>0` 是有效性判据，垃圾内存可能撞出伪 reservoir）。frame.csv 新增 `pathReservoir` 列（D211 规则：捕获与生效同源）。
- **WorldPush**：尾部追加 `pathReservoirAddr`（偏移 1280，struct 1280→1296）。放 WorldPush 而非已满的 push-constant 块（块内只剩 4 B，半个地址）；**地址 0 就是开关的 shader 侧全部**——没有第二个旗标可以与之失配。
- **记录（world.rgen tracePath）**：
  - `pathSeedBase`：循环流基（`seed ^ sampleIndex*C → pcg` 之后、首个 draw 之前）快照——重放从这里开始，存中段状态只会重放出路径尾部。
  - **重连顶点** = 首个「延续方向密度有限」的不透明着色顶点（`!exactSpecular`）；电介质链与粒子天然不满足（在到达不透明分支前 `continue`），随机游走 SSS 臂显式除名（出射方向无解析密度，雅可比无从谈起）。到达弹射上限的顶点不记录（无延续即无后缀）。
  - **后缀恢复 = 快照差分**：重连顶点处快照 `L`（前缀部分和）与选择延续后的 `throughput`，循环结束后 `suffix = L − L_snapshot`、`li = suffix / T_post`。**12 个 `L +=` 累加点一字未动**——开档的算术与关档逐位相同（同样的加法、同样的顺序），差异只在循环后的这一块。减法恢复的舍入误差 ~ulp(L_final)，对 MC 估计可忽略；RGB 各通道 max 0 钳掉差分可能出现的负分量。
  - 记录字段：seed 基、重连位置/着色法线、wi（oct16×2）、li（Rgb9e5，**相对值**——接收方用自己的前缀吞吐重新加权）、m=1、targetSum=luminance(suffix)（= 候选在其母顶点的贡献，GRIS 合并所需）、reconIndex=hitDepth（重放深度对齐用）。**只 leaf 0 / sample 0 记录**，与「每像素一槽、无 path 平面」的布局一致（spp>1 时共享槽，v1 接受，记档）。

### 口径抉择（记档，供下一片时对勘）

- **li 存相对值**（后缀自身的入射辐亮度估计），不存吞吐加权的绝对值——否则合并时我的前缀 T 会被乘到邻居候选上两次。
- **targetSum 存绝对尾迹亮度**：`T_pre·w·li_rel ≡ suffix_abs`（代数上就是循环里实际发生的事），省一次额外 BSDF 求值。
- 时域合并的雅可比（下一片）：静态场景下 `cos_n == cos_y` → J 恒 1，固定机位收敛对照（设计风险 ③ 的验收工具）正好检验这一点；运动相机的修正量 = `|dot(wi, n_stored)| / |dot(wi, n_current)|`，邻居侧材质按 M24 先例近似为本顶点材质（retargetBsdfContext 同款近似，显式记档）。
- 测试：RtSkyMediumLayoutTest 钉 1296/1280；重放契约测试新增「种子基快照 + 地址门控 + pack 调用」三条源级钉。

### 验证状态

- slangc + spirv-val 全绿（含 EXT SER 变体）；`gradlew build` 全绿；layout/契约测试全绿。
- **游戏内复验待做**：开档应与关档画面无可辨差异（尚未有消费者）、~199 MiB 显存日志出现、`pathReservoir` CSV 列随开关翻转。性能基线（R6 寄存器压力台阶）等时域合并落地后一并测——本片的开档成本只有一次 48 B 写/像素/帧。

## D217：S2 pass B 第二片 —— 时域合并落地（2026-09-09）

初始候选有了消费者：主命中重连顶点处读上帧记录、校验、重加权、合并进像素，并把幸存者写回。**估计量(GRIS 退化形,共享目标近似)**:

- **像素值**:`后缀 = (本帧自身后缀 + 时域候选) × 0.5`。两项都是同一积分的无偏估计——自身是本帧路径,时域是 `W_n · T_pre · f(wi_n)·cos/pdf(wi_n) · J · li_n`(存的方向按本顶点的延续混合体重加权)。静态场景方差约减半;更深的历史压缩走 S3 的形状项。
- **写回 = 幸存者而非和**:路径后缀不可平均(两个方向不是一方向),reservoir 按 `P ∝ m_i·t̄_i` 目标比例抽一个代表,记 `m' = 1 + min(m_n,16)`、`targetSum' = t_c + m_n·t̄_n`、**`W' = targetSum'/((1+m_n)·t_chosen)`**——这个 W 正是「W·t(y) 在任何通过校验的顶点重评都无偏」这一不变式的维持者(标准 RIS 恒等式),也是 m 可以增长而 W 保持 O(1) 的原因。`spare` lane 改名 W 并更新 banner;旧格式记录 W=0 会被校验拒绝,会话内热更安全。
- **f(wi) 的口径**:延续混合体拼写(diffuse+各向异性 GGX,×rainBaseTransmission+rainFilmBrdf(false),÷bsdfContinuationPdf)——**刻意不复用 evalSampleContrib**:那是面光源目标函数(emitterTint 在内、SSS 背lobes分支、film floor 与 emitter MIS 绑定),没有一个描述后缀样本;内联块在 recordPath 门内,关档死码。
- **雅可比 v1 = cos_here/cos_stored**(D216 记档口径的最终形:材质按本顶点近似后,比值只剩余弦);**静态场景 cos 相等 → J≡1**,固定机位收敛对照因此直接检验无偏性。
- **重投影 = M24 d0 原样**:reconDepth 0(重连=主命中,多数像素)走 restirPreviousPixel;更深的重连顶点不在相机射线上,读自己像素(M24 弹射顶点的既有安排)。
- **校验即 v1 移位(设计排序决策,向用户报备)**:钉死设计里的随机重放子循环在「后缀级时域合并」中**没有估计量角色**——存的后缀原地复用,从不需要重追;它的真实消费者是 S3 空间复用(邻居前缀真的要在本几何上重推导)。v1 以对应性校验代之:同重放深度 + M24 同款漂移(0.05)/法线(0.9)界 + W/target 有限。**重放子循环随 S3 落地**,若用户要它提前,单独成片。
- NaN 守卫:存储方向与视线近反向(量化可达)时半矢量退化,拒绝该候选。

### 验证状态

- slangc/spirv-val 全绿(含 SER);`gradlew build` 全绿;重放契约测试新增 d0 重投影/合并/W-lane/m-cap 四钉。
- **游戏内验收待做(铁律 7,固定机位收敛对照 = 设计风险③的验收工具)**:
  1. **静态场景噪声减半、无亮度偏移**:站定看一处间接光可见区域(墙角/屋檐下),开关 `composite.path-reservoir` A/B——开档噪声应变细,亮度不应整体偏移。**整体偏亮/偏暗 = 雅可比错**(设计已知风险③的预定表现);噪声不减 = 合并未生效(查 `pathReservoir` CSV 列与显存日志)。
  2. **移动不复用但不出鬼影**:走动时开档 ≈ 关档(重投影/漂移界拒绝历史),不应有亮度拖尾。
  3. **性能归档**:frame.csv `gpu.*` 同会话 A/B(A→B→A),本片新增成本 = 每像素一次 48B 读 + 一次 BSDF 重评 + 写;寄存器压力(R6 台阶)顺带观察 frame.csv 的 traceIndirect 底值变化。

### D217 补充:验收归因仪表(同日)

镜像 `RtRestirStats` 新增 `RtPathReservoirStats`(同一 `diagnostics.restir-stats` 开关下,一个 ReSTIR 统计开关管两套 store):三 lane——**read**(找到并读了历史)/ **usable**(通过校验)/ **applied**(合并值到达像素),1/16 像素采样、环形回读、每秒限频日志。shader 侧在时域块内直接三次 `InterlockedAdd`(每像素每帧至多一次尝试,采样后成本与 DI 统计同量级)。诊断读法:**read 高 usable 低** = 漂移/深度校验在拒(移动场景正常,静止场景偏高 = 校验过紧);**usable 高 applied 低** = pdf 门吞掉(方向落在混合体支集外,罕见);**read 本身 ≈ 0** = 重投影失效或开关/缓冲没接上。WorldPush 尾部追加 `pathReservoirStatsAddr`(偏移 1288)——恰好落在第一地址的 padding 里,BYTE_SIZE 仍是 1296,第二个地址零上传成本。

## D218：S2 开关首拨即崩 —— 未注册的 CSV 列名把 composite 打回原版（2026-09-13）

### 经过与根因

用户首次拨 `path-reservoir` 开关，当帧 `recordFrame → FRAME.count("pathReservoir", …)` 抛 `Unknown RtFrameStats name`，composite 失败闩置位，渲染回退原版。日志三行钉死因果：14 MiB path reservoir 分配成功（分配本身无错）→ 下一帧 count() 抛 → 「RT composite failed; reverting to vanilla path」。

`RtFrameStats` 的 stage/counter 名字必须先登记进各自数组，未登记的调用点会在**运行时**（统计开着的第一帧）抛——D208 时代的 gpu.* 注释和类 banner 都记档过这个陷阱，D217 的我仍然踩了：只加了 `count()` 调用没登记列名。它逃过所有构建期检查的原因正是 banner 写的：**只在统计开关开启时抛**，而自动化测试从不开启统计。

### 修复与回归缝

- `pathReservoir` 登记进 `FRAME_COUNTER_NAMES`（D211 的 CSV 归因规则不变）。
- 两个名字数组提为公开常量 `FRAME_STAGE_NAMES`/`FRAME_COUNTER_NAMES`，新增 `RtFrameStatsNameRegistryTest` 源级扫描：main 树下所有 `FRAME.count("` / `FRAME.stage("` / `"gpu.*"` 字面量必须已在注册表——失配在 `gradlew test` 红而不是用户游戏里炸。测试先红后绿验证（红在 pathReservoir，stage/gpu-zone 两网无其他缺口）。
- 教训归类：**「名字注册表 + 惰性抛错」必须配源级注册表测试**——抛点在运行时冷路径上，任何不扫调用点的测试都看不见它。这与 D215 的「视图注册表只允许一份」同族：注册表和调用点的任何第二份清单都会腐烂。

## D219：S2 首次游戏内验收 —— 合并在大量生效，但噪点反而暴增（2026-09-13）

### 现象与取证

用户开档后「噪点非常多」，问是不是合并结果没进 DLSS-RR。取证（12:23 会话日志 + stats）：

- **RR 在链路里且正常**：`DLSS-RR feature created` 照常、全程无 composite 失败——「绕过降噪」的假设否定；
- **合并大规模生效**：stats 显示 usable/applied 每秒 ~5–12k（1/16 采样），applied==usable（pdf 门零吞），约 1/3–2/3 的采样像素每帧被合并；
- 结论：噪点 = 合并估计量自身的输出。

### 机制（重新推导后代入）

估计量代数无偏（J 在期望内自洽），但**方差结构错了**：m 逐帧累积封顶 17 后，幸存者抽签里历史以 ~17/18 连赢，同一历史样本粘滞 ~18 帧；期间每帧像素都含同一份单样本偏差 (h−I)/2 ——**时间上准静态的逐像素随机图案**。DLSS-RR 的积累窗恰好也是 ~10+ 帧量级：它把独立逐帧噪声平均掉，却平均不掉这个准静态图案，净观感比关档更脏。次要放大器：J = cos_here/cos_stored 在存储方向贴近切向时无界（原 1e-4 守卫允许数千倍比值）， grazing 面上亮斑火fly。

### 修复（两件套）

1. **写回永远为新鲜单样本记录（m=1, W=1）**——历史最多活两帧。方差仍约减半（两个独立估计的平均），但没有任何静态图案能活过 RR 的积累窗。m/W 机制保留给 S3：空间域贡献的是许多**独立**候选，积累在那里才有估计量角色（同一根样品的复读不是积累）。
2. **J 钳制 [1/3, 3] + 双侧 cos ≥ 0.1 门**——法线本已 26° 内一致，比值出带即方向切向， damping 权重好过火fly。

### 验收状态

- slangc/test 全绿。**待用户复验**：静止 A/B 噪声应明确减半且无亮度偏移；若残余噪点集中在掠射面（水边、墙沿），报给我——那是 J/重连判据的 S3 份额。

## D220：路径 reservoir 步长 64≠48 —— 全分辨率会话 GPU fault（2026-09-13）

### 现象与取证

用户全分辨率（1600x900，RR 关）会话 2 分 17 秒后 `VK semaphore 5s timeout` 崩溃。Fluorite 的 device-fault 诊断两行钉死：`READ_INVALID` 地址落在 **`ReSTIR path reservoirs 1600x900x2`** 缓冲末尾之外 ~1 MB，随后 `IP_FAULT`（GPU 执行流失效，邻居是 TLAS instance buffer——越界写踩烂下游数据）。追诉：12:23 的 533x300 噪点会话同样在越界（319,800 槽 × 64B = 20.4 MB 跨度 vs 14 MB 分配），只是邻居恰好可读没炸——**D219 记录的「噪点暴增」可能部分是内存踩踏，不止估计量方差**，修复后需重新评估 D219 的两项修复效果。

### 根因：std430 的 float3 对齐

`PackedPathReservoir` 初版把 `pathSeed`（uint）声明在 `reconPos`（float3）之前。std430 给 float3 **16 字节对齐**：4 字节垫片把真实步长推到 **64**，而 RtComposite 按 48 B/槽分配——shader 以 64B 步长索引 48B 槽的缓冲，每个 slot 访问都错位、最后一个 slot 落在缓冲外 ~46 MB。PackedPathSegment 能做到 48 恰因 float3（ro）排第一位。 scaffolding 时只按字段字节数心算 48，且**没给这个结构体接 codegen 探针**——MaterialExtensionData 注释里「a hand-copied 48 that nothing checked against the struct it describes」正是防这个，防住了上一个，没防住这一个。

### 修复与回归缝

1. **字段重排**：`reconPos` 领头（16 + 8×4 = 48，与 PackedPathSegment 同构），记录迁入 world_common（probe 可达；restir_pt 的 import 链撞 probe 绑定，与 PackedPathSegment 同款先例）——banner 里写明「字段序就是分配」。
2. **codegen 接上**：probe + `PackedPathReservoirData` 生成，反射步长 48 已验证（修正前生成 64，诊断坐实）。
3. **RtComposite 尺寸改用生成值**：`PATH_RESERVOIR_BYTES = PackedPathReservoirData.BYTE_SIZE`，手抄数字退场——漂移在构造上不可能。
4. **钉子**：`RtPathReservoirLayoutTest` 钉 48；重放契约测试钉「float3 必须在 pathSeed 之前」。
5. 教训（与 D211/D215 同族第三课）：**跨语言 ABI 的每一份手抄都是定时炸弹；反射生成 + 注册表测试是唯一的防**。位（D211）、名字（D218）、步长（D220）——三次同一个根：两端各说各话，中间没有机器检查。

## D221：S2 验收裁决数据齐了 —— 时域合并在 1spp+RR 下结构性无赢面，应用门控在多样本历史上（2026-09-13）

### 用户的决定性观察

「开机就是开档没有噪点；进游戏后开关切换再打开有噪点。」stats 时间线还原真相：usable≡0% 持续六分钟（用户在飞行为测试地图跑图），最后一次开关后 5 秒跳到 30–37% ——那正是用户**站定**做 A/B 的时刻。合并的激活条件和 M24 完全同构：**移动即失效（d0 重投影/漂移界拒绝，M24 实测移动接受率 0–30%），站定即激活**。「开机干净」不是合并好，是没站定过；「切换后噪点」不是切换坏，是站定了。

### 净效果数学（D219 的推断错在哪）

站定时开档像素 = (新样本 + 陈旧样本)/2。D219 说「方差减半」只算了**原始帧**：单看 raw 的确减半。但下游 DLSS-RR 在 ~16 帧窗上平均**独立**噪声（关档去噪后方差 ≈ Var/16），而陈旧分量是 2 帧寿命的**时间相关**结构，RR 保留它不去平均——开档去噪后方差 ≈ 0.25×(½+1/16) ≈ 0.14Var，**比关档脏 ~2.3 倍**。结论：m=1 的时域合并（D219 反静态化之后）在强时域降噪器前没有赢面区间——移动时与 RR 同步失效，静止时输给 RR。S2 验收的 GO/NO-GO 对「时域合并作为视觉特性」给出的是 **NO-GO**，这是决策⑤节奏的正常产出，不是缺陷。

（另记档：stats 绝对计数存在疑似环形槽双计（读计数超采样像素数 1.2–1.8×），比率不受影响，不阻塞。）

### 处置（向用户报备的 re-scope）

- **应用门控**：`PATH_RESERVOIR_APPLY_MIN_M = 2` ——像素只接受携带 ≥2 个**独立**候选的历史。今天所有记录都是单样本（D219），故开档=关档画面 + 每像素一次写入；S3 空间域做出真正的多样本历史时，应用自然激活。机制（记录/校验/d0/统计）全部保留并持续可测。
- UI 提示词如实改为「S2/S3 基础设施，画面暂不变」。
- S2 的验收结论改写为：机制 GO（记录/校验/统计按设计运转），视觉 GO/NO-GO 移交 S3。**若用户裁决不同（例如想保留单样本合并在「关 RR 的 raw 参考视图」下启用），可再调。**

## D222：S3a 空间复用落地 —— 独立候选把 D221 的应用门顶开（2026-09-13）

用户批准 re-scope（S2=机制里程碑，视觉收益在 S3）后第一片。

### 内容

- **统一候选评估**：时域/空间两移位的校验+求值合并为 restir_pt 的 `evalPathSuffixCandidate`（一处拼写，仅表面判据不同：时域=绝对漂移，空间=平面偏移 0.05 + 横向 24 像素足迹界——M24 空间判据的同款 twin）。D218 时代的内联副本随之消除。
- **rgen 空间循环**：以重投影像素为中心、共享 `composite.restir-spatial-neighbours` 旋钮（M24 未用时其复用先查自己 store 地址返回，发布互不干扰），每个邻居候选参与流式幸存者抽签；m 封顶 16。
- **应用门打开**：`extraCount = 时域 + 空间 ≥ 2` 时像素取 `(own + Σ m_i·value_i)/(1+Σ m_i)`；幸存者三向抽签（own/时域代表/空间代表），写回带广义 m/targetSum/W（`W = totalTarget/(m·t_chosen)` 恒等式回归）。**惰性区间仍守 D219 规则**（写新鲎单样本），陈旧单样本永不通过时域链复读。
- **stats 3→5 lanes**：t-read/t-usable/s-attempt/s-usable/applied，日志双率分开报告（两移位答的是两个问题）；applied 即 D221 门的计数。
- 契约测试钉 evalPathSuffixCandidate/temporalWins/spatialWins；提示词更新（空间邻居旋钮驱动生效）。

### 预期与验收（交用户）

- **静止 + 空间邻居 ≥2**：合并首次真正进入画面。观察点：间接光区（墙角/屋檐）A/B 噪声应可见变细；**整体亮度不偏移**（偏移=雅可比/求值错，回报即修）。
- **移动**：与关档无差异（两移位都失效，预期行为）。
- **性能**：每个被接受的邻居 ≈ 一次 48B 读 + 一次混合体 BSDF 重评；frame.csv 同会话 A/B。
- **已知风险**（记档）：空间候选与 RR 的交互仍按 D221 的框架看待——候选彼此独立（同帧不同像素），理论上方差随接受数下降；GO/NO-GO 由本次验收实测裁决。

## D223：S3a 验收复盘 + S3b —— 「看不出效果」的两个原因与修复（2026-09-13）

### 验收数据还原

用户静止+邻居≥2 验收「看不出明显视觉效果」。stats:`applied` 恒 0,但 t-usable 63–74%、s-attempt 21–27k、**s-usable 仅 0.8–8%**。两个原因:

1. **仪表缺失(我的)**:S3a 重写后循环时把 applied 计数器弄丢了——恒 0 是假的,合并可能已在少数像素上生效而日志看不见。修复:applied 移到 post-loop 门控处自增(它数的本来就该是门,不是候选)。
2. **门被空间接受率卡死(真问题)**:applied 门需要 ≥2 独立候选;时域 74% 给了 1 个,空间只补上 0.8–8% → 全画面只有 ~1–8% 像素过门 → 视觉上不可见是**真实结果**。空间判据过严的根源:0.05 格的世界距离阈值在数米外就小于一个像素的足迹,DLSS-RR 降采样下更是亚像素——**距离越远越严,方向反了**。

### S3b 修复(决策 6 前两项的落点)

- **阈值足迹化**:漂移 ≤3 footprints、平面 ≤2 footprints、横向保持 24 footprints——footprint=该顶点处一个像素覆盖的表面尺寸,阈值随距离/分辨率自动缩放(Enhanced 论文重连判据的核心论证)。世界块常数退役。
- **分级接受**:空间软门(平面/横向/法线)失败时不再一刀切,按兼容度评分 `max(cos,0)^8 × exp(-超出横向界的平方)` 随机通过——边界邻居按 Junkins 的连续评分偶尔放行,质量排序保留(评分→0 的邻居实质仍被拒)。候选瘦身对值无偏:每个被接受的候选仍带精确重连权重。
- 预期:s-usable 从 ~5% 显著抬升 → 过门像素比例上升 → applied > 0 → 静止验收应有可见降噪。

### 验收(交用户,同前次流程)

静止 + 邻居 ≥2:①stats 的 `s=(x%)` 应明显高于之前的 1–8%;②`applied` 应为正;③间接光区噪点可辨变细、亮度不偏移。若 s-usable 仍低,报数字——下一个杠杆是把邻居搜索半径/软门余量再放。

## D224（A 线）：A-②a 钉现状 + A-①a 重连粗糙度门（feat/m28-s3-finish）

三线并行启动，A 线从 `7a7d50f` 拉分支，commit 顺序照分派文档。

- **A-②a（d808df2）**：决策 6③「RGB 向量权重」的 shipped 形态钉死——applied 估计按 RGB 累积（`temporalCount * temporalValue + spatialValue` 进画面）、幸存者抽签标量驱动（`rndf(seed) * totalTarget`、候选权重 `candCount * candMeanTarget`）。Enhanced §6.3 的解耦两侧各一钉，A-②b 的域切换不得塌缩任何一侧。
- **A-①a**：Enhanced §4.2 单顶点粗糙度门落地。`PATH_RECONNECT_MIN_ALPHA = 0.04`（论文 ρ_min=0.2 的 Falcor 感知粗糙度 → 本仓库 GGX alpha 的一次性单位换算，铁律 2 口径，非对存储值平方）；`tracePath` 循环携带 `prevVertexRough`（初值 1.0 = 相机前缀视为粗糙，`reconIndex == 0` 无 x_{k-1} 表面，门只咬镜面链）；资格判定追加 `prevVertexRough >= PATH_RECONNECT_MIN_ALPHA`——门看的是**前一顶点**：粗糙的延续正是可重连的定义，镜面在重连**背后**才杀死后缀迁移。stats 扩到 6 lanes，lane 5 = `deep-recon`（被接受候选中 `reconIndex > 0` 的采样计数）——**这张占比底片决定 A-①b 是否有估计量角色**（分派文档的裁决点，数字随下次游戏会话产出）。

## D225（A 线）：A-②b 选择目标切到接收域（Enhanced §6.3 对齐）

幸存者代表性：候选选择权重从「home 域 target（`stored.targetSum/m`，候选在老家的亮度）」切到「**接收域**值（`luminance(candValue)`，本顶点重评后的贡献亮度）」——时域/空间一处拼写同改，`spatialWinTarget` 赋值点同域；own 侧 `ownTarget` 本就是接收域。**无偏性**：GRIS 的选择目标可为任意正函数，无偏性只要求 W 恒等式的 draw/sums/t_chosen 三者同域——三处一起切、契约钉逐处钉死（`candCount * luminance(candValue)` / `temporalSum = candCount * luminance(candValue)` / `spatialWinTarget = luminance(candValue)`）；applied 估计的 RGB 累积不受域切换影响（A-②a 的钉子把「标量选择 vs 向量着色」的不变式保住了）。记录里的 home 域 targetSum/m 下帧只作正数选择提示，域可不同。预期：幸存者更贴接收域，下帧时域候选更准（静止 A/B 不劣化）。

## D226（A 线）：A-①b 前置重构 —— `continuationReconnectWeight` 一拼写

`evalPathSuffixCandidate` 内的 f(wi)·cos/pdf 块抽为 restir_pt 文件域 helper，in-place 与重连两条求值路径共用（分派文档 A-①b 第 4 条：文件横幅警告「两种拼写 = 复用率失效」，这条重构就是防它）。表达式逐项保序，无行为变化；「刻意不用 evalSampleContrib」的理由注释随行。

## D227（A 线）：A-①b 重连移位落地 —— 范围经证据收敛到 reconIndex == 1（composite.path-replay，默认关）

### 范围裁决(实现前对论文与流结构的核对,记档供复核)

分派文档的 sketch 让 `replayPrefixToReconnect` 把存储种子重驱到深度 reconIndex−1。实现前对 `Papers/_txt/lin2026restirptenhanced.txt` 与本仓库 tracePath 的流结构逐项核对,发现**流保真在 reconIndex ≥ 2 时不可达**:tracePath 在每个不透明着色顶点消耗 M24 复用的抽取,而其消耗次数随接受结果变化——重放无法停在记录流的同一位置。Enhanced §6.2.3/§6.2.4 靠**改造 tracer**(把 NEE 光采样与 RR 从 replay 路径移除)绕开,我们的已记录流无法追溯适用。文档 sketch 的重放种子初始化 `pcg(replaySeed)` 亦与记录侧推导不符(记录值已是 pcg 后状态,再 pcg = 静默换流),一并修正记档。

**收敛后的 v1 范围**:reconIndex == 1 的前缀锚 y_{k-1} 恰是**调用者自己的主命中**——零种子消耗、无可重放项,移位退化为「从该锚到存储重连顶点的连接」,用锚点的**真实材料**(重放解码,非 retarget)求值。reconIndex ≥ 2 一律拒绝(带证据注释);deep-recon lane(A-①a)正是裁决这一范围够不够的底片。这也顺带消除了 sketch 里的雅可比问题:重连方向由几何给出(非重采样),连接几何项 `cos_y·cos_x/dist²` 显式入值(两锚点是世界中不同点,远距候选按物理衰减;in-place 路径无此项恰因两锚点重合)。

### 落地

- `restir_pt.slang`:`ReplayedPrefix` 结构 + `evalReconnectedSuffixCandidate`(连接几何项 + 双侧切向门 + 共享 `continuationReconnectWeight`);范围证据写在模块注释。
- `world.rgen`:空间循环路由——`reconIndex > 0 && path-replay 开`走重连分支(锚点状态门 `reconDepth == 0 && reconIndex == 1`),连通性经 `visibilityMasked(CULL_SHADOW_NO_SELF, offsetSurfaceOrigin(...))` 零透射即拒;`reconIndex == 0` 与关闭档走原 in-place 路径(retarget 近似保留 = 测量 A/B 隔离臂)。
- 开关 `composite.path-replay`(默认关,关档 = 本片前行为);WorldPush 尾部 `pathReplayEnabled`(§3.2 豁免,偏移 1296/1312,codegen + layout 钉同 commit);stats 6→8 lanes(replay attempted/valid,attempted 高 valid 低 = 移位在死:切向/遮挡/深度不合)。
- 契约钉:范围门、连通性检查、共享权重拼写、`reconIndex ≥ 2` 拒绝、开关默认关。
- devlog D227 即 A-①c 归档的占位:固定机位收敛对照与 `frame.csv` A→B→A(`composite.path-replay` 翻转)由下次游戏会话产出后补记。

## D228（A 线）：A 线收尾状态 + 协作事故记录

- **A 线五个 commit 全部落地**于 `feat/m28-s3-finish`：A-②a（d808df2）→ A-①a（ce35c94）→ A-②b（0e6e4cf）→ A-①b-prep（4de1270）→ A-①b（e1730e6，自 C 线分支 cherry-pick 回）。构建门在 `.claude/worktrees/line-a` 全绿（slangc + spirv-val 含 SER、全部测试；worktree 缺原生资产目录属环境问题，已拷贝主树产物验证）。
- **共享检出冲突**：A-①b 首次提交时工作树已被 C 线切至 `feat/m30-parallax-puddle-link`，提交落在对方分支；未动对方检出，以 worktree + cherry-pick 解决（e1730e6 与 86d16f7 同补丁）。已记入分派文档 §7,建议并行期强制独立 worktree。
- **A-①c（归档 commit）**：固定机位收敛对照 + `composite.path-replay` 同会话 A→B→A,等用户验收会话产出数据后补记。
- **A-③（duplication map）**：按分派文档铁律 1 前置要求,**未开工**,设计与「偏差换相关」取舍已呈请用户裁决（见交接）。

## D229（A 线）：2026-09-23 审计后的阻断修复

对照 `F:\MC\Shader\evidence\M28-ReSTIR-audit-2026-09-23.md`，D227 关于「绝对连接几何项解决雅可比」的结论**撤回**。Enhanced Eq. 2 要求新旧路径密度与几何项的比值；48 B 记录缺旧锚点及相应密度，原 `cos_y·cos_x/dist²` 无法满足同路径 `J=1`。原重连求值函数已移除；保留配置键与 WorldPush ABI，`composite.path-replay` 开档暂时拒绝 `reconIndex > 0` 的空间候选，关档仍是 D227 前的 in-place retarget 基线。开档现在是安全隔离状态，不代表随机重放已经实现。原分支的法线方向、可见性终点和半透明透射问题随无效估计量一起停用；恢复前必须一起解决并以 Eq. 2 验证。

同时修复空间邻域的行主序逆映射（`readPixel / renderSize.x`）、把历史候选求值限制在首次重连弹射点，并在水/玻璃前驱后关闭不适用的粗糙度资格。静态契约增加非方形分辨率索引往返与首次重连事件门检查；多弹射运行时复验仍待游戏会话。当前 S3 的位置足迹与候选软放行仍是启发式，不能称为 Enhanced Eq. 5 双足迹或 Junkins K→M WRS；跨像素估计的无偏性未验证。此前 S3 接受率、亮度和性能数据需作废并重测。

## D230（A 线接手）：2026-09-26 复审——S3 估计量判定重建，Phase 0 护栏

对照最初设定（调研八项决策、§8.13 切片、09-09 S2 裁决 B）与七篇论文原文复审 `b9a24df`，完整报告在 `F:\MC\Shader\evidence\M28-ReSTIR-audit-2026-09-26.md`，W 链数值模型在同目录 `M28-wchain-sim-2026-09-26.py`。

### 结论

D229 修掉的是索引与重复合并；**当前唯一在跑的估计量本身不成立**：

- **B01**：in-place 空间候选把邻居 x₁ 处的方向与入射辐亮度原样搬到本像素，没有可见性和连接几何，除时域静止的恒等移位外都有偏。根因是重连顶点差一位——论文的重连是 y_{k−1}→x_k、k≥2，实现却把方向被采样的 x₁ 当成了重连顶点。**撤回 D217、S3a 与分派文档中「reconIndex == 0 精确移位」的说法**；A-01a 的粗糙度门因此落在相机上。
- **B02**：循环条件 `temporalCount + spatialCount < 16` 让静止像素两三帧后一个空间邻居都不读。
- **B03**：幸存者 W 用含旧 W 的接收域亮度作 t_chosen，W′ = W_correct/W_old。静态合并链偏亮 +16%～+5600%；乘回旧 W 后无偏，标准差降约 5.7×。A-02b 之前的写法同样有偏。

另记：软门绕过平面判据（B04）；调研 §1.4 转述的 Junkins 公式有误（原文 h_p = exp(−‖Δ‖/s)、s = √(Ωd²/π)，用于 K→M 的 WRS 选择）；D224 的 α_min 由 0.2 换算成 0.04 缺出处（B08）；合并口径退回 M24 已废弃的常数 1/M（B09）；REPLAY CONTRACT 横幅与随机流实际消耗不符（B07）。

### 用户裁决（2026-09-26/27）

| 项 | 裁决 |
| --- | --- |
| S3 估计量 | 完整 hybrid shift + 随机重放（09-09 裁决 B 全貌），不做简化 |
| MIS | pairwise（复用 M24 公式）；「反向移位是否追可见性」做隔离开关，同会话 A→B→A 实测后再裁 |
| 样本定义 | 论文 path-tree 单事件：x_k 处事件带技术标签，WRS 选一，移位时按新入射方向重评 BSDF 与 MIS；未支持的事件留在本像素路径里，可分期落地 |
| 记录 | 80 B（论文 64 B 字段 + objectID + 主命中 UV），即解除分派文档 §3.2 对该结构的冻结 |
| 寄存器隔离 | 编译期变体 `-DFLUORITE_PATH_REUSE`，关档管线不含新代码 |
| 分支 | Phase 0 后 M28（开关全默认关）先合 main，M29/M30 rebase 其上；三条分支已推送 origin 备份 |

### Phase 0（本条对应提交）

- **B03**：时域/空间胜出分支的 W 乘回胜者记录的 `asfloat(prev.W)`；新增 `RtPathReservoirEstimatorTest`，用 Java 镜像公式跑固定种子的静态合并链，断言修正写法无偏、旧写法偏亮，并钉住 shader 写法。
- **B05**：本帧没有重连顶点的像素写 m=0 空记录（`emptyPathReservoir`）。pass B 对每个像素的 leaf 0 都调用 `tracePath`，所以全覆盖。
- 横幅与提示更正：记录横幅的 m/W 语义；「ReSTIR 空间邻居数」补路径 reservoir 用法；「路径 Reservoir（M28 S2）」注明当前估计量有偏、正在重建。
- **有意不修**：B02（单修会放大 B01，重建 R2 整体退役 in-place 估计量）。B04/B06/B07/B09 随重建处理。
- 关档管线不受影响：改动都在 `recordPath` 门内，铁律 8 成立。

### 下一步

M28 合 main（合并前请示）→ M29/M30 rebase（改写他线分支历史前请示）→ 重建 R0–R6（设计记录 D231）。M28 号段剩 D232–D233，之后需申请新号段。

## D231（重建设计）：S3 按论文完整 hybrid shift + 随机重放重建——估计量、记录、随机流、开关与切片

分支 `feat/m28-s3-rework`（从 `23d3015` 拉出）。用户裁决见 D230；本条是重建的设计基线，切片内细节以对应提交与后续 D 条为准。标为「请示项」的内容到该切片前按铁律 1 请示。

### 估计量

- **样本**：path tree 单事件，对应 Enhanced §2.3/§6.1。在源路径的重连点 x_k，每个事件都是一个带技术标签的候选：
  - **L**：x_k 自发光与环境底；
  - **S**：太阳 NEE；
  - **R**：发光体 RIS NEE；
  - **T**：薄壳 SSS；
  - **H**：x_{k+1} 处 MIS 加权的 BSDF 命中；
  - **D**：更深的其余部分。

  WRS 选一个存入记录，得到 W_tree。**R** 在 S4 统一 DI 之前是具名近似。暂不支持的事件不进复用，留在本像素自己的路径里——积分拆分无偏，所以技术可以分期落地：D、S 先，L/H/T 随后，R 最后。
- **重连顶点**：源路径上第一对满足判据的 (x_{k−1}, x_k)，k ≥ 2；接收端的 y₁ 是 pass B 的 hitDepth-0 顶点。判据有三条：Eq. 5 前向足迹、x_k 非漫反射时加逆向足迹、x_{k−1} 的 sampled-lobe 单顶点 α 门。**用户 2026-10-08 批准 R2 初始值 α_min=0.2（直接为本项目线性 GGX alpha，不平方）、c=0.02；R6 再实测校准**（D230 B08 的未证实 0.04 换算不恢复）。
- **移位**：k ≥ 3 时，从接收端 hitDepth-0 顶点用逐顶点随机流重放到 y_{k−1}，重放不做 RR。然后用**最近命中**连接射线连到 x_k：命中距离相同、同侧、法线一致才算通过，同时借 payload 取回 x_k 的材质（G3 请示项）。在 x_k 按新入射方向重评技术与 MIS。雅可比按 Eq. 2 计算，其中 G 的余弦取在 x_k（论文印刷的注记见 D230）；源路径的分母 D 存在记录里。
- **合并**：pairwise MIS 沿用 M24 形式；「反向移位是否追可见性」是隔离开关（Q2）。着色按 §6.3 做 RGB 累积；没有可用域时回退到路径追踪的完整和，所以开档移动时约等于关档。
- **置信度**：每个上帧候选各自取 min(Cap, c)；空间循环不受时域计数限制（修 B02）。Cap 取值是请示项 G6。

### 记录（原定80 B，R2b-6经用户批准为96 B；std430）

| 偏移 | 字段 |
| --- | --- |
| 0 | `float3 reconPos`（x_k）、`uint pathSeed` |
| 16 | `float3 primaryPos`（记录自身像素的 y₁）、`uint primaryNrm`（oct16×2） |
| 32 | `uint reconNrm`、`uint dirK`（R2b-6起为方向X的float32位模式）、`uint radiance`（float32入射R位模式，R2b-5起）、`float W` |
| 48 | `float jacDenom`、`float targetOwn`、`uint confPdf`（低半精度置信度，高半字保留零）、`uint bits`（k/技术/lobe/介质/主命中类型/valid；R2b-17增加前驱瓣与事件子类） |
| 64 | `uint objectId`、`uint primaryUv`（决策7的LoD钩子）；`reserved0/1`在R2b-5起存float32入射G/B位模式，不再空闲 |
| 80 | `uint dirY/dirZ`（方向Y/Z float32位模式）；88/92为`float neePdf/sourcePdf`，通常存竞争NEE PDF与源当前事件PDF；R2b-19的H kind=2分别存NEE面积密度与固定出射端点G，以保留原面积MIS数值保护项，源采样密度仍在jacDenom及独立诊断参考中；stride仍96 B |

1080p渲染分辨率下双份398,131,200 B（379.6875 MiB）；DLSS内部分辨率更小。原80 B的331,776,000 B预算已由R2b-6的用户裁决更新。结构、`PATH_RESERVOIR_BYTES`、生成记录与 `RtPathReservoirLayoutTest` 同步维护。

入射编码原定RGB9E5，2026-10-30经R2b-4 GPU隔离发现大量弱通道/低量级损失，R2b-5在80 B内改为float32 RGB；上表保留实际字段名与偏移，变更依据见对应小节。

### 随机流（R1）

- 新增 `vertexStream(pathSeed, hitDepth)`，只接管**决定顶点**的抽样：电介质的 VNDF 与 Fresnel 选择，不透明面的 lobeChoice、水膜/高光 VNDF、SSS 游走选择与内部、漫反射余弦。
- 留在原 `seed` 的：`sampleSquare`、`risInitial`、`restirReuse`、`volumeNee`、粒子余弦、两处 RR。
- `traceClouds` 改用逐顶点流派生的种子，使连接边上的云透射是路径的确定函数。
- 复用逻辑自己的抽样（邻居候选、A-ES、幸存者）改用独立的 `reuseSeed`，改复用逻辑不会改变追踪出的路径。
- 这些改动只存在于复用变体管线中（G15），基础管线仍是已发布的 tracer。

### 开关（G14，用户裁决）

- **主开关**「路径 Reservoir（M28 S2）」（`composite.path-reservoir`）：打开时分配缓冲，并让 pass B 切到复用变体管线。
- **WorldPush 1296 处的字改为位字段**。Slang 字段名 `pathReplayEnabled` 暂不改，因为 M30 的布局钉引用它（`RtSkyMediumLayoutTest` 1296/1300）；语义以横幅为准。
  - bits 8–11：路径复用空间邻居数。
  - 时域开关（R3）、反向可见性（R3）、允许 k≥3（R5）、恒等自检（R1）各占一位，**在消费它的切片才加 UI 与位**，不出现拨了没反应的开关（D211 的教训）。
- **新旋钮**「路径复用空间邻居数」（`composite.path-reuse-spatial-neighbours`，路径追踪分区，默认 0，范围 0–8）。M24 的「ReSTIR 空间邻居数」从此只管光源 reservoir。
- **`composite.path-replay` 退役**：普通 `fluorite.toml` 里残留的键会被忽略；严格导入的预设若含该键会被拒绝——这个键在 main 上只存在了数小时。
- **frame.csv 新列** `pathReuseSwitches`：记录打包后的字，与 `pathReservoir` 同源（D211）。

### 编译期变体（G15，用户裁决）

- `build.gradle` 对 `world.rgen` 再编两份带 `-DFLUORITE_PATH_REUSE` 的：`world_reuse.rgen.spv`、`world_ser_reuse.rgen.spv`。
- `RtPipeline` 增加**变体管线**：共享管线布局与描述符集。R0时只换raygen、共用miss/hit；R2b-21起显式传入完整阶段清单，辐射阶段使用联合命中接口、阴影阶段保留原接口，各自SBT尺寸跟随变体。它必须是另一条 `VkPipeline`，不能做成同一管线里的第三个 raygen 组，因为光追管线的寄存器分配按整条管线计，放在一起会把关档一起拖慢。
- 主开关第一次打开时懒创建变体，此后常驻；pass B 只在路径 store 存在时绑定变体。
- 基础管线里 `recordPath` 是编译期常量 `false`，路径复用代码整体被消除。

### 验证基建

- **Java 数值参考**：`RtPathReuseMathTest`（R0），覆盖：
  - pairwise 在目标与雅可比都不相等时仍单位分解；
  - 反向可见性关掉时的偏暗量；
  - flatland GRIS 链无偏，含 Cap、§6.3 与 B03 回归；
  - J(identity) = 1、J·J⁻¹ = 1、D′ = 分子；
  - 置信度有界；
  - path-tree RIS 边际化与 RR 外置。

  之后各片的 shader 公式都要钉到对应的 Java 镜像上。
- **GPU 统计 lane**：由用到它的切片自己加，不预留空 lane；原计划 R0 扩到 16 lanes 的做法改为此。
- **恒等 lane**：R1 做前缀恒等，R2 做完整恒等（按 k=2 / k≥3 分档）。

### 切片

| 切片 | 内容 | 关键验证 |
| --- | --- | --- |
| R0 | 本条；G14 开关与 frame.csv 列；G15 变体管线；`RtPathReuseMathTest` | 构建与测试全绿；关档管线不含复用代码 |
| R1 | 逐顶点随机流、顶点函数抽取、REPLAY CONTRACT 重写、前缀恒等 lane | 恒等 lane ≥ 99.9% |
| R2 | 96 B记录（R2b-6用户已批扩容）、记录端判据、path-tree单事件、退役in-place估计量 | 完整恒等lane≈100%；约398 MB日志；仅记录的A→B→A |
| R3 | 时域 hybrid shift（k=2） | 关「DLSS 光线重建」固定机位 N 帧平均开≈关（2σ），且方差更低 |
| R4 | 空间复用：Junkins Alg. 1、逐候选 Cap | RR 开下的静止视觉 GO/NO-GO；按邻居数的 A→B→A |
| R5 | k ≥ 3 深链重放 | k≥3 恒等 lane；光泽地面与水边收敛 |
| R6 | 实测后裁决反向可见性、α_min、c、Cap、半径、duplication map | — |

**切片级请示项**：G3 x_k 材质、G4 空间域反向 y₁ 材质、G6 Cap、G10 连接边介质、G11 半透明连接、G12 发光体 RIS 技术、G16 特殊主命中、G18 SSS 游走前缀、G22 x₁ 天空 DI。G16/G18/G22 已裁决，见文末「请示项裁决」。

**风险**：
- 每像素约 +4–10 条射线（M=2 时的估计，未实测）。
- 单个 raygen 做不了 stream compaction。
- DLSS-RR 前的相关性问题（D219/D221）。
- 80 B 记录与 M24 并存时的 8 GB 峰值。
- Slang 的已知误编（R24）。

### 落地进度（2026-09-28，`feat/m28-s3-rework`）

每片一个提交，推送到 origin。G15 之后关档管线不含任何复用代码；R1a 起每片都把关档管线（pass A `world_primary.rgen`、pass B `world.rgen`/`world_ser.rgen`）的 SPIR-V 做规整化比对（去调试信息与死常量、ID 重编号），与前一片等价。

- **R0**：`1d57f90` 开关字 + 独立邻居数 + UI + `pathReuseSwitches` 列，`composite.path-replay` 退役（G14）；`78ebba1` 变体管线（G15）；`f2b80ed` `RtPathReuseMathTest`。
- **R1a** `41024a0`：复用 tracer 挪进 `path_reuse_trace.slang`，只在变体里 include；`world.rgen` 的 `tracePath` 回到 S2 之前的发布版。
- **R1b-1** `15e88b4`：三个顶点决策函数（`reuseResolveDielectric`、`reuseOpaqueVertex`、`reuseSampleContinuation`）逐行抽出，契约测试逐行对照发布版循环。
- **R1b-2** `cd7fe7a`：被记录路径的顶点决策改走 `vertexStream(pathSeed, hitDepth)`，未记录路径把流交还给 `seed`、逐次抽样与发布版一致；复用自己的抽样走 `reuseStream`。
- **R1b-3**：前缀重放 `reuseReplayPrefix`（与循环同一条追踪调用、同一套顶点函数、逐顶点流、不做 RR，遇粒子即停）和恒等自检：诊断分区「路径复用恒等自检」（`diagnostics.path-reuse-identity-check`，开关字 bit 0），每 16 像素抽 1 个，把自己的路径从 pass B 第一个顶点重放 2–3 个顶点，落点与记录偏差在位置量级 1e-4 以内即通过；统计 lane 6/7（尝试/通过），追加在「ReSTIR 复用统计」日志行末尾 `identity N (比例)`。只含一个顶点（不涉及任何决策）的路径与经过粒子的路径不计入。
- **与设计的差异**：`traceClouds`、`integrateSegment` 仍按值取 `seed`（不推进流，不影响路径几何）。它们只在移位要重新求值连接边或重放段的透射时才需要确定性种子，所以挪到消费它的 R3 一起做，届时与 G10（连接边介质）同一请示。
- **待游戏内验证**（R2 开工前，用户自跑后交日志）：关档画面与帧时间不变；拨开主开关后日志出现 `RT path reuse pass B pipeline built`，frame.csv 的 `pathReuseSwitches` 列跟随开关字；恒等自检比例 ≥ 99.9%。

### 请示项裁决（2026-09-28，用户）

请示时按「论文原意 / 当初路线 / 现状」三方对照给出选项。

- **G16 特殊主命中 → 三类都写空记录。** pass B 第一个顶点 y₁ 是粒子、粗糙电介质（磨砂玻璃）或 pass A 在 delta 水面/玻璃处拆出的分叉叶时，R2 写空记录，这些像素用自己的路径追踪值，与关档画面一致，无偏、零成本。
  - 依据：论文（Lin 2026）把 delta 主命中留在路径里、靠 hybrid 重放穿过去；本项目由 pass A 做确定性双叶拆分，两者不对应。
  - 现状的问题：粒子续射走顺序流、无法重放；粗糙电介质没有 BSDF 求值函数；分叉叶只记录叶 0，而叶 0 是**反射叶**（法向入射约 2% 能量），时域重投影还会落到被反射物体所在的像素上。
  - 后续：水/玻璃的复用作为 R4 之后的独立扩展（按叶存记录，参考 Hong 2026 的多层 reservoir），届时另行请示。
  - 未选：改记折射叶（水面波浪使水下时域接受率预计很低）；按论文穿过主命中重放（要改 pass A 的拆分）。
- **G18 前缀含 SSS 随机游走 → 判失效，留给本像素。** 这种情况只出现在「次表面散射」=「随机游走」时。x₁…x_{k−1} 任一处走了游走分支，路径就不记录，移位也不穿过游走；游走分支的贡献由本像素自己的路径承担（积分拆分，无偏）。
  - 依据：hybrid shift 的雅可比需要每个重放顶点的 pdf，游走出射没有解析 pdf。七篇论文都未处理这种情况；专门方案是 Werner 2024（ReSTIR SSS），可作为以后的扩展。
  - 接收端：y₁ 的目标函数只含非游走瓣。
  - 未选：把游走近似成漫反射参与移位（有偏）。
- **G22 x₁ 直接光 → 留到 S4/S5。** x₁ 的发光体 DI 继续走 M24，太阳 NEE 仍每像素计算；路径 reservoir 的重连点 k ≥ 2，本就不含 x₁ 直接光。R2–R5 不为此增加任何内容。
  - 未选：现在把 x₁ 太阳 NEE 作为 k=1 事件加进 path tree（太阳 DI 方差本就小，收益有限）。


## D232（2026-10-02）：R1 实际冒烟，R2a 80 B ABI 与 in-place 退役

分支 `feat/m28-s3-rework`，从 `929be42` 接手。用户随后要求：**今后每次启动游戏测试前，先说明检查/实验内容并取得用户同意；未同意不启动。** CLI 构建、数值测试与 SPIR-V 比对照常推进。

### R1 冒烟证据

在独立工作树中复制基准存档为 `restir-r1-smoke`；原存档与主工作区配置未修改。RTX 2080 / NVIDIA 617.14，SER hint NONE，实际渲染尺寸533×300，DLSS-RR 开，空间邻居0。12:39:16 日志确认 `RT path reuse pass B pipeline built (world_reuse.rgen.spv)`；69批日志中的431,023次前缀重放尝试全部打印100.0%。旧格式只有一位小数，严格能证明的是每批通过率≥99.95%，满足R1 ≥99.9%的门槛，不能声称零失败。CSV的1274个开档帧均有 `pathReservoir=1`、`pathReuseSwitches=1`，旧 applied=0。客户端正常保存并退出。

归档：`F:\MC\Shader\evidence\restir-rework-2026-10-02\` 的 `r1-runtime.log`、`r1-frame.csv`、`r1-config.toml`、`r1-summary.json`。**这次只有前缀恒等冒烟**，没有同会话A→B→A，没有视觉/性能验收。启动参数1920×1080未成为实际渲染尺寸，不能写成1080p测量。

### R2a 落地

- `PackedPathReservoir`、`PATH_RESERVOIR_BYTES`与生成Java记录同步改为80 B。布局测试逐字段钉住D231表中的17个偏移，1080p双份331,776,000 B（316.41 MiB）。
- 删除旧时域/空间 in-place 候选、整段后缀归一化/幸存者写入、余弦比钳制、旧footprint启发式、未经证实的0.04粗糙度转换、Mcap与apply gate。
- **当前只写空记录并返回完整的自己的路径。** 这是重建中间态；path-tree事件、Eq.5记录判据与完整恒等检查尚未实现，R2未完成，R3/R4也未启动。保留的空间旋钮到R4才重新消费。
- LoD字段只保留布局；`objectId=0xffffffff`明确表示钩子不可用，不能把未取得的object/UV写成实现完成。
- GPU统计删去已无消费者的旧merge lane，仅保留prefix identity的尝试/通过2 lanes（shader与Java同步0/1）。输出原始通过/尝试数和3位小数，避免门槛判断被舍入遮蔽。
- `tools/normalize-spirv.py`在进入SPIRV-Tools前去除核心调试与 `NonSemantic.Shader.DebugInfo` 指令，再删除死常量、重编号ID并验证结果；Windows SPIRV-Tools2026.2直接strip-debug大型Slang raygen会访问异常，此流程已通过三份关档shader验证。
- 关档 `world.rgen`、`world_ser.rgen`、`world_primary.rgen`规整化后二进制与R1基线逐字节相同；测试261项通过，全部shader编译与spirv-val通过。

### 记录端的数据缺口（2026-10-05 已裁决）

现有 `world.rchit` 把 `evaluateMaterial` 的**着色法线**写入唯一normal lane；法线贴图会改变它，`reuseOpaqueVertex`又可叠加雨膜扰动。Enhanced Eq.2/Eq.5的几何项需要**几何法线**，不能把这个normal直接代入当作论文公式；InstanceID与UV也没有回传到raygen。R2b要在有效记录前补齐数据通路：

1. 独立ray query取实际三角形几何信息与object/UV，维持基础hit/payload不变；k=2成功记录通常额外2次查询，深链至少3次，若足迹判据连续失败查询数随深度增长。成本未经GPU测量。
2. 复用专用hit/SBT变体直接带回信息，不增加几何查询，但需扩展D231原定共享hit表的结构、冻结payload契约与构建管线；新增payload状态的寄存器成本也未测量。

用户已选择**独立 ray query**路线。该选择只授权实现取数通路，不是启动游戏的许可。G3的最近命中材质回取、G10/G11连接介质/半透明处理仍按D231在消费片请示；不以近似法线或不可用钩子绕过。

## D233（2026-10-05）：R2b-1 独立几何取数与 metadata 恒等诊断

落实用户选定的独立 ray query 路线。**本片是 R2 的数据通路前置片，R2 尚未完成，仍写空记录并返回本像素完整的路径追踪值。** path-tree 单事件、Eq.5 资格、完整 shift 恒等与记录阶段 A→B→A 均仍待后续消费片。

### 实现与契约

- 新增 `path_reuse_geometry.slang`，仅由 `FLUORITE_PATH_REUSE` 的 raygen 包含。radiance closest-hit、普通 tracer 与 payload ABI 不变。
- 以原追踪的 ro/rd、cull mask、hitT 查询同一个命中。`FORCE_NON_OPAQUE` 暴露候选；只考虑已知 hitT 数值容差内的候选，不 commit、不取第一个最近候选，保证不被 traversal 顺序决定。命中距离容差为 `8×2^-23×(1+max(abs(ro))+abs(hitT))`；这是数值容差，未经 GPU 数据调参。
- 从 position-fetch 的实际三角形顶点计算几何法线。顶点处于已应用 geometry transform、未应用 instance transform 的 object space；实例变换作用到两条边后再 cross，支持非均匀与镜像缩放；法线朝向入射射线。完整 3×4 变换用于命中位置，插值位置必须与 `ro+rd*hitT` 一致。
- terrain UV 走 `PrimitiveIndex + Section.triBase[GeometryIndex]` 的连续三角形角点；entity UV 走 `EntityGeom.triBase` 与 indexed vertex UV。两种索引与 `world.rchit` 对齐。粒子或超出几何桶范围的候选不访问这些表。
- 同一距离的不同 instance/geometry/primitive 判歧义，包含共享边和共面重叠；不猜 radiance shader 采用了哪个 primitive。重复报告同一个 key 可以接受。退化/非有限法线、位置或 UV 判 invalid。所有失败返回不可用 object sentinel。
- `objectId` 取得的是**当前 TLAS custom index**，尚不是跨帧稳定 ID；当前 UV 也未编码到 `primaryUv`。这两点不作为 LoD 重投影完成的证据。
- 当前消费者是现有恒等自检（bit 0）：每 16 像素抽 1 个，在原路径的前 3 个命中即时取数，先于其它 lighting trace；前缀重放仅对最后一个已成功取数的目标再查询，比较 position、geometric normal、object/geometry/primitive ID 与未打包 UV。取数不推进任何随机流、不改 L。关闭诊断时没有新增 query 调用。
- 统计为 9 lanes：0/1 前缀尝试/通过；2 几何取数尝试；3/4/5/6 成功/未找到/歧义/无效（完整分割尝试）；7/8 metadata 重放尝试/通过。Java readback 同步 36 B，日志输出原始分子/分母与三位小数；零分母标 `n/a`。三个语言的 tooltip 同步新成本：每个抽查像素最多 3 次重放追踪 + 4 次几何查询，实际 GPU 成本尚未测量。

API 依据：[Slang query position fetch](https://docs.shader-slang.org/en/latest/external/core-module-reference/types/rayquery-03/candidategetintersectiontrianglevertexpositions-09cow12.html)、[Khronos position-fetch 说明](https://www.khronos.org/blog/introducing-vulkan-ray-tracing-position-fetch-extension)。坐标空间以 Khronos 的 object-space 定义为准。设备原已启用 rayQuery/positionFetch，terrain/entity/refit 共用的 BLAS build flags 原已包含 `ALLOW_DATA_ACCESS`。

### 验证与待办

- 独立 API 探针已编译并通过 `spirv-val --target-env vulkan1.2`。
- `RtPathReuseGeometryTest` 用数值验证非均匀/镜像变换、朝向、平移只应用一次、terrain/entity 分桶 UV、cutout 非目标距离、候选顺序、重复候选/共享边/重叠拒绝、失败状态与容差；源码契约绑定实际 shader 公式、即时 capture 和各统计 lane。
- `generateShaderRecords :fabric:test` 全量构建成功；52 份 shader 编译与 `spirv-val` 通过；54 套/272 项测试全通过（0 failure/error/skipped，新增几何测试 11 项）。关档 `world.rgen`、`world_ser.rgen`、`world_primary.rgen` 规整化后二进制与 R2a `c240bb7` 逐字节一致。
- 归档：`F:\MC\Shader\evidence\restir-rework-2026-10-05\` 中的 `r2b1-build.log`、`r2b1-summary.json`、三个关档规整化模块及 geometry/replay/layout 测试 XML。摘要脚本检查测试总数、失败计数和二进制等价，记录新增 shader/Java 源文件哈希。
- **2026-10-05 提交时未启动游戏。** 当时等待逐次启动许可；2026-10-06 用户改为授权自主检查，游戏实验与发现的修复见下节。此冒烟不替代 R2 完整恒等、视觉或 GPU 性能验收。
- D233 是当前 M28 已授权号段的末号；新的 D 编号须另行申请。

### 2026-10-06：自主检查、运行错误修复与观感待验

用户授权：本任务可自主启动游戏执行检查，涉及观感的验收集中留给用户。取代此前每次启动确认要求；方向性实现决策仍按铁律 1。运行全部使用 `line-a/run/saves/restir-r1-smoke` 隔离存档，验证层开启；主工作区配置与原基准存档未修改。

**实际发现并修复的错误**：

1. `sky_transmittance.comp.spv` 声明 `StorageImageReadWithoutFormat`，设备创建未满足能力要求，触发 `VUID-VkShaderModuleCreateInfo-pCode-08740`，渲染器回退到 vanilla，根本没有执行几何自检。新增核心 read/write-without-format 功能到同一份查询/启用清单；设备不支持时照常报告 RT 不可用。对应关系见 [Vulkan SPIR-V 能力表](https://docs.vulkan.org/spec/latest/appendices/spirvenv.html)。实际编译的 sky module 的 capability 与启用清单契约测试先失败、修复后通过；无 shader 内容或 payload 改动。
2. `RtGpuTimers.resolve` 先于该 slot 的 `beginFrame` 执行；首次 read 触发 `VUID-vkGetQueryPoolResults-None-09401`。全池 reset 已记录也不代表另一未用 slot 的 reset 已在 GPU 执行。新增每 slot 的 recorded 标记：只有此 slot 记录过命令、调用者能等待其 graphics-use token 时才读回。两个测试直接调用真实 resolve 的 native-read 边界，覆盖首次读取和另一 slot 只排队 reset；先失败、修复后通过，真实重启后该 VUID 消失。
3. 正常退出报 `VUID-vkDestroyDevice-device-05137`，有 202 个活对象。关闭 DLSS-RR 仍报 202，RT 总开关关闭则没有该告警，范围缩到 RT 生命周期。`RtComposite` 创建了 `skyLuts` 却从未销毁整个资源图；`RtSky.destroy` 还漏了创建的 `waterDeformBake`。补齐两处释放及 sky 引用清空；保留 `FluoriteLifecycle` 最先等待 GPU idle、最后销毁 context 的顺序。所有权契约两项先失败后通过；原生 Vulkan 退出回归确认 202→0，最终会话正常保存/退出，三条 VUID 均 0。

**几何运行证据**（修复前两项后的完整会话，日志每秒抽取一个 frame snapshot，每 frame 抽 1/16 像素；不是未打印帧的总计）：

| 检查 | 原始计数 | 通过率 |
| --- | --- | --- |
| prefix | 4,318,538 / 4,318,553 | 99.99965% |
| geometry | 11,572,856 / 11,578,986 | 99.94706% |
| metadata replay | 4,311,444 / 4,312,689 | 99.97113% |

共 627 个日志快照；geometry 失败全为 6,130 次 ambiguity，missing/invalid 均 0。prefix 有 15 次失败，metadata 有 1,245 次失败，不能写成零失败。metadata 失败尚未细分类，可能是再次查询歧义或字段比较失败；不得全部归因到共享边。CSV 的 10,169 个开档帧都记录开关字 1；实际 RT 533×300、输出 1600×900、DLSS-RR 开，80 B 双份实际 25,584,000 B（24.40 MiB）。显卡 RTX 2080、驱动 617.14、SER hint NONE，只验证普通 TraceRay 变体的运行。未做 GPU 时间戳同会话 A→B→A，不作性能结论。

归档目录：`F:\MC\Shader\evidence\restir-rework-2026-10-06\`。包含初始失败与修复后日志/CSV、三类 red/green 测试证据、关闭 DLSS/关闭 RT 的资源对照、配置快照和 `r2b1-runtime-summary.json`。资源清理最终回归为 `sky-ownership-fixed.log` / `sky-ownership-fixed-console.log`。全部 57 套/277 项测试通过，0 failure/error/skipped；shader 源文件和生成的二进制未改，52 份已校验产物沿用。临时对照配置已恢复至检查前快照；全部客户端已退出。

**观感集中待验清单**：

- 当前 R2b-1 开档仍只返回自己的路径、写空记录；path-tree/Eq.5/完整 shift 恒等尚待后续，不能评价 temporal/spatial 复用效果。
- 已保存 `visual-reference-record-only.png`（DLSS-RR，533×300→1600×900）与 `visual-reference-no-dlss.png`（原生 1600×900）。这是当前阶段参考帧；两者输入分辨率不同且不是 N 帧平均，不能用于证明能量守恒或性能差距。
- 用户后续统一检查：记录阶段开/关的材质与高光观感；R3/R4 完成后再检查静止噪声、运动拖影、光泽地面/水边和特殊主命中的回退。当前没有视觉 GO/NO-GO 结论。

## R2b-2（2026-10-08，D 编号待分配）：sampled-lobe 参数与 Eq.5 资格前置片

用户批准 R2 初始值 α_min=0.2、c=0.02，直接使用本项目线性 GGX alpha，不平方；R6再校准。后续D号段问题仍待回复，本条只使用已存在的R2b-2切片名，未自行分配新D编号。

### 已完成的前置数据与判据

- `ReuseContinuationSample` 保存实际选中的 diffuse/base GGX/film/delta/walk 标签、该瓣的 scalar alpha、`bsdfPdf`（包含离散选择概率的 selected-lobe density）。不拿太阳 NEE 的全 mixture pdf 替换它。diffuse alpha=1，base为原线性rough参数、film为filmAlpha；delta/walk无解析密度，判失效。共享 sampler 的原方向、权重、pdf 和随机抽样逐行保留；只追加标签输出，契约测试钉住发布版逻辑。
- `reuseFootprintEligible` 实现参数化Eq.5，使用实际采样 ray 的方向与hitT（包含原tracer的surface offset约定）。前向G的法线位于x_k，逆向G的法线位于x_{k−1}；都为cosine/distance²。阈值为 `(c/100)*4π*|camera-primary|²/cosPrimary`。以 `density*threshold <= 1` 等价检验面积足迹，包含等号，无世界距离启发式、无energy/pdf钳制。
- current diffuse按论文脚注6跳过逆向项；current glossy/film检查逆向项，不再加x_k roughness门。source denominator为 `p_prev * G_forward * p_k`，非有限/非正值返回失败，不生成可用分母。
- 当前消费者只验证**D延续分支的资格**：恒等自检抽样的前3个顶点中，寻找第一对k≥2的合格点。要求未拆分的opaque主命中、有效primary/前后几何、前后解析延续、前缀无SSSwalk；电介质延续不暴露解析pdf，不能直接作为此处前驱。delta、particle、pass A split primary不进入探针的可用域。
- 在原ray命中后、其它lighting trace前捕获edge direction/T；在当前延续成功后、RR前检查。**因此这不是D事件已经存在的证明**：随后RR杀死或终端截断可能不给出任何D贡献。更深的event producer需要处理这一点。
- 统计扩为13 lanes/52 B：9尝试边、10首对通过、11首对k2、12首对k3，10=11+12；没有有效记录写出，时域/空间仍未消费。

### 验证

- 58套/287项测试全通过，52份shader编译/spirv-val通过。新增10项数值/契约测试覆盖：目标余弦与反距离平方、逆向拒绝与diffuse例外、primary投影和/100、边界等号、场景单位缩放、线性sampled alpha、退化/非有限密度、联合源分母和实际调用/统计接线。
- 关档 `world.rgen`、`world_ser.rgen`、`world_primary.rgen` 规整化后与R2a基线逐字节相同。
- RTX2080/617.14，SER NONE，隔离存档 `restir-r1-smoke`，实际533×300→1600×900、DLSS-RR开、空间0、identity bit0，验证层开。147个日志frame snapshots：prefix 1,005,466/1,005,471（99.99950%）；geometry 2,695,788/2,697,191（99.94798%，missing/invalid=0、ambiguity=1,403）；metadata replay 1,003,835/1,004,134（99.97022%）。
- D资格边456,712/1,293,979（35.29516%），首对k2=307,204、k3=149,508。分母是**尝试边**，一条路径可尝试两对，不能写成35.3%的像素产出事件。k3也只是资格，不是R5深链移位验收。
- CSV开档2,357帧开关字均1。启动/退出VUID=0、正常保存退出、配置快照哈希不变。没有同会话A→B→A，不作GPU成本结论；观感仍在待用户清单。
- 证据：`F:\MC\Shader\evidence\restir-rework-2026-10-08\` 中 `r2b2-build.log`、`r2b2-build-summary.json`、`r2b2-runtime.log`、`r2b2-runtime-summary.json`、CSV/配置/测试XML及三个关档规整化模块。公式原文核对Enhanced PDF第7–8页及第4页的sampled-lobe定义。

**下一片**：path-tree 单事件的实际生成、RR外置和记录写出；Sun NEE/发光事件有自己的技术pdf与lobe语义，不能把本片D探针的输出直接当作这些事件的记录。R2仍未完成。

## R2b-3（2026-10-20，D 编号待分配）：D 太阳端点事件、外置 RR 与有效记录

沿 D231「技术可分期落地、未支持事件留本像素」的已批拆分推进。本片首次产出有效记录，但**只支持 D 的一个子域：在首对合格重连点 x_k 之后，经解析不透明延续，到更深不透明顶点的太阳 NEE 端点**。它是从 x_k 看过去的 D 事件，不是 x_k 当地的 S 事件；不套用 forced-NEE 的源分母。完整 R2 尚未完成。D 号段申请仍待用户回复，不自行分配。

### 事件与权重

- 在所有记录所属路径上查询实际命中几何，至首对合格 D 点为止；自检另有前3点需求时复用同一次查询。诊断关闭也能产出记录。G16 的不支持主命中、G18 的游走前缀继续排除；诊断快照不被后续深链查询覆盖。查询无新随机抽样，不扩 payload/48 B 段 ABI。
- 解析 continuation 额外输出实际 `f*cos/pdf` 乘数（包含离散选瓣概率），原 sampler 抽样、方向、吞吐量、pdf 原句保留。独立 `dTailWeight` 从 x_k 出射后的单位 RGB 开始，逐段乘原介质/云透射、逐点乘解析延续乘数。绝不以 `suffix / colouredPrefix` 恢复辐亮度，源前缀为零的通道仍保留。
- 每个更深太阳 NEE 是一个候选；只收该站原 `brdf * irradiance * ndl * vis`，保持原太阳 MIS 与 Disney 附加瓣。后缀的电介质、delta 或 SSS walk 停止新候选，已经生成的较短事件继续保留。立即 BSDF 命中的 emitter/sky（H）、ambient floor、RIS、薄壳 SSS、体积/云 in-scatter 均未混入 D 太阳端点；这些项与所有未支持事件继续由本像素原路径承担。
- 设 `Q_e` 为到端点 e 的所有实际外层 RR 存活概率之积，`B_k` 为源前缀与 x_k 散射的无 RR 乘数，`I_e` 为独立尾部得到的相对入射项，`F_e=B_k*I_e`。WRS 候选权重 `w_e=luminance(F_e)/Q_e`，存活者概率 `w_e/sum(w)`；写出 `W=sum(w)/luminance(F_selected)`，即 `W_tree/Q_selected`。不同深度的 Q 不同，不能用最终 Q 统一补偿。RR 杀死更深路径也不能删除之前的候选。RR 及原路径的随机流、终止与补偿保持原样；WRS 仅用独立 reuseSeed。
- 80 B 写出：实际 primary/recon 位置及几何法线、重放基 seed、出射方向、RGB9E5 相对入射、W、正有限源分母、own target、confidence=1/竞争pdf=0（D）、primary 的本帧 objectId 和 half2 UV。`targetOwn` 与源分母保留打包前精度；方向/辐亮度是既定有损格式，完整打包后源端重评与误差验收仍待 R2 后续，不能把本片因式分解检查称为完整 shift 恒等。
- bits 定义：bit0 valid；1–4 k；5–7 technique（1=D）；8–10 selected lobe；12–13 current medium 的已有两位 trait；14–15 outer medium；16–17 primary type（1=opaque）。其余位0；未知介质 trait、非解析 lobe、越界深度拒绝，不静默截断。两个 reserved uint 继续为0；objectId 仍不是跨帧稳定身份。
- 数值非法/溢出、RGB9E5 超范围或完全下溢、half UV 非有限、没有正 target 时写空记录，不钳制出一个有效高能样本。每个所属 slot 每帧必写，失败无陈旧残留。当前仍返回完整自己的 L，未读历史/空间，不能把记录贡献再次加回画面。

### 验证与边界

- 新数值回归精确枚举 WRS 索引及所有 RR 截止结局，覆盖不同端点存活概率、单事件恰好一次 RR 补偿、零 target、零前缀 RGB 通道、前后 RR 因式分解和 bit 字段往返；shader 契约钉住实际乘数、调用与写入。
- 统计增加13–19：D太阳候选、有效写出、k2、k≥3、源因式分解尝试/通过、非空树写出拒绝。有效写出=k2+k≥3；源因式分解比较 `B_k*I_e` 与原追踪该太阳项乘 Q，**不是完整 shift 身份检查**。所有统计仍按恒等诊断的1/16像素采样。
- 59套/297项测试全通过（failure/error/skipped均0），52份shader编译与spirv-val通过；构建4分17秒。补齐位编码与实际shader常量的绑定后，再跑完整Java测试（shader未变且任务UP-TO-DATE），27秒仍全通过。三份关档模块经 `tools/normalize-spirv.py` 规整化、验证并与R2a基线逐字节比较一致。
- RTX2080/617.42、普通TraceRay（SER NONE）、隔离存档 `restir-r1-smoke`、533×300→1600×900、DLSS-RR开、1 spp、max-bounces=3、空间0、identity bit0、验证层开。共56个日志frame snapshots：prefix 378,512/378,514；geometry 1,014,934/1,015,440（missing/invalid=0、ambiguous=506）；metadata replay 377,908/378,028。前缀不是零失败，不能四舍五入称作100%。
- D资格171,530/486,776（首对k2=115,582、k3=55,948）；D太阳候选38,638、有效记录写出36,669（k2=23,418、k≥3=13,251）、非空树拒绝0；因式分解38,638/38,638。以上是**日志抽样帧内的1/16像素计数**，不是全会话全部像素/帧数，深链记录不代表R5移位已验收。CSV开档774帧、switch word均1。配置前后快照逐字节相同。
- 正常保存/退出，Gradle `BUILD SUCCESSFUL in 6m 48s`、exit0；VUID=0、无device lost/composite failed/退出对象泄漏。开发账号401和OSHI进程信息异常在启动时有日志，但未阻断本地存档、光追或正常退出，未改账号/系统设置。未做同会话A→B→A，不报告GPU成本；观感留给用户。当前驱动版本与上片不同，不拿旧片绝对帧时间作对比。
- 证据：`F:\MC\Shader\evidence\restir-rework-2026-10-20\` 的构建/最终测试日志、源测试XML、三个关档规整化模块、运行日志/CSV/配置、`r2b3-build-summary.json`、`r2b3-runtime-summary.json` 及可复算脚本。核对原论文完整PDF第10–11页的§6.1/§6.2.4；未复制reference代码或常数。

**仍待 R2 后续**：扩齐 D 端点与 x_k 的 S/L/H/T（R 为已标近似），完整源端重评/打包误差与完整恒等 lane、仅记录同会话 A→B→A。R3 消费前还须显式保留本像素被复用子域的和及其余项，按计划做积分拆分，不能把复用估计直接叠加完整 L。R3–R6 未因此获得完成状态。

## R2b-4（2026-10-30，D 编号待分配）：selected-lobe 解析重评与打包误差测量

补 R2 的源端重评前置函数，尚不扩充事件域或消费历史/空间。不自行分配新 D 编号；既有号段申请仍待回复。

- `reuseEvaluateContinuation` 以现有 `BsdfContext`、pf/ps/walk share、selected lobe 和指定出射方向为输入，重建 diffuse/base anisotropic GGX/film 的 selected-lobe solid-angle pdf 与 `f*cos/pdf`。GGX 使用与原 sampler 相同的 VNDF 约简权重（F×G1(l)/离散概率）及现有数值保护；线性alpha不平方、不采用太阳NEE的mixture pdf、不重抽随机数、不追新射线。delta、walk、零选瓣概率、非有限/退化方向不伪造解析密度。以后从新视角重建的context可调用同一求值函数，但本片只在实际源context上验证。
- 首对 D 重连成立时，在恒等诊断的1/16像素上，把原采样的pdf/weight与解析重评比较；再把实际写入的oct16方向解码、重新求值。独立保留采样前的RR-free RGB前缀，避免以彩色乘数作除法。最终非空记录上比较 `原sourceWeight×原incident` 与 `解析packedSourceWeight×解码RGB9E5 incident`，并单独统计packed方向导致的selected pdf误差；RGB近似相等不能证明pdf/雅可比源分母也相等。
- 测量使用对称相对误差：scalar为 `|a-b|/max(|a|,|b|)`；RGB为 `maxAbs(a-b)/max(maxAbs(a),maxAbs(b))`，两边同时为0时误差为0。0.1%/1%只是日志分档，**不参与资格、记录拒绝、目标函数、W或画面**。保留每帧最大误差的float位模式，正有限数经uint `InterlockedMax`比较，CPU按float解码；跨日志快照取最大，不能把最大误差求和。
- 统计20–25为解析求值尝试、invalid、pdf≤0.1%、weight≤0.1%、最大pdf误差、最大weight误差；26–33为非空记录打包测量尝试、invalid、RGB误差≤0.1%/0.1%–1%/>1%三个互斥桶、最大RGB误差、最大packed pdf误差、packed pdf>1%计数。34–39进一步隔离未打包前缀组合误差、仅方向编码误差、仅入射RGB编码误差，各记最大值和>1%异常计数（组合异常也计非有限输入）。共40 lanes/160 B；26=27+28+29+30，且26=有效记录写出14。诊断关时不执行求值，原生产逻辑仍可正常记录。
- 新数值回归以原采样微表面法线为独立依据，验证反射方向重建半向量、selected pdf及BRDF/pdf，覆盖各向异性轴互换、新入射视角、雨膜双界面与独立film瓣、离散选瓣概率、零色通道、delta/walk/无效输入。另保留两个可复现的表示风险：薄光泽瓣的oct16 pdf误差可大于RGB权重误差；RGB9E5可抹去一个弱入射通道，而该通道恰是彩色接收者唯一响应的通道。没有据此擅改80 B编码或增加能量保护。

**构建验证**：60套/308项测试全部通过（failure/error/skipped=0），新增11项；52份shader编译与spirv-val通过。初始构建3分44秒；加入隔离测量后的最终构建4分15秒仍全通过。关档 `world.rgen`、`world_ser.rgen`、`world_primary.rgen` 经现有规整化工具验证，与R2a基线逐字节一致。

**真实GPU隔离结果**（RTX2080/617.42、TraceRay、533×300→1600×900、DLSS-RR开、1 spp、max-bounces=3、独立存档 `restir-r1-smoke`、验证层开）：

- 初始289个日志frame snapshots：196,182次有效记录中，62,840次打包后源贡献误差>1%，最大100%。未打包重评911,434次，pdf全部≤0.1%，weight有23次>0.1%、最大1.01728%。据此继续拆分测量，不能把全部异常先归因于压缩。
- 最终347个日志frame snapshots：prefix 2,383,660/2,383,668；geometry 6,402,868/6,406,564（missing/invalid=0、ambiguity=3,696）；metadata replay 2,379,350/2,380,189；D候选313,283、有效记录294,999（k2=209,324、深链85,675）、非空树拒绝0；源因式分解313,283/313,283。
- 未打包selected-lobe重评1,257,276次，invalid=0，pdf全部≤0.1%（最大0.00861584%）；weight有41次>0.1%，最大3.23598%。未打包前缀组合有4次>1%或非有限异常，最大有限误差3.23598%；这些少量异常仍待细分，不能称作完整恒等通过。
- 294,999次非空记录的打包组合误差：≤0.1%为6,951，0.1%–1%为189,972，>1%为98,076（33.2462%），最大100%，invalid=0。仅方向变化的贡献误差最大0.999809%，>1%计数0；仅RGB9E5入射编码造成98,065次>1%、最大100%，确认主因。packed selected pdf有37次>1%，最大4.35335%，是另一项尚未解决的方向表示误差。
- 以上均为每秒抽样日志frame内1/16像素的计数，不是所有渲染帧或像素。用户报告移动过鼠标，已标为机位有移动；同一条记录内的分解仍有效，不作固定机位或GPU性能对比。
- 两次会话均正常保存退出、Gradle exit0/BUILD SUCCESSFUL，最终会话9分13秒；VUID=0，配置快照逐字节相同。首轮由用户协助退出；复测中电脑控制被物理Esc停止，恢复工作时已验证没有Minecraft窗口，保存/退出由实际日志确认。未绕过停止控制的限制。CPU编码原型20,003组float32往返无新增丢失，仅作为下一片的修复依据，尚未混入本片生产编码。
- 证据目录 `F:\MC\Shader\evidence\restir-rework-2026-10-30\`：initial/final构建与运行日志、CSV/配置/测试XML、规整化模块、`r2b4-initial-runtime-summary.json`、`r2b4-runtime-summary.json`、复算脚本及float3编码候选。

**界限**：仍使用实际源点原始材质/法线/位置，未重新取packed primary/recon法线、UV材质或回放前缀，也未重追连接边可见性、介质。它是源端selected-lobe及方向/入射编码诊断，**不是完整shift恒等**。完整R2、其它事件和记录阶段A→B→A仍未完成。

## R2b-5（2026-10-30，D 编号待分配）：80 B 内保留完整float32入射RGB

R2b-4已隔离RGB9E5为主要损失来源。会话中展示了「利用现有两条备用lane、记录仍80 B、保留object/UV」的float32 RGB原型和20,003组CPU位往返证据；用户回复「ok继续吧」后沿此方案落地。本片只更换入射编码，方向仍为oct16，未扩充事件域或启用时域/空间复用。

- `radiance`（offset40）存R的float32位模式，原 `reserved0`（72）/`reserved1`（76）存G/B，由 `reuseStoreIncident`/`reuseLoadIncident` 统一读写。保持字段名、顺序、80 B std430 stride和既有reflection生成器；primary objectId/UV钩子独立保留。两条原备用lane从此已占用，不能再当作空闲字段。1080p双缓冲仍331,776,000 B，未增加射线或随机抽样；GPU时间成本仍待同会话A→B→A，不以代码变简单推断速度。
- 移除RGB9E5专属的65408范围限制与全打包字零判断；原有非有限/负值、无正target、非有限W/UV检查继续生效。有效性检查读取三个通道，允许R=0而G/B贡献非零。空记录仍以valid bit=0判空，所有lane清零。
- WRS选择、原相对入射值、目标、W、源分母和所有原路径L保持原式，不以压缩后的luminance反推W，也不加误差放行门。量化诊断改由同一getter读取实际float32分量。`PackedPathSegment`的48 B和它自己的RGB9E5编码没有改变。
- 新测试直接使用Slang reflection生成的真实80 B Java serializer，覆盖弱通道、低量级、R=0/G>0、20,000组有限float32位模式（含边界/次正规）、其它字段与object/UV不被覆盖，并钉住实际shader读写与诊断调用。CPU字节往返不能替代GPU闭环：验收脚本先对R2b-4实际运行摘要执行，因入射误差最大100%而失败；修复后必须重新检查GPU仅入射编码误差为0。

**验证**：

- 61套/314项测试全通过（failure/error/skipped=0，新增6项），52份shader编译与spirv-val通过，完整构建4分15秒。关档三模块经现有规整化工具验证后，与R2a基线逐字节一致；80 B实际反射stride和所有既有offset不变。
- RTX2080/617.42、普通TraceRay、独立存档 `restir-r1-smoke`、533×300→1600×900、DLSS-RR开、1 spp、max-bounces=3、空间0、恒等诊断与验证层开。78个日志frame snapshots：prefix 548,159/548,161；geometry 1,475,597/1,476,299（missing/invalid=0、ambiguity=702）；metadata replay 547,305/547,495；D候选71,901，有效记录67,859（k2=47,989、深链19,870），非空树拒绝0；源因式分解71,901/71,901。
- **67,859次非空记录的仅入射编码误差最大值=0，>1%计数=0，invalid=0。** GPU回归脚本从旧摘要的100%误差失败，变为新摘要通过；实际shader的codec闭环消除了这项损失。这里验证的是生产shader内的编码/解码及源贡献，不是独立dispatch的全局slot读回或完整shift。
- 组合贡献误差：67,852次≤0.1%、7次在0.1%–1%、>1%计数0，最大0.249969%；与仅方向贡献误差最大值一致。方向pdf仍有7次>1%，最大1.991%。未打包源求值289,204次，invalid=0，pdf全部≤0.1%（最大0.00388695%），weight有13次>0.1%、最大0.762771%；前缀组合最大0.762773%、>1%或invalid计数0。保留这些剩余误差，不以本次编码修复掩盖。
- 以上是日志抽样帧内1/16像素的计数。CSV开档1,092帧、switch word均1，配置前后快照逐字节相同；与R2b-4是不同会话/不同样本集合，不拿比例或绝对帧时间作性能、收敛或固定机位画面对照。
- 助手通过绑定本轮游戏窗口的关闭快捷键自行结束客户端；日志确认保存所有维度并正常退出，Gradle `BUILD SUCCESSFUL in 6m 10s`、exit0，后续窗口清单无Minecraft。启动/退出VUID=0，无device lost。窗口AX读取不可用，关闭结果由实际日志与窗口清单复核，没有假定快捷键调用即为完成。
- 证据仍在 `F:\MC\Shader\evidence\restir-rework-2026-10-30\`：`r2b5-build.log`、构建摘要/三模块、实际serializer测试XML、运行日志/CSV/配置/摘要、`r2b5-incident-red.log` 与 `r2b5-incident-green.log`、复算和断言脚本。CPU候选保留为有标记的历史验证材料，没有作为额外运行代码接入。

**防复发**：记录编码的验收必须同时看彩色sourceWeight下的RGB贡献和方向pdf。仅看非空/有限标记、incident本身的相对误差或ABI对齐，不能发现弱通道丢失；今后的编码变更继续沿用这条GPU隔离闭环。

**剩余**：方向编码pdf误差、少量未打包源重评异常、其它事件、完整源端/shift恒等及记录阶段A→B→A仍待完成。本片不宣称整个R2完成，也没有观感或复用降噪验收结论。

## R2b-6（2026-10-30，D 编号待分配）：完整float32方向与源重评异常取证

用户在本轮明确批准「采用96 B：保留完整float32方向」。已先以独立Slang reflection原型核实原80 B整体保留、追加Y/Z后std430 stride为96 B，并给出1080p双缓冲增加66,355,200 B（63.28125 MiB、20%）及GPU耗时尚未实测；没有以此扩大48 B segment或阴影payload。

- `dirK`（offset36）改为方向X的float32位模式，追加 `dirY`/`dirZ`（80/84）；88–95为std430尾部padding。由 `reuseStoreDirection` / `reuseLoadDirection`读写，**不重新normalize**，保留原采样向量的float32值与长度。原R/G/B、objectId/UV、W/target/jacDenom等字段offset不动。Java分配继续只认Slang reflection生成的BYTE_SIZE，shader指针步长同为96 B；没有手写第二份分配尺寸。
- 原路径采样、随机流、目标、WRS、源分母和本像素完整L不改，不以重评值回写原采样权重来制造恒等。记录仍只生产既有D太阳NEE子域，没有历史/邻居消费。新增实际serializer的有限正负float位往返、负零/次正规、非方向字段、相邻记录和尾padding检查；原入射codec回归继续保留。

**未打包源重评诊断**：先展示并检验掠射N·L、半向量/Fresnel、材质/法线状态不一致三个假设。临时探针只在weight相对误差>0.1%时，每帧以CAS保留一个见证；95 lanes只存在于诊断构建，不是最终ABI。捕获后保存原shader、patch、SPIR-V、日志、CSV、配置及逐uint位模式，再清除临时字段/文件/Java日志。

- 104个日志frame snapshots中捕获17个见证（每个日志帧至多一个，不是所有失败样本）：全为base specular、F0=(0,0,0)，源/重评法线、视角、F0逐float一致。半向量余弦相差1–3 ULP；相对weight误差最大0.648512%，最大绝对weight差4.33575e-19，样本中最大weight为3.80493e-16。
- F0=0时Schlick精确化为 `(1-v·h)^5`。用捕获的两个余弦独立预测权重比，除去该比后残差最大2.573e-7，解释了这17个异常；N·L没有对应量级的变化，未发现不同context。**结论仅限捕获样本**，不能据此把所有现有/未来源重评异常标通过，未放松0.1%/1%诊断分档或添加F0/能量下限。
- 同时验证本地Slang 2026.8的局部precise与全局fp-mode precise最小原型，均没有输出NoContraction；本轮因此没有把未验证的precise当修复，也未更换工具链或全局浮点模式。上游相关说明见 [Slang #12285](https://github.com/shader-slang/slang/issues/12285)，本地SPIR-V证据才是当前编译器结论的依据。
- 诊断会话正常保存退出、Gradle exit0/BUILD SUCCESSFUL，助手自行关闭并核实Minecraft窗口清单为空，VUID=0、无device lost、前后配置相同。机位未主动移动；不作同会话性能或画面验收。

**历史指标勘误**：R2b-4/5 lanes36/37所谓「仅方向」实际比较原采样sourceWeight与解码方向的解析重评，仍包含未打包半向量/Fresnel误差；该数值是「方向表示加源重评」的合并误差，不能单独归因于oct16。lanes32/33的packed PDF同样含原采样与解析重评误差。保留原始数字与原指标以便追溯；本片另加同context、同解析函数、原方向对解码方向的独立codec测量。

**独立编码诊断**：最终stats为44 lanes/176 B。40计编码尝试、41计编码前后任一方向分量位模式变化，42/43分别记 `reuseEvaluateContinuation(原方向)` 与同context同函数的 `解码方向` 的最大PDF/weight相对误差。21的invalid及原始源/组合误差照常保留，不能用新的0值替代它们。两组求值均在原生产shader、相同源点执行；这是codec闭环，尚非后续dispatch从全局slot读取后完成的完整shift。

**构建验证**：62套/318项测试全通过（failure/error/skipped=0，新增4项），52份shader编译与spirv-val通过，最终构建3分38秒；初始96 B构建3分49秒也通过。三个关档模块规整后与R2a基线逐字节一致；实际生成BYTE_SIZE=96、DIR_Y_OFFSET=80、DIR_Z_OFFSET=84。48 B segment与其它ABI测试继续通过。

**GPU最终验证（2026-10-30运行，2026-10-31收尾）**：RTX2080/617.42、普通TraceRay、533×300→1600×900、DLSS-RR开、1 spp、max-bounces=3、独立存档 `restir-r1-smoke`、空间0、恒等诊断及验证层开。169个日志frame snapshots：

- 独立方向codec 625,437次，方向分量位模式变化=0，独立PDF/weight误差最大值均为0；入射codec误差也为0。原采样与packed求值仍独立比较，145,029次非空记录（k2=101,819、深链=43,210）的组合误差全部≤0.1%，最大0.0280282%；packed PDF >1%计数0，最大0.00355132%。方向回归脚本在旧R2b-5实测1.991%数据上失败，在本片数据上通过；入射codec回归继续通过。
- D候选153,544，源因式分解153,544/153,544，非空树拒绝0。prefix 1,182,947/1,182,951；geometry 3,181,431/3,183,038（missing/invalid=0、ambiguity=1,607）；metadata replay 1,180,997/1,181,418。编码闭环通过不代表这些其它恒等通道全部通过。
- **源重评仍开放**：625,437次未打包求值，invalid=0，PDF全部≤0.1%（最大0.00687628%）；weight有21次>0.1%，最大19.1681%。前缀组合有2次>1%或invalid，最大有限误差19.1681%。此最终构建没有临时见证探针，不能用之前17个F0=0样本直接解释本轮较大异常，也不把其绝对影响判为可忽略；下一片优先捕获>1%的原始样本。
- CSV开档2,406帧，switch word均1；前后配置逐字节相同，VUID=0、无device lost。会话7分36秒、Gradle exit0/BUILD SUCCESSFUL，日志确认保存所有维度及Stopping。助手尝试正常关闭时Windows拒绝交互访问，用户明确接手关闭；随后电脑控制被物理Esc停止，未继续调用或绕过。正常退出由日志确认，没有将失败的快捷键当成关闭成功，也不声称最终窗口清单已核实。
- 以上是日志抽样帧内1/16像素的统计，机位未经固定性验收；不比较不同会话的绝对帧时间、噪声或通过率来推断性能/收敛。未做A→B→A或用户观感验收。
- 证据目录 `F:\MC\Shader\evidence\restir-rework-2026-10-30\`：`r2b6-build.log`、测试XML/构建摘要/规整化模块、实际生成serializer、witness原始patch/shader/SPIR-V及逐位分析、运行日志/CSV/配置/摘要、direction red/green和incident green脚本结果。

**剩余**：较大源重评异常取证、其它D/S/L/H/T事件、完整源端/shift恒等及仅记录A→B→A。R2和R3–R6继续保持未完成；本片不改变原完整L，没有启用时间/空间复用。

## R2b-7（2026-10-31，D 编号待分配）：复现19.168%的源重评误差与稳定公式原型

R2b-6的96 B方向编码修复已收尾为 `975217e` 并推送。本片先取证较大源重评异常，没有擅自改变原采样器的浮点权重。

**可证伪假设**：①F0=0时 `1-v·h` 的舍入经五次方放大，预测半向量余弦只差少数ULP且权重比可由Fresnel比解释；②掠射N·L的误差放大，预测出射余弦也有相应相对变化；③材质/法线/视角状态不同，预测原始位模式不一致。沿diagnose流程，增加临时见证而非先修改任何BSDF。

- 临时stats为111 lanes/444 B，保留既有44 lanes，再以CAS在每帧保存第一条weight误差>1%的样本。保存原/重评half-vector、余弦、权重、完整source prefix、source weight及context；Java在常规1 Hz限流之前记录见证，避免罕见异常落在未输出的帧。仍不是每帧所有失败样本或全局最大值捕获。
- 取得25个全帧见证，全部base specular、F0=(0,0,0)，原/重评n、v、F0逐float一致，半向量余弦相差1–3 ULP。最大相对weight误差19.16806698%，复现了前片的19.1681%。该样本余弦为0.9999986290931702与0.9999985694885254，对应weight为4.8139070e-29与5.9554521e-29；两个half-vector只在Y分量差1 ULP，其double复算长度平方为0.9999999733与0.9999998572。
- 25例的权重比均可由 `(1-cosHalf)^5` 的比值解释，剔除Fresnel比后的最大残差1.4035e-7；最大绝对weight差6.66797e-23，最大前缀组合绝对差6.24536e-23。证据支持这批大异常来自half-vector重建/单位长度舍入下的零F0病态数值，未见context错配。绝对差只描述这些局部见证，不能推断所有HDR尾部贡献可忽略；其它未捕获情况继续保留诊断。

**游戏取证验证**：诊断构建62套/318项测试与52份shader校验通过。RTX2080/617.42、TraceRay、533×300→1600×900、DLSS-RR开、1 spp、max-bounces=3、既有独立存档、验证层开，配置快照前后逐字节相同。常规1 Hz的161个日志快照计574,538次源求值，PDF全部≤0.1%，weight有27次>0.1%、其中最大2.21229%；该取样集合与上述全帧见证不同，不能用574,538作为25例的发生率分母。131,820条有效记录全部组合误差≤0.1%，方向codec的574,538次位/PDF/weight误差仍全0；源因式分解139,724/139,724，非空树拒绝0。游戏5分34秒、exit0/BUILD SUCCESSFUL、VUID=0、无device lost。助手正常关闭并由保存日志与后续空窗口清单确认，无用户代关或电脑控制中止。

**稳定公式候选（尚未接入）**：对局部单位方向 `v̂`、`ŵ`，Schlick的 `m=1-cos(theta/2)` 可等价写为：

```
m = ||v̂-ŵ||² / (4 + 2||v̂+ŵ||)
F = F0 + (1-F0) m⁵
```

分子直接取方向差，避免两个接近1的数相减；分母从方向和求长度，也避免相反方向时 `sqrt(1-q)` 的消减误差。原型先局部normalize两个BSDF输入，不修改任何追踪射线，再算同一Schlick式。source sampler与解析重评若共用该函数，其Fresnel输入将来自同一对方向，不再依赖原始/重建half-vector各自的单位长度舍入；是否使整个selected-lobe求值通过仍须集成后的原GPU诊断确认。

- CPU对20,000组方向作独立双精度对照；原型编译与spirv-val通过。另以现有LWJGL依赖编写独立、无窗口的Vulkan compute回放器，仅在证据目录，不改项目依赖或产品启动流程。RTX2080上执行25个真实见证方向加20,000组对照（F0含全零、0.04及零/非零混合色通道），GPU与double oracle最大RGB绝对差8.79136e-7、最大相对差0.0187834%，均有限非负。该验证只证明候选公式，不是生产shader完整源重评/shift恒等，更不是GPU性能测量。
- 成本与兼容性已向用户说明：局部两次方向归一化、向量和/差及平方长度、sqrt和除法；不增加射线或显存。数学BSDF和线性roughness不改，但开档原采样的浮点weight和自己的L会微调，需明确允许共享sampler与已发布源码逐行证明中的这一处数学等价替换；关档仍须与基线逐字节相同。因此按CLAUDE.md铁律1请求用户裁决「接入稳定公式」或「保留原采样浮点行为并继续记录此误差」，**当前待回复，未将未答复视为批准**。

**清理与状态**：所有临时111-lane探针、字段、日志与临时test尺寸修改已移除，生产源码恢复为 `975217e` 的44-lane/96 B版本。证据目录 `F:\MC\Shader\evidence\restir-rework-2026-10-31\` 保存原始patch/shader/SPIR-V、逐位样本/分析、游戏日志/CSV/配置、CPU原型与独立Vulkan回放器及输入/输出/摘要。当前提交仅归档诊断与待裁决方案；没有把源重评标作修复或宣告R2完成。

## R2b-8（2026-10-31，D 编号待分配）：共享稳定Schlick方向公式

用户询问「保留原采样浮点行为」的含义，明确解释原式、舍入差异、稳定公式及成本后，回复「按你说的推进」，采用R2b-7的稳定公式路线。由此结案前片的待裁决项；允许ReSTIR开档的原采样权重及自己的L出现数学等价改写引起的浮点微调，关档仍必须与基线逐字节相同。没有新增roughness、能量下限、误差门限或采样策略。

- 新增纯函数 `path_reuse_fresnel.slang`，局部normalize view/outgoing，以方向差的平方长度和方向和的长度计算 `m=||v̂-ŵ||²/(4+2||v̂+ŵ||)`，再算原Schlick `F0+(1-F0)m⁵`。该clamp仍是原Schlick的[0,1]定义域保护，没有加入epsilon/F0 floor。局部归一化不回写任何追踪射线；half-vector依然用于原GGX PDF。
- `reuseSampleContinuation`的非delta base anisotropic GGX与film瓣，以及 `reuseEvaluateContinuation`的对应两瓣调用同一helper。delta、diffuse、walk及其它材质/光照的Fresnel保持原实现；随机数调用、射线生成公式、selected-lobe PDF、GGX G1、离散选瓣概率和外置RR处理不变。原目标和WRS继续使用实际采样的source weight，不以重评值覆盖原采样来制造恒等。
- 共享sampler的严格逐行证明仅为两处批准的helper调用前缀作精确映射，钉住出现次数为2，其余证明完整保留；另以独立数值回归和实际shader调用契约约束helper。开关OFF的三模块仍另做规整后逐字节比较。record为96 B、stats为44 lanes/176 B、segment为48 B，WorldPush/阴影payload无增长。

**回归先行**：将R2b-7的25个GPU原始view/outgoing/cosHalf保存为float32位可往返的CSV fixture。新增5项测试：复现旧19.168%误差、全部真实方向的独立angle/double oracle、同向/反向极限、5,000组方向与零/非零F0、生产调用及OFF隔离。接入前5项中4项数值测试通过、生产调用因helper缺失而失败；接入后全量63套/323项通过（failure/error/skipped=0）。52份shader编译与spirv-val通过，完整构建3分7秒，三个关档模块与R2a基线逐字节一致。

共享CSV通过Fabric test resources注册并从测试classpath读取，使增量测试能追踪fixture变化；该配置只影响测试资源。最终重跑63套/323项仍全部通过（13秒，shader UP-TO-DATE，生产shader没有再改），最终XML及构建摘要已更新归档。

**实际helper的独立GPU验证**：新的compute入口直接include生产 `path_reuse_fresnel.slang`，不再复制候选函数；RTX2080无窗口Vulkan回放25个真实样本加20,000组方向/混合F0，和double oracle比较，最大RGB相对误差0.0187834%、绝对差8.79136e-7，均有限非负。该GPU oracle以double计算方向和/差；单元测试另外使用atan2叉积角度和sin构造独立oracle。它验证真实helper本身，不替代原路径loop里的source PDF/weight、RR-free前缀组合或完整shift。无GPU时间戳，不报告成本比值。

**原场景闭环**：继续使用原有0.1%源PDF/weight分档及>1%前缀组合异常计数；断言脚本先在R2b-6实际摘要上失败（625,437次中21次weight越界，最大19.1681%），修复后实际游戏测量通过。未放松原阈值、改变日志采样率或屏蔽极小权重样本。

- RTX2080/617.42、普通TraceRay、533×300→1600×900、DLSS-RR开、1 spp、max-bounces=3、独立存档 `restir-r1-smoke`、空间0、恒等诊断与验证层开，240个日志frame snapshots。源求值861,119次，invalid=0，**PDF及weight全部≤0.1%**，最大PDF误差0.00510916%、weight误差0.0281716%。前缀组合最大0.0281723%，>1%或invalid=0；只声称本轮测量阈值通过，不声称所有浮点残差为0。
- D候选209,558、有效记录198,035（k2=137,910、深链=60,125），源因式分解209,558/209,558、非空树拒绝0。所有有效记录的组合贡献误差≤0.1%，最大0.000500353%；packed PDF最大0.00312302%，>1%计数0。独立方向codec的861,119次位模式/PDF/weight误差全0，入射codec误差也为0。
- prefix 1,683,118/1,683,119；geometry 4,525,854/4,528,012（missing/invalid=0，ambiguity=2,158）；metadata replay 1,680,749/1,681,059。这些通道的残余差异没有因selected-lobe修复而被标作完整shift恒等通过。
- 会话6分46秒、Gradle exit0/BUILD SUCCESSFUL，助手通过本轮绑定窗口的关闭快捷键自行结束，日志确认保存所有维度及Stopping。VUID=0、无device lost，前后配置逐字节相同；CSV开档3,568帧、switch word均1。未再操作视角或设置；机位固定性未作验收，跨会话数据仅作数值回归，不作性能、噪声或通过率改善的比较。
- 证据目录 `F:\MC\Shader\evidence\restir-rework-2026-10-31\`：`r2b8-build.log`、测试XML/规整化模块/构建摘要、实际生产helper的无窗口GPU probe及输入/输出/摘要、unit red与原数据source red、运行日志/CSV/配置/摘要、source/direction/incident green与复算脚本。25例fixture同时纳入仓库；没有遗留临时见证探针或新增stats字段。

**状态与防复发**：本片解决已取证的零F0消减误差放大，并通过原场景selected-lobe回归。依旧没有从新视角重新取packed材质/前缀/完整几何或重追连接边，因此不是完整源端/shift恒等。其它D/S/L/H/T事件、完整shift、仅记录A→B→A与R3–R6继续开放；新增数学运算的实际GPU成本仍待时间戳同会话测试，观感由用户集中验收。后续改写继续同时检查零F0真实样本、独立double oracle和原生产loop阈值，不能仅以数学等价或RGB看似相同作为通过依据。

## R2b-9（2026-10-31，D 编号待分配）：D 的更深天空与发光命中端点

按D231已批准的分域落地继续扩充D。核对原始 `lin2026restirptenhanced.pdf` §2.3、§6.1及§6.2.4：path-tree选择一个NEE/BSDF终端事件，技术区分需保留MIS，初始RR外置；原文没有要求把全部后缀合并成一个候选。没有恢复退役的in-place估计量。

- `dTailIndirect`在选择x_k时为false，只有后续顶点成功采样一次解析延续后才为true。x_{k+1}的直接BSDF emitter/sky命中继续属于H，**不进入此D树**；x_{k+2}及以后的终端命中在尾部仍受支持时才成为D候选。太阳NEE仍可从x_{k+1}起加入D，它已有额外的NEE终端边。delta/电介质/walk/粒子继续停止新候选，已有较短事件不被抹去。
- 天空端点取原trace中已经合成的sky（含该provider原有的analytical-light MIS）；发光端点取原 `albedo*emission*emitterTint*emitterShare*rainBaseExitTransmission`。保留原proxed-emitter门和面积MIS，不重新采光、不追新可见性、不另乘一次MIS。与尾部单位前缀相乘即相对incident，所有介质/云透射沿用已有dTailWeight；云/体积in-scatter、RIS、SSS及ambient floor仍不混入本片。
- 每个端点单独执行WRS，使用到该端点为止的rrSurvival；相对incident不含prefix、x_k散射或外部RR，原W_tree计算不变。原路径L的每一条着色语句保持原样，所有新增随机数只消费reuseSeed；仍返回完整自己的L，不消费历史/邻居。
- 在D中保留的这些MIS项都来自固定后缀，重连只改变x_k的入射视角，故继续使用同一个D标签、源分母和缓存incident；无需把端点类别加入PackedPathReservoir。`selectedEndpoint`仅是本地tree的诊断来源。记录仍96 B、segment仍48 B，WorldPush/阴影payload不增长。
- 原lane13由“D太阳候选”改为“全部D候选”，17/18因式分解也覆盖全部三类；新44/45计深层sky/emitter候选，46/47计实际非空写出中WRS选中了这两类的记录，solar由总数减两类得到。stats为48 lanes/192 B，没有预留空lane或增大每像素记录。日志使用D events，并单列D endpoints；不能用候选出现替代实际记录覆盖。

**回归**：新增7项，包括混合端点的WRS精确边际化、同顶点多个贡献的相同存活率、枚举RR停止、MIS只计一次、H/D边界、记录ABI不增长及分类计数。先4项数值通过/3项生产接入失败，接入后64套/330项全部通过（failure/error/skipped=0）；52份shader编译与spirv-val通过，完整构建3分5秒。关档三个模块规整后与R2a基线逐字节一致，严格tracer/sampler证明继续通过。

**无窗口GPU回放**：compute入口直接include实际生产tree helper，RTX2080执行20,003组含变长RR存活率/彩色零通道的混合事件，验证sum、selected target、W和端点来源；选中incident逐位保持，三类各自单独非零的强制选择也通过。最大相对误差：weightSum=2.528e-7、target=1.828e-7、W=2.694e-7。20,000个随机树的solar/sky/emitter选择数4,654/6,396/8,950，分别落在独立CDF期望4,678.07/6,448.28/8,873.65的6σ范围内；这不是性能测量。独立诊断harness显式使用Vulkan1.2，probe的SPIR-V仅需Shader能力。

**真实渲染验证**：RTX2080/617.42、普通TraceRay、533×300→1600×900、DLSS-RR开、1 spp、max-bounces=3、独立存档 `restir-r1-smoke`、空间0、恒等诊断与验证层开，334个日志frame snapshots：

- D候选318,804，源因式分解318,804/318,804，非空树拒绝0。有效记录285,641（k2=201,067、深链=84,574）。候选分类solar=295,761、sky=23,039、emitter=4；实际写出分类solar=268,462、sky=17,177、emitter=2。**两种新增端点均实际写出**；该场景深发光命中覆盖较少，仅4候选/2记录，数值回放不能替代其它材质、灯光或场景下的渲染验证。
- 源求值1,196,141次，invalid=0，PDF/weight全部≤0.1%，最大分别0.00606094%/0.0760649%；前缀组合最大0.0760699%，>1%或invalid=0。所有有效记录的组合贡献误差≤0.1%，最大0.000161232%，packed PDF最大0.00466234%、>1%计数0；独立方向codec的位/PDF/weight误差与入射codec误差均0。前片源重评原阈值回归继续通过，没有屏蔽小权重样本。
- prefix 2,336,196/2,336,200；geometry 6,286,792/6,289,936（missing/invalid=0、ambiguity=3,144）；metadata replay 2,332,794/2,333,209。完整shift恒等仍未因此获得通过状态。
- CSV开档4,802帧、switch word均1，配置前后逐字节相同；VUID=0，无device lost。会话8分20秒、exit0/BUILD SUCCESSFUL，助手自行正常关闭，日志确认保存所有维度及Stopping。未移动机位或更改设置，固定性未独立验收；不拿跨会话计数/帧时间作性能或噪声对比。
- 证据目录 `F:\MC\Shader\evidence\restir-rework-2026-10-31\` 保存论文抽取、unit red、完整构建/测试XML/规整化模块、真实helper的headless probe及输入输出/摘要、运行日志/CSV/配置/摘要和endpoints/source/direction/incident回归结果。旧R2b-8摘要在新端点覆盖断言上失败，新摘要通过；解析器显式检查三类候选/记录分区和实际来源因式分解。

**剩余**：D其余贡献、x_k的S/L/H/T（R为已标近似）、完整源端/shift恒等和记录阶段A→B→A。R2未完成，R3–R6未启动消费；本片没有复用效果、GPU成本或用户观感结论。

## R2b-10（2026-10-31，D 编号待分配）：源材质与散射前缀的独立重放诊断

从同一个source segment与record.pathSeed重新TraceRay到x_k，使用新payload重建材质、法线、ray-cone与selected-lobe context，以记录中的完整方向重评散射权重/PDF，并以重放的前驱PDF、距离和目的顶点几何法线重算源雅可比分母。这里没有改为从其它像素追连接射线，也没有替用户选择G3的nearest-hit/material策略。

- 原路径另记不含介质/云透射和外部RR的散射ledger，逐次乘原continuation.weight，选择x_k时保存作独立ground truth；重评计算不读这些参考值。delta分支只导出其已有质量权重，不改变原throughput公式。禁止从实际RGB除以透射来反推散射，零透射/零通道仍可测。
- 新重放只验证opaque解析前缀（含其delta质量）；原前缀遇电介质/particle/walk则单列scope，不拒绝生产记录。原前缀受支持而重放材质/命中不同则列miss；geometry、invalid与numeric分别计数。k2与深链各有attempt/pass，所有attempt都进入一个结果桶，未通过的没有从分母剔除。
- 散射误差同时检查未加权RGB及乘选中incident后的RGB，防止很弱的前缀通道恰是该事件唯一有效通道。scatter、selected PDF和源denominator保持原0.1%诊断门限；统计48–59共12个新lane，合计60 lanes/240 B。仅诊断开时执行，额外重放不参与WRS/原L，也不改生产record96 B、segment48 B、WorldPush或阴影payload。
- D231已将段/云确定性种子留给R3/G10；本片没有重放transport、连接可见性、透明层或跨帧packed材质身份，不能称完整源端/shift恒等。此界限在开工时已向用户说明。

**构建**：新增7项回归覆盖零透射ledger、几何分母、独立fresh payload、record seed/lobe/direction输入、无未批准transport种子、分类计数与弱通道incident投影。接入前6项中4项失败/2项数值通过，最终65套/337项全通过（failure/error/skipped=0）；52份shader编译与spirv-val通过，完整构建3分19秒。严格tracer/sampler证明继续通过，关档三个模块规整后与R2a逐字节一致。

**实际运行发现残差，未判通过**：RTX2080/617.42、普通TraceRay、533×300→1600×900、DLSS-RR、1 spp、max-bounces=3、既有独立存档、空间0、恒等诊断及验证层开。270个日志frame snapshots：

- 有效记录227,864（k2=159,749、deep=68,115）。新源散射重放k2通过159,743、deep通过68,106；**geometry不匹配11、numeric越界4**，scope/miss/invalid均0。最大scatter误差0.0821044%、PDF误差1.3294%、源denominator误差1.32994%。这些计数来自1 Hz日志帧，不是全会话所有帧的总发生率，尚无原始失败样本，不能先归因于几何舍入或材质错配。
- 旧cached-context selected-lobe求值969,321次，PDF全部≤0.1%（最大0.0106224%），**weight仍有1条越界，最大0.148851%**；前缀组合最大0.148849%、>1%或invalid=0。R2b-8/9的本轮全通过不构成未来样本的保证，本片暴露的残差保持开放，未放松原门限。
- D候选253,867、因式分解全部通过、非空树拒绝0；记录solar=214,500、sky=13,363、emitter=1。全部记录packed贡献≤0.1%，最大0.000189079%，packed PDF最大0.00312302%；969,321次方向codec位/PDF/weight误差及incident codec误差仍0。
- prefix 1,893,275/1,893,278；geometry 5,090,843/5,093,306（ambiguous=2,463、missing/invalid=0）；metadata replay 1,890,611/1,890,944。以上不同检查覆盖范围不同，不合并解释其失败原因。
- 游戏7分20秒、Gradle exit0/BUILD SUCCESSFUL，助手自行关闭并正常保存所有维度，VUID=0、无device lost；前后配置逐字节相同，CSV开档3,755帧、switch均1。无性能比值或观感结论。

证据在 `F:\MC\Shader\evidence\restir-rework-2026-10-31\` 的r2b10构建/测试XML/关档模块、unit red、运行日志/CSV/配置及摘要。下一片优先捕获新重放的numeric/geometry失败与旧权重残差，分别验证材质/context、射线几何及GGX PDF数值假设；当前仅交付新增可执行诊断，**R2仍未完成**。

## R2b-11（2026-10-31，D 编号待分配）：源重放材质分叉的逐位现场

R2b-10已提交并推送为 `bace2bd`。继续沿diagnose流程对三个可证伪假设取证：射线/位置舍入经材质纹理放大；材质/法线/cone或payload重建不一致；相同context内的selected-lobe公式仍有较大残差。几何query状态与位置偏差独立记录，不混作PDF失败。本片没有改变物理或采样策略，也没有提交临时探针。

**临时见证**：保留60个正式stats lane；临时扩到233 lanes/932 B，以两个独立CAS分别保存每帧首个fresh numeric/geometry失败及旧cached-context PDF/weight越界。原路径保存实际x_k的context、入射射线/距离/cone/前驱PDF/几何法线/selected weight；与重放对照的原始uint位模式在1 Hz汇总限流前回读。不改变原L、选中记录或门限，不是每帧所有异常或全局最大值捕获。取证构建65套/337项测试、52份shader校验通过。

- 全帧见证共31条numeric（k2=16、deep=15），31条的入射方向和目的几何法线均逐位一致，但射线起点均有差异（最大欧氏距离2.13248e-5）。31条着色n、t、F0与ndv改变，18条rough/alphaX/alphaY改变；pf/ps/walk/film与diffAlb均相同。31条几何位置偏差都仍在原容差内（最大为容差的0.47713倍），**位置相近不足以证明材质重放一致**。
- 最大现场selected PDF为189.70529175与2.13997793，相对误差98.871940%，源分母误差98.871946%；该条源散射误差1.522524%。源/重放入射相同、起点X差一个float ULP，着色normal和F0已明显不同。另一条selected PDF从5.48227到233.70897，相对误差97.654235%。不能把这种残差判为可忽略，或用放宽几何容差/筛掉小贡献来消除计数。
- 独立double以两份已捕获context重算各自GGX PDF，预测PDF比值与实际比值的最大残差2.22540e-6。另建无窗口compute直接import生产bsdf并include真实selected-lobe evaluator，RTX2080回放31对/62个context：对捕获PDF的最大相对误差2.46198e-6、对selected weight为2.27144e-7，均有限非负。这支持**这批较大差值发生于材质/context输入分叉**，不是把同一context交给公式就出现近99%误差；不构成所有求值器残差或完整路径恒等的证明。
- 31条原context的F0均可由half精确表示，而31条重放context的F0均不满足这一点。Payload.f0声明为half3、closest-hit SPIR-V也含16位lane，因此**还须直接捕获TraceRay返回时的原始payload、cone及逐顶点状态，核对实际半精度转换与context构建**。当前缺少这层原始快照，不能把全部分叉先归因于UV/位置舍入、FMA或某个编译器缺陷，也没有足够依据直接改采样器/材质精度。
- 全帧几何见证116条：115条query成功但重放/缓存位置差超过原容差，1条query ambiguous；与numeric是互斥分类。旧cached-context见证为0，**未复现R2b-10的0.148851%残差，仍开放**；探针会改变生成代码，不能因本片没复现便宣布修复。

**运行汇总与采样边界**：同既有配置和存档，验证层开。244个1 Hz日志frame snapshots中205,502条记录（k2=144,560、deep=60,942），fresh pass分别144,559/60,938，geometry=4、numeric=1，其余失败桶0；该集合的最大PDF误差0.222471%、源分母0.219461%、scatter0.0702174%。这些日志帧与上面的全帧见证不是同一个采样集合，**不能用205,502作147条见证的发生率分母**。873,250次cached-context求值在本次汇总中全部≤0.1%，最大PDF0.00352664%、weight0.00266246%；D候选228,916个因式分解全过、非空树拒绝0，codec位/PDF/weight及incident codec误差0。仍不替其它会话未捕获样本背书。

游戏7分37秒、Gradle exit0/BUILD SUCCESSFUL，助手自行关闭，保存所有维度/Stopping正常；VUID=0、无device lost，前后配置逐字节一致，CSV开档2,681帧。没有移动视角、改设置或做视觉/性能验收，不跨会话比较通过率或帧时间。

**清理**：见证shader/patch/SPIR-V、逐位JSON、double与真实生产evaluator的GPU输入输出/摘要、构建测试XML及日志/CSV/配置均保存在 `F:\MC\Shader\evidence\restir-rework-2026-10-31\` 的r2b11文件中。源码、stats及临时尺寸断言按备份逐字节恢复到 `bace2bd`，临时include/辅助文件移除，正式stats仍60 lanes/240 B。本片只归档诊断，**材质分叉根因及修复、旧0.148851%残差、完整源端/shift与其余R2任务继续开放**。下一次取证应在每个TraceRay刚返回、几何query及材质重建前后保存payload/packed-cone/ro/rd/hitT，定位第一个不一致边界。

清理后重新完整构建4分13秒，65套/337项测试与52份shader校验通过；关档三个规整化模块再次与R2a逐字节一致。仓库内搜索临时tag/helper/include与233-lane尺寸无残留，代码及测试相对 `bace2bd` 无差异，仅本片文档更新；未因清理再启动游戏。

## R2b-12（2026-11-04，D 编号待分配）：源重放的位置舍入与 payload 精度对齐

沿R2b-11的三个假设继续定位：TraceRay返回已不同；query/返回边界影响payload；相同命中在位置或材质构建时分叉。临时645-lane/2580 B探针保存前三个顶点的输入射线/cone、查询前后与构建前的payload、hitPos、BSDF法线/F0及出射状态，每帧CAS保存首条numeric/geometry失败，限流前回读。没有改生产record、payload或segment的ABI。

**现场与可复现原因**：取证会话保存61条见证（numeric=16、geometry=45），最大现场PDF相对差26.98907%、散射差3.79461%。其中164个源顶点的hitPos全部吻合分步FP32乘法/加法；164个重放顶点全部吻合融合乘加。在164个源顶点中，融合计算有63个位置与源端不同。独立有理数oracle逐步做round-to-nearest-even，再用无窗口GPU执行两种顺序，328组输入全部逐位符合各自oracle。源与重放的cone更新则均符合融合顺序（103组全符），没有顺手改cone。

- 原始探针的packed-half辅助视图在部分native-half向量的分量顺序上未通过自洽检查，**这些字段不作为payload损坏的证据**。按可靠的射线/标量/float3数据重新排序，59例首先分叉于第一顶点hitPos，2例首先分叉于第一顶点构建F0；查询前后及原路径构建前未观察到这些可靠字段被改写。复制到half结构与直接用于float上下文的数值不同，也不能直接命名为内存被clobber。
- 精度实例：原路径构建F0为0.035308837890625，重放构建为0.03529411926865578；前者恰为后者按binary16舍入再展开的结果。payload声明为half，重放内联后的更宽寄存器值不能当作原路径已经物化的half值使用。输入几何很近而材质/PDF明显变化的旧现场，由位置舍入差及上下文有效精度差共同解释；没有放宽原0.1%门限或修改纹理/roughness资格。

**局部修复**：新增 `path_reuse_numerics.slang`，只由reuse variant包含。

- `reuseReplayPosition`在SPIR-V内对乘法与加法分别标NoContraction，保持已捕获源端的分步FP32舍入。修改仅在replay内；原tracePathReuse的hitPos语句、共享采样器、随机流、原路径L与记录选择逻辑保持原代码。普通与SER的编译产物新增真实指令检查，避免把源级precise写上便假定它已生效。
- 每个重放命中及目标材质重建前，显式物化payload.albedo/normal/f0的binary16精度，其余字段不动。实现以整数位运算完成ties-to-even舍入、保留符号零及全部half次正规数；正常值截去13个尾数位并处理进位，次正规值按2^-24的整数倍构造精确FP32结果，溢出/非有限值遵循binary16类别。这里没有能量floor、roughness平方、截断RGB或新采样策略。
- 两个较短原型被实测否决：OpQuantizeToF16会在本机冲掉half次正规值；普通转换及PackHalf/UnpackHalf往返在有效启用shaderFloat16/shaderInt16的GPU探针中仍没有落实舍入。最终使用可验证的整数舍入，未将上述原型留在产品代码，也未用放松subnormal预期的办法让检查通过。
- 不增加射线或每像素存储；额外整数运算仅在replay路径，真实GPU时间成本未测。正式stats仍60 lanes/240 B，record96 B、segment48 B、WorldPush与阴影payload均不增长。R2仍只记录并返回自己的完整L，尚未启用历史/邻居消费。

**回归与GPU验证**：164个真实源位置作为float32可往返CSV纳入测试资源。初始4项测试中3项独立数值通过、生产接入失败；随后补上次正规/符号零/偶数舍入及普通/SER实际SPIR-V装饰检查，共新增6项。最终66套/343项测试全部通过，52份shader编译/spirv-val通过；最终生产源码完整构建3分29秒，追加编译产物测试后全量测试22秒。三个OFF模块规整后与R2a逐字节一致，严格tracer/sampler证明继续成立。

无窗口compute直接include最终生产helper：5,339组（328组实际射线输入、5,000组随机输入、11组边界）的位置、half舍入及写回native payload后读取全部逐位通过；另以全部有限half值及相邻值中点/两侧构造正负两组，共126,974例，直接/写回payload两条路径均与独立NumPy binary16参考逐位一致。探针显式查询并启用shaderFloat16/shaderInt16；早期未启用所需能力的native-half尝试不作为结论依据。最终raygen确有2个受约束乘法及2个受约束加法，两个reuse变体均由单元测试核对，非仅源码字符串证明。

**真实渲染闭环**：维持既有存档/配置，RTX2080、普通TraceRay、533×300→1600×900、DLSS-RR、1 spp、max-bounces=3、空间0、恒等诊断及验证层开。350个1 Hz日志frame snapshots：

- 有效源记录299,472（k2=210,921、deep=88,551），fresh source散射/PDF/源分母全部通过；scope/miss/geometry/invalid/numeric各为0。最大散射误差0.0001868%、PDF0.00432646%、源分母0.00432771%。旧R2b-10实际摘要在同一无排除/原0.1%断言上失败，修复后的摘要通过。只声明本场景和测量集合的闭环，不推断所有未来输入为零误差。
- prefix 2,448,174/2,448,174；metadata replay 2,445,157/2,445,157。源geometry query仍有3,175个ambiguous（missing/invalid=0），不把真实未决几何算作修复后全部唯一可解。
- D候选334,607个源因式分解全过，非空树拒绝0；实际记录solar281,478、sky17,992、emitter2。全部299,472条packed贡献≤0.1%，最大0.000188986%；1,254,038次方向codec的位/PDF/weight误差及incident codec误差均0。
- **旧cached-context残差仍开放**：1,254,038次selected-lobe求值的PDF全≤0.1%（最大0.0106224%），weight有1条越界、最大0.14885%；前缀组合最大0.148849%，>1%或invalid=0。该通道与本片fresh source输入重建不同，不能因后者全通过就把前者关闭，下一片优先捕获这条残差的原始sample/eval现场。
- 会话8分49秒、Gradle exit0/BUILD SUCCESSFUL；助手自行关闭，日志确认保存全部维度及Stopping。VUID=0、无device lost，配置前后逐字节一致；CSV开档4,788帧、switch均1。前一取证会话9分1秒也已自行正常退出。没有移动机位、做视觉验收或报告跨会话性能比值。

**工具链事实与文档冲突**：本机Gradle实际解析到PATH的SDK1.4.350.0/Slang2026.8。CLAUDE.md和开发指南前文写最低2026.14，开发指南后文却明确接受CI随SDK使用2026.8；不能继续把未核实的2026.14当作本机构建事实。本片使用既有CI基线验证局部修复，未更改系统/用户配置或静默切换全项目编译器。官方2026.14仅在证据目录独立下载并按release资产SHA256校验，其单模块试编译因现有volume.slang:801的skyOpen初始化诊断被warnings-as-errors阻断，未改volume或屏蔽诊断；版本声明/CI基线应后续统一。源级precise与早期SPIR-V后端的问题可参考[Slang #11933](https://github.com/shader-slang/slang/issues/11933)、[#12198](https://github.com/shader-slang/slang/issues/12198)；本片以本机实际产物和GPU结果为准，局部汇编用法参照[Slang interop文档](https://docs.shader-slang.org/en/stable/external/slang/docs/user-guide/a1-04-interop.html)，不将文档或外部issue代替本机证明。

证据在 `F:\MC\Shader\evidence\restir-rework-2026-11-04\`：boundary原始shader/patch/SPIR-V/日志/CSV/配置/61条逐位JSON、可信字段校正与有理数/GPU位置复算、被否决的half原型、最终真实helper的GPU输入输出/摘要、unit red/XML、最终测试/构建/三OFF模块与运行摘要、source replay red/green脚本。临时645-lane探针和字段/日志/辅助include均已移除。**本片结案的是已复现的源重放位置/精度分叉；旧0.149%权重、transport、完整shift、其它D/S/L/H/T及R2后续仍未完成**，观感继续留给用户。

## R2b-13（2026-11-05，D 编号待分配）：掠射 selected-lobe 权重残差的点积取证

本片接续 `22732ce`，只结案已捕获残差的原因，不提前宣称修复。先按零F0的Fresnel放大、极小权重下溢/运算顺序、实际上下文不同三类假设取证。第一轮临时125-lane探针仅在旧cached-context的weight误差超过原0.1%时，用CAS保存每帧首条失败的原始uint位模式；不向采样器增加上下文字段。第二轮127 lanes再从采样器/重评器各自导出实际使用的 `n·wi`。回读在1 Hz日志限流之前；这不是每帧全部失败或全局最大值捕获，也没有新增生产记录字段或放宽门限。

**现场与因果验证**：第一轮捕获1条0.1488505%失败，F0各通道为0.040008544921875，sample/eval的n、v、F0、diffAlb逐位相同，权重约6.4e-6。排除这条属于零F0或float32次正规下溢；独立double用实际上下文重算Fresnel/Smith/probability，再枚举FP32点积累加顺序，能够分别还原两侧权重。第二轮直接捕获2条，确认实际点积与两种FMA累加顺序逐位吻合：

| 现场 | 采样器实际 n·wi | 重评器实际 n·wi | 采样权重（灰色RGB） | 重评权重（灰色RGB） | 相对权重差 |
| --- | --- | --- | --- | --- | --- |
| A | 5.436026185634546e-6 | 5.427935320767574e-6 | 8.46804596221773e-6 | 8.455441275145859e-6 | 0.1488500% |
| B | 8.525106750312261e-6 | 8.515793524566106e-6 | 1.6872267224243842e-5 | 1.6853835404617712e-5 | 0.1092433% |

两条的source均符合先舍入 `ny*wiy`、再FMA加入x、最后FMA加入z；eval均符合先x、再y、最后z。把各自实测点积代入独立double Smith公式，对实际weight的最大相对残差5.98e-7，远小于原0.1%门限。它们同为掠射且F0非零，原材质、方向和理论公式一致，点积中大项相消后的FP32舍入差经G1传播，解释了捕获的权重差。这里不是GPU内存损坏，也不把正常的浮点非结合性直接称为编译器缺陷。跨会话的diffAlb/ps可以不同，不声称两轮是完全相同的材质上下文；第二轮每条的两侧输入仍逐位一致。

**不能只看1 Hz快照**：第一轮55个日志帧的192,968次cached-context检查全部在门限内，但逐帧见证实际捕获1条越界；第二轮78个日志帧的275,684次也全部在门限内，逐帧见证却捕获2条。两种采样集合不能混用分母或由前者推出全会话通过。摘要新增独立的 `per_frame_captured_weight_failures`、`per_frame_max_weight_error_pct`，两轮 `original_weight_gate_passed` 均为false；没有用小权重屏蔽、扩大容差或删掉失败来通过验证。

**可供裁定的原型，尚未接入生产**：在证据目录实现固定x→y→z的FP32点积，首个乘法显式NoContraction，后两项显式FMA。Slang2026.8实际SPIR-V中的乘法装饰和Fma指令已核对，spirv-val通过。独立Vulkan/RTX2080执行45,003组（3条已捕获输入、25,000组近正交方向、20,000组一般方向），原型与逐步FP32参考逐位一致，交换两个向量操作数也逐位一致。原型解决的是两处使用同一种舍入顺序，不等于改用double或消灭所有掠射的FP32绝对误差；尚无真实raygen接入后的回归或GPU耗时结论，不能把这45,003组当作正式修复验收。

建议经用户裁定后，在reuse采样器与selected-lobe重评器中共享上述 `n·wi` 定序点积，覆盖film/base的半球判定与Smith输入、diffuse PDF及重评入口。理论BSDF、线性roughness、随机数和生成的方向公式不变，但会进一步改变开档FP32权重，极贴半球边界时也可能改变通过判定；无新增射线/显存，局部指令顺序受约束的实际GPU代价待测。此选择超出R2b-8只替换Schlick表达式的已批例外，按§3.1先由用户决定。另一选项是保留当前算术及开放残差，再安排其它R2工作；不能把当前驱动的source累加顺序只硬编码到eval后当作跨编译器保证。

**范围、运行与清理**：两轮仍为既有 `restir-r1-smoke`、533×300→1600×900、DLSS-RR、1 spp、max-bounces=3、空间0、恒等诊断和验证层开；分别3分43秒/4分20秒，均由助手正常关闭，exit0/BUILD SUCCESSFUL、保存全部维度、VUID=0、无device lost、前后TOML逐字节一致。日志抽样fresh source分别45,423条/64,484条全部通过，scope/miss/geometry/invalid/numeric均0；它们与本片cached-context见证不同，不替代后者。没有视觉验收或性能比值结论。

两轮取证构建各66套/343项测试、52份shader校验及三个OFF模块对R2a逐字节比较通过。临时helper/include、debugNdl、125/127-lane日志和尺寸断言已移除，11个源/测试文件按取证前备份逐字节恢复，源码相对 `22732ce` 无差异；正式stats仍60 lanes/240 B，record96 B、segment48 B未变。证据目录 `F:\MC\Shader\evidence\restir-rework-2026-11-04\` 保存两轮r2b13日志/CSV/TOML/摘要、3条原始uint/float见证、FP32顺序枚举/double复算、独立GPU原型与输入输出、临时源码和清理manifest。**R2仍未完成；本片没有交付点积修复，也没有启用历史/邻居消费。**

清理后重新完整构建3分52秒，66套/343项测试及52份shader校验通过；三个OFF模块再次与R2a规整化基线逐字节相同，严格tracer/sampler证明保持通过。仓库内无临时tag/helper/debugNdl或125/127-lane残留，仅更新本日志与DEVELOPMENT.md的当前进度/待裁定项；没有为清理后的同源码再启动游戏。

## R2b-14（2026-11-05，D 编号待分配）：共享定序点积与逐帧回读累计回归

用户已明确批准R2b-13的共享定序 `n·wi` 方案。本片接入并完成既有场景的原0.1%门限回归，结案的是已捕获的掠射点积累加顺序残差；不把有限场景的通过扩展为全部未来输入或完整shift恒等证明。

- 新增reuse专属 `path_reuse_dot.slang`：先将x乘积按FP32舍入并显式标NoContraction，再依次用显式FMA加入y/z。采样器的film/base半球判定与Smith输入、diffuse PDF及selected-lobe重评入口共用这一实现。理论BSDF、线性roughness、随机流和生成出射方向的公式不变；开档权重及极贴半球边界的通过判定仍有已告知并获批的浮点微调。没有增加能量floor、近零角度例外或放宽原误差门限。
- 原tracer逐行契约只追加用户批准的三个normal-dot调用点例外，精确要求film/base两处与diffuse一处，随后还原成原dot再做原有严格比较；R2b-8的两个Schlick例外继续单独计数。没有整段跳过采样器、PDF、权重、RNG或射线公式的比较。原OFF tracer、96 B record、48 B segment、WorldPush和阴影payload均未改。
- 修复1 Hz汇总的观测盲区：CPU对每个完成的readback累计全部60 lanes，uint计数扩为unsigned long累加，非负float误差保留最大值，累加发生在日志限流与空检查之前。每秒输出累计结果，失败帧在限流前独立报警；buffer生命周期结束时输出最终累计并清零，避免下一次开档混入旧数据。沿用60 lanes/240 B GPU缓冲及原frame-ring所有权，不加同步等待、射线或GPU存储。新增CPU工作为每次readback读60个lane并更新一个480 B数组；真实CPU/GPU耗时未测。

**回归与独立GPU复算**：两条直接记录过源/eval点积的真实掠射输入纳入CSV。接入前3项新dot测试中，现场数值复现通过，实际共享调用点与实际SPIR-V指令链两项失败；接入后通过。另3项测试验证两次日志之间的失败不会被后续正常帧覆盖、unsigned计数和buffer生命周期清零、累加/报警早于限流且ABI仍60/240。最终68套/349项测试全部通过，52份shader编译/spirv-val通过，完整构建3分23秒；普通/SER实际产物都保留受约束的首乘法及两次Fma指令链。三个OFF模块规整后与R2a逐字节一致。

无窗口compute直接include最终生产dot helper，45,003组实际/随机/近正交输入与逐步FP32参考逐位一致，交换操作数也逐位一致。另以实际生产selected-lobe evaluator回放3条旧失败上下文，定序点积逐位吻合，权重/PDF对独立参考的最大相对误差分别3.953e-7/4.082e-8。这里没有复制一份简化求值器来代替生产实现；实际采样器与重评器的组合仍由下述真实raygen验证。工具链沿用R2b-12明确记录的SDK/Slang2026.8基线，未改变尚待统一的版本声明。

**真实渲染闭环**：既有 `restir-r1-smoke`，RTX2080、普通TraceRay、533×300→1600×900、DLSS-RR、1 spp、max-bounces=3、空间0、恒等诊断及验证层开。累计日志257行，单一buffer生命周期，最终结果为：

| 所有已完成回读帧的累计检查 | 实际结果 |
| --- | --- |
| readback帧 | 3,564 |
| cached-context selected-lobe | 12,780,519次，invalid=0，PDF/weight全部≤0.1% |
| 最大cached PDF / weight误差 | 0.0319349% / 0.00148790% |
| fresh source k2 / deep | 2,106,719 / 892,551条，全部通过 |
| fresh scope / miss / geometry / invalid / numeric | 各0，无排除失败 |
| 最大fresh scatter / PDF / denominator误差 | 0.000184097% / 0.00610037% / 0.00610038% |
| direction codec | 12,780,519次，位变化/PDF/weight误差均0 |
| incident codec / 逐帧失败报警 / VUID | 均0 |

原source-evaluation验证脚本对R2b-12摘要按原门槛失败（exit1），对本片1 Hz摘要通过（exit0）；新增累计解析器对所有已回读帧的原门槛也通过，未只选取日志帧或剔除小权重。累计解析器验证同一生命周期内各计数/最大值单调、不重复累加每秒的累计快照，生命周期重建则分别结算；本会话只有一个生命周期。诊断仍是每帧1/16像素，不是每个像素的全量保证；结束时尚未完成回读的帧也不在上述分母。

保留的256个1 Hz快照另作生产事件/记录范围证据：915,392次cached检查全部通过，215,091条有效记录（k2=150,905、deep=64,186）fresh检查全部通过；239,580个候选源因式分解全部通过，非空树拒绝0。实际记录solar=202,128、sky=12,963、emitter=0，故本会话没有新增深发光端点覆盖。prefix为1,789,122/1,789,122，metadata replay为1,786,861/1,786,861；源几何query仍有2,366个ambiguous，missing/invalid=0，不称所有几何唯一可解。上述日志帧的计数不与全readback计数混用分母。

会话7分4秒，助手通过正常关闭流程退出，日志确认保存全部维度，Gradle exit0/BUILD SUCCESSFUL；无device lost，前后TOML逐字节一致，CSV开档3,570帧、switch word均1。CSV与readback的统计边界不同，验收明确使用上表3,564个已回读帧。没有移动机位、做用户视觉验收或报告跨会话性能比值。

证据归档在 `F:\MC\Shader\evidence\restir-rework-2026-11-04\` 的r2b14文件：unit red、68套测试XML/构建及三OFF模块、真实生产helper/evaluator的GPU输入输出/摘要、运行日志/CSV/TOML、原门槛red/green及累计解析器与摘要。没有重新引入临时见证字段、helper或125/127-lane缓冲。**已修复本次捕获的点积残差，R2仍未完成**：其余D贡献、S/L/H/T、完整源端/shift恒等、记录阶段A→B→A继续开放；transport种子仍按计划留给R3/G10，历史/邻居尚未消费，仍返回完整自己的L。GPU成本和观感按原计划后续验收。

## R2b-15（2026-11-05，D 编号待分配）：深层环境底色与薄壳表面项

按既有分域计划扩充D：更深顶点的环境底色、太阳薄壳SSS分别成为独立WRS候选，保留原色彩、雨层透射及各端点RR存活率。每条原L着色语句保留，仍只记录，不消费历史/邻居。环境/薄壳 provenance使用局部tree标签3/4，记录继续使用D技术，不增加96 B record字段、segment或payload。诊断增加60–63四个实际使用的candidate/record lane，合计64 lanes/256 B；CPU累计器沿用按lane类型累加/取最大值，增加的四lane都是计数。

新增2项回归覆盖真实生产挂接、与RR和零前缀通道的WRS边际化；接入前9项terminal测试中2项失败，最终68套/351项全通过，52份shader编译/校验及三个OFF模块对R2a的规整化字节对照通过。完整构建3分38秒。没有把RIS/体积光源或非解析后缀静默归入新D事件；其余技术与完整移位继续待做。

**定向夹具验证**：临时仅在CPU WorldPush组装处置起已有环境底色bit，既有隔离存档临时启用thin档；没有改shader函数、SSS材质或可见性来让事件通过。采集后RtComposite和TOML按备份逐字节恢复，thin档开启本身不等于材质/光线实际覆盖该事件。6分1秒会话，助手正常关闭并保存全部维度，exit0/BUILD SUCCESSFUL，VUID=0、无device lost。

2,609个已完成readback帧中，环境底色候选2,524,942个、实际写出1,235,215条；薄壳候选/记录均0，**本机位未覆盖薄壳runtime，仍需专门夹具，不把零计数判通过**。9,360,766次cached PDF/weight全部≤0.1%，最大0.0131068%/0.00135193%；2,576,046条fresh source（k2=1,771,310、deep=804,736）全部通过，scope/miss/geometry/invalid/numeric均0，最大scatter/PDF/denominator为0.000397617%/0.00592501%/0.00592368%。codec位/PDF/weight及incident误差0，逐帧失败报警0。夹具配置前后逐字节一致，随后恢复原配置；没有做视觉/性能验收。

清理后全量测试再通过（shader任务up-to-date），无临时ambient标志残留。证据在 `F:\MC\Shader\evidence\restir-r2-r3-2026-11-05\` 的r2b15构建/red/关档对照、临时CPU夹具备份、日志/CSV/配置/累计与surface摘要。**R2未完成**：下一步是局部L/S/H/T各自源求值及记录、完整源端/shift恒等、薄壳专项覆盖和记录A→B→A；R3的G3/G6/G10/G11已列具体接法并向用户请示，未将等待答复当作批准。

## R2b-16（2026-11-05，D 编号待分配）：局部事件的共享表面求值

为后续S/T/L/H源求值建立共享数学入口。将原opaque太阳BRDF的52行计算原样抽到reuse专属 `path_reuse_local.slang`，源tracer直接调用；原L累加、能量floor启停、线性alpha、continuation竞争PDF、仅sampled部分MIS及未采样Disney瓣保持原语句。严格shipped-line契约把该helper实际源码纳入逐行证明，原三个顶点函数的次序证明和两项已批数值例外继续成立，未跳过太阳公式。另有局部ambient/thin response及BSDF-hit MIS小函数；没有把它们说成各技术的记录生产已齐。

新增3项测试，接入前两项生产挂接失败、独立MIS分区数值通过；接入后69套/354项测试全通过，52份shader编译/校验通过，完整构建3分47秒。三个OFF模块与R2a规整化字节相同；两个reuse模块与R2b-15规整化字节不同，**没有声称ON字节相同或以此替代后续实际场景回归**。

独立Vulkan compute直接include正式helper，20,003组各向异性、湿膜、MIS开/关和正反面上下文，与独立double参考比较：太阳/ambient/thin最大相对误差5.355e-5/4.005e-6/5.259e-6，竞争PDF最大5.622e-5；10,020组薄壳结果实际非零，全部有限非负。参考模型初次误写水膜F0为0.02、遗漏MIS关闭时水膜alpha floor，被GPU对照检出；按生产已存在的0.020373和0.02 floor纠正参考后通过，没有修改生产常数去迎合参考。此synthetic context验证不冒充R2b-15缺失的真实SSS材质/光路覆盖。

依据补查的[官方补充材料](https://research.nvidia.com/labs/rtr/publication/lin2026restirptenhanced/lin2026restirptenhanced_supplemental.pdf) §1 Eq.4与§8：连接端需要保持瓣标签，路径MIS用的NEE PDF与雅可比乘积是不同量。局部记录生产、瓣/技术元数据、完整源端与shift恒等仍在后续实现，R2/R3未判完成。原始PDF/文字/page2目视截图、GPU源/输入输出/摘要、unit red/构建/三OFF对照均存同一11-05证据目录；本片未再启动游戏，实际场景回归与后续事件生产一起做。

## R2b-17（2026-11-05，D 编号待分配）：事件元数据与对应记录的单次 WRS

局部事件可能连接不同顶点，不能沿用全局D记录模板。新增`reuseTreeUpdateRecord`，沿用原来唯一一次WRS随机数，在该事件中选时同步保存其完整record；树另存selectedValue与直接累加的RR补偿sourceSum，为后续自身覆盖积分拆分建立依据，不从RGB前缀相除。当前仍只挂接已有D生产者，本片不声称局部事件或时域已经接入。

96 B记录的原尾padding 88/92分别存完整float32 NEE竞争PDF和源当前事件PDF；既有字段offset及stride不变，confPdf高半字现保留零。bits19–21存实际前驱采样瓣，22–25存事件子类；非法PDF/瓣返回空记录。Slang reflection生成Java ABI，布局/serializer同步更新，无额外显存、射线或随机数。

70套/357项测试、52份shader构建/校验通过（3分23秒）；三OFF模块规整化字节与R2a相同。独立Vulkan直接include正式tree/record helper，20,003组不同记录位置/种子/PDF候选中，选中元数据与incident位模式错误0；weightSum/覆盖RGB/W/选中RGB最大相对误差分别2.582e-7/2.255e-7/2.636e-7/5.911e-8，零target与非法前驱瓣保持空。证据为同一11-05目录r2b17-*。本片未启动游戏，后续局部事件集成后一起做真实源检查；R2完整恒等/A→B→A、R3与待裁决项仍开放。

## R2b-18（2026-11-05，D 编号待分配）：局部 S/L/T 记录与按事件重评

将双足迹计算抽成事件密度入口，解析continuation包装器仍按原规则检查当前瓣；局部NEE/终端的lobe index为0，使用正有限事件PDF与前驱解析瓣/alpha门、前向足迹，跳过不依赖incoming view的逆向密度检查。不将它们冒充diffuse continuation。共享local record在采样continuation之前构造，所以bounce cap的局部照明也能入树；x1 DI、walk前缀、特殊主命中保持排除。

接入局部太阳S、环境底色L与薄壳T，record随WRS选择，独立incident保留光照与可见性，源response保留实际局部着色；外置RR逐事件补偿。早先已选D锚点之后的项继续归D，不与局部事件重复。选中参考散射/PDF同样跟随WRS，fresh源重放按技术重新求实际材质响应与分母，不再拿全局D参考去检查局部记录。原L累加语句保持，仍无历史/邻居消费。

**构建与独立GPU**：初版71套/360项测试、52shader与三OFF规整化字节对照通过。20,003组正式事件/足迹/record helper的GPU检查，17,145组合法记录、2,858组高密度前驱被拒，技术/ℓ=0/前驱瓣/几何分母/方向位模式符合预期。太阳/ambient/thin相对误差最大5.362e-5/4.005e-6/5.259e-6；8,599组thin response非零，仍只属synthetic context验证。

**真实运行与保留的red**：基线SSS off、环境底色off，2,234个完成readback帧中，局部S实际写出4,098,249条，L/H/T均0。本片没有宣称L/T真实覆盖通过。cached散射8,020,309次均过0.1%门限；fresh k2通过3,771,542/3,771,553，deep通过2,055,761/2,055,770，geometry失败20，scope/miss/invalid/numeric均0；最大scatter/PDF/denominator误差0.0980059%/0.00455365%/0.0219944%。source factor的1Hz抽样日志全部通过，不将其冒充逐readback累计证明。方向位变化0，但两次独立求值的PDF/weight隔离最大0.000299607%/0.00111975%，所以原严格累计解析器仍判red，没有删失败、放松0.1%门限或把几何失败径直归因到流送/共享边。

**诊断修复与后续**：两个独立inline求值会选择不同的浮点融合上下文，不能把这种差异标成编码损失。仅诊断改为一个DontUnroll循环体依次求两组方向，原sampler/理论BSDF不改。正式helper的20,003组GPU检查，同位输入13,336组逐位一致；6,667组故意改动方向的负对照全部检出，SPIR-V保留DontUnroll循环。增加fresh geometry的missing/ambiguity/invalid/position四类计数，保留原总失败桶和报警；stats为72 lanes/288 B。该修复尚待实际场景复验，不以独立GPU检查提前覆盖运行时red。

**SSS覆盖勘误**：R2b-15当时仅将D-thin零计数归为机位覆盖不足，解释不充分。MAX_SSS_INDIRECT_DEPTH=1，而首个D锚点最早在hitDepth=1且局部thin发生在选择D锚点之前，更深D-thin在现有预算下不可达。当前可达的薄壳复用技术是局部T；测试应针对它，不通过擅自增加SSS深度制造D覆盖。

游戏在8分25秒会话后由助手正常关闭，全部维度保存、BUILD SUCCESSFUL/exit0，VUID=0；配置前后字节一致，无视觉/性能结论。证据在同一11-05目录r2b18-*；R2完整源/shift恒等、局部发光L/H及L/T真实夹具、记录A→B→A和R3仍开放。用户要求先解释G3/G10/G11/G6的理由与代价再确认，三项尚未采用，不把“需要分项调整”当批准。

## R2b-19（2026-11-05，D 编号待分配）：立即命中 H 与局部发光 L

补齐已有记录模型中的H立即天空/发光命中和局部发光L。H保存未施加hit MIS的endpoint incident，源response和重新求值的response各自施加其命中MIS；基础天空与analytic celestial项分成两个候选，保持原atmosphere/environment分支、delta/显式NEE门的实际语义。H仅在D锚点之后的第一次BSDF命中产生，更深端点继续归D；局部发光L只在更早D锚点尚未选定时产生，current lobe=0、事件质量1，不重复计入原L。

把原emitterShare的三路分区原样抽成共享函数，源着色与fresh L重新解码的材质/前驱PDF/showCelestial使用同一函数；unlisted、proxy、MIS off与delta规则不变。严格shipped-line证明纳入该helper，没有跳过发光数学或改旧采样行为。fresh replay额外导出原前驱celestial门，参考值依旧随选中record而不是全局D模板移动。

**数值测度要点**：原powerHeuristic含1e-20保护项，不能简单把NEE面积密度除以G后改用角度PDF；那会把保护项放到另一测度。H kind=2保留NEE面积PDF，并将96 B记录offset92的sourcePdf辅助lane按标签用于固定出射端点G，重评仍做powerHeuristic(pdf*G,areaPdf)。源当前BSDF PDF由实际求值重建，其源分母仍在jacDenom中；其它技术/子类该lane仍为源当前事件PDF。没有挤占RGB/方向/object/UV，没有加record/segment或阴影payload。G由原端点着色法线与原距离计算，遵循当前发光MIS基线；不会拿这项当连接判据的几何法线替代。

72套/363项测试、52shader构建/校验与三OFF规整化字节对照通过。20,003组实际production事件/发光helper的Vulkan compute检查，H响应最大相对误差2.542e-7，PDF误差0；emitterShare对独立double参考最大2.011e-7。6,666组面积MIS中含952组竞争面积PDF为零，仍保留原保护项；690组负对照检出“直接转角度密度”错误，unlisted/proxy/delta分区均通过。源factor检查也接入每个readback失败报警和累计，不再仅看1Hz抽样。

本片未再启动游戏，H/局部发光L、L/T定向夹具与R2b-18诊断修复的整场验证仍开放，不能据独立helper测试判R2完成。用户最新裁决改变后续G3/G10方向：拒绝TraceRay+补metadata query的正式接法，要求联合命中与共享传输内核重构，消除不必要重复计算；G6主开关默认关、时域/反向验证开启、Cap20作为初始优化基准已授权。具体边界更新到DEVELOPMENT §8.13；旧独立query当前仍在记录通路中，后续重构取代它，不将未实施的联合返回冒充完成。证据为同一11-05目录r2b19-*。

## R2b-20（2026-11-05，D 编号待分配）：共享的完整照明/纯透射内核

按用户要求，先审查“完整积分后只取透射”在编译后是否还做无用功，再重构而非新增一份透射近似。静态探针证明：旧integrateSegment仅取T仍保留8处OpTraceRayKHR代码点、33个loop和55处显式纹理采样；旧traceClouds仅取T保留4个loop、19处纹理采样。这里是编译后静态代码点，不是每条连接实际执行8条射线或某个耗时比值。

新增三个各有唯一算法体的kernel：cloud_path_kernel（低云march/高云薄片/路径合成）、volume_ambient_kernel（异质区域步进及source adapter）、volume_segment_kernel（介质profile分派）。各在模块内静态实例化为完整照明与T-only入口，公开cloudPathTransmittance/segmentPathTransmittance。T-only只保留必要密度/消光/步进/mip/终止计算，不执行太阳光程、源照明、scatter-event归一化预遍历或可见性射线；原完整模式的估计量和调用接口不变。异质T保留逐步exp的原乘积顺序，没有以exp(-sum tau)替代；封闭介质闭式和高度雾解析式仍是原式，没有为了统一而退化成march。

先尝试原生bool模板和运行时常量折叠：完整模式虽算术相同，规整化仍多两处私有OpLoad，不满足既定字节门。改为单一kernel源码的预处理静态实例化，完整模式展开的原语句/作用域保持原样；不是维护两份积分算法，也不在每个密度样本做运行时模式判断。正式源码的T-only编译后：介质OpTraceRay从8处降为0、loop33→3、纹理采样55→9；云loop4→3、纹理采样19→13。普通ternary只关闭最终RGB写回时仍保留旧19处采样，故未采用“只不返回in-scatter就会自动便宜”的假设。

73套/364项测试和52shader通过，清理后的全量测试再次通过。新的独立compiler contract将正式T-only两个入口作为compute编译并spirv-val，确认密度loop/纹理工作实际存在、TraceRay/ray query均不存在。原source契约测试改为读取实际kernel的完整照明静态体，原断言保留。三个OFF模块与R2a逐字节相同；从b4578c6源快照重新编译的普通/SER ON模块，与重构后两个ON模块也规整化逐字节相同，未用“数学相同”替代字节比较。

这一步没有加射线、GPU缓冲、WorldPush、48 B segment、96 B record或阴影payload，没有改变源随机流，也没有把T-only调用当作R3已经消费。完整源/shift的逐边可重放种子、T-only真实数值恒等、实际GPU时间及前片几何诊断残余仍待后续；本片未启动游戏。原始原型、反例、编译SPIR-V/反汇编/静态计数、R19源码快照、364项测试与五模块对照均在同一11-05证据目录r2b20/shared-edge/cloud/volume相关文件。

## R2b-21（2026-11-06，D 编号待分配）：一次命中的联合材质/几何接口

落实用户要求的G3数据流重构。`path_hit`定义独立的辐射命中结果：原`Payload surface`加32 B逻辑的FP32几何法线/UV和对象、geometry、primitive标识。closest-hit在已有顶点/UV的同一段材质求值中写几何元数据，normal来自变换后的真实三角形边；实体非均匀/镜像变换按变换后边的叉积处理。没有再发一条正式metadata query，也没有拿着色法线或半精度UV冒充几何数据。G16排除的粒子不额外计算不会使用的几何结果。

`trace_path`与`trace_path_ser`拥有联合返回的普通/SER入口；后者在ReorderThread后重建完整结果，不将返回表面或几何跨reorder保活。复用分支的入口局部绑定同时覆盖既有volumeDebug辐射调用，SPIR-V逐调用检查确认普通/SER变体内所有旧Payload的TraceRay都使用shadow SBT。Pass B不使用指南探针，因此没有新增无消费者的联合指南阶段；Pass A/指南、旧普通/SER追踪模块及公共ABI均保留原源文件。完整材质着色仍只有一套函数体，通过命中阶段的静态包装提供两种接口。

`RtPipeline.VariantShaders`显式携带完整阶段表。新辐射closest/any-hit/miss与原阴影any-hit/guide-miss分别匹配payload，shadow hit group不绑定联合closest-hit。地形与实体的16个SBT记录均按各自ray-type分组，不能将entity radiance索引8..11错当shadow。变体的miss/hit区域按自己的计数定位，共享父布局/描述符与生命周期。48 B segment、96 B reservoir、MediumStack、WorldPush与原shadow payload没有增长；32 B是新增逻辑返回信息，不是实测寄存器、栈或GPU ABI成本。

74套367项测试与55shader构建/spirv-val通过。实际编译模块的payload类型树检查覆盖所有可达stage和两个raygen后端；三份OFF raygen与R2a逐字节相同，any-hit及两个miss与R2b-20逐字节相同。closest-hit规整化字节不同，独立检查证明只涉及不可变常量声明顺序和ID：3,008条非恒定指令的完整顺序/操作数绑定、92个常量的精确位模式与多重集均相同，没有做算术重排或宽松数值比较。三个合法SPIR-V负对照分别改一个浮点常量位、加一次加载、将一次浮点加法换成乘法，均被检查拒绝。没有修改既有规整化器，也没有把closest-hit伪报为字节相同。引入独立模块和复用分支绑定，是为了让基线入口与通用追踪接口保持原样，而非在基线里增加运行时模式分支。

正式几何构造器的20,003组GPU对照覆盖非均匀/镜像/剪切变换后的边，normal最大绝对误差8.3310522e-6，UV与整数标识逐bit保留；207组退化边、197组不可用标识、195组粒子排除分区正确，14,557组改错标识/UV/法线的负对照检出。初版cross(a,a)因FMA收缩产生微小残差再被归一化，已用显式NoContraction的乘、乘、减修正；固定SDK忽略源级precise，因此用真实SPIR-V装饰作永久回归闸门。该退化输入不会作为正常AS命中出现，但接口不能凭空返回有效法线。

生产和fresh replay改为`reuseReadHitGeometry`读取联合结果。原query作为独立的1/16像素诊断oracle继续保留，唯一候选还须比较实际normal、UV、位置和完整key；missing/ambiguous/invalid仍进入旧状态分区，不影响生产记录。先前接线原型的真实会话1,569个回读帧中，cached 5,644,540条全过0.1%，codec方向位变化、PDF/weight差异与incident误差均为0；局部S/L/H记录分别2,708,240/120/239,009，T仍0。fresh k2 2,699,592/2,699,598、deep 1,471,457/1,471,464，13次均为位置偏差，scope/miss/invalid/numeric为0；max scatter/PDF/denom分别0.0979985%/0.0132431%/0.0219944%。严格累计仍RED，不能把这13条删掉或归因于共面查询歧义。游戏7分32秒后正常保存关闭、VUID=0，配置/options前后字节相同。

该原型之后收紧了模块边界，当前ON模块与该会话不逐字节相同，因此上述会话是保留的故障与事件覆盖证据，不冒充最终模块运行时验收。下一步用独立失败见证区捕获原始射线/命中距离/key/坐标位模式，区分点运算舍入、前缀分叉与不同三角形，再作定点修复并移除临时取证。T定向夹具已准备但未激活；完整transport/shift恒等、记录A→B→A、R3时域消费、实际GPU成本和用户观感均继续开放。证据与比较器/负对照在F:\MC\Shader\evidence\restir-r2-r3-2026-11-05的r2b21文件，原始失败累计也保留。

### R2b-22：同一VNDF公式的可重放方向算术（2026-11-07）

用户确认上一轮0xCFFFFFFF退出是手动结束，并要求重新运行。重跑2601个已完成回读帧保留82次位置失败和1次数值失败，不能当通过；2484组方向见证的n/t/b/v、两个线性alpha轴、输入seed逐位一致，输出seed及所选瓣也一致，全部为specular。1798组全部上下文字段相同，其余686组仅ps末位不同，不能解释方向偏差。进一步取证374组实际采样微表面法线，全部在reflect前就已不同。更早的19组几何见证已证实主命中点/距离相同、重连三角形key相同，排除了把记录点回填、放宽几何门或额外重射线作为修复。

原因是源路径与前缀重放虽然调用同一源码，GGX采样的浮点归约/收缩仍依赖编译上下文。现在把VNDF公式抽到唯一的direction_ggx_kernel.slang：math.slang原实例保持native算术，path_reuse_direction.slang实例使用显式NoContraction的乘/加/减和既有首乘法加两次FMA的点积，归一化与反射也明确其算术边界。所有复用端GGX和反射（含电介质）消费该接口，两端不再各写一套采样器。公式、线性GGX alpha、随机数消耗、射线数、96 B记录/48 B segment/阴影payload不变；ON档FP舍入有微调，禁用部分融合与新增标量运算的实际GPU成本未测。不改资格阈值或0.1%数值门。

验证分两层。22,484组无窗口GPU对照包含2484组现场与20000组随机输入，alpha为线性1e-5..1、视角覆盖1e-7..1极掠射；正式helper与原型输出逐位相同、seed一致。对双精度独立参考，最大方向绝对误差native为8.8305e-5、固定算术为5.4866e-5，均无超过1e-4。这个简化compute没有复现原native跨上下文故障，故只证明公式与数值范围，不能冒充回归red到green；真正的回归seam是原场景RT源/重放累计。74套367项测试、55shader及全部spirv-val通过，三OFF raygen与R2a规整化字节相同。旧编译测试曾要求全模块受控add/multiply总数相等，新采样器增加合法算术后该假设失效；已改为检查真实受控vector multiply到add的依赖及其NoContraction标记，保留首轮失败日志，不删除测试。源码对照仅归一新增helper名，所有原采样参数与周边语句仍受检查。

正式模块原场景累计2312个已完成回读帧：cached 2,591,605条全过原0.1%门；fresh k2为1,163,684/1,163,684、deep为27,258/27,258，scope/miss/geometry/invalid/numeric全0。最大scatter/PDF/denom误差分别0.00164413%/0.0140544%/0.0140525%；方向codec位变化及其PDF/weight误差、incident误差均0；source factors为1,258,030/1,258,030。实际局部记录S/L/H为1,080,404/2,799/67,658，T为0（SSS仍off）。这是每个已完成readback的累计，像素采样比例仍1/16，不是1 Hz抽样或全像素证明。VUID0，游戏正常保存关闭、exit0，配置/options字节不变。所有临时取证字段已移除，正式stats仍72 lanes/288 B。

证据在F:\MC\Shader\evidence\restir-r2-r3-2026-11-05：r2b22-context-retry及half保留旧失败、原始hex和对应模块；r2b22-build-summary、r22-formal-direction-summary、r2b22-direction-run-summary、累计严格checker及CSV保存正式修复验证。没有修改规整化器。T实际覆盖、完整transport/shift恒等、记录模式A到B到A及R3时域消费仍未完成；观感最终验收留给用户，性能不能由本次1600×900诊断会话推断。

R2b-22后续薄壳覆盖：独立存档restir-r2-event-fixture和临时资源包把六块smooth quartz改为单面薄壳，并补两个粗糙glowstone；临时启用既有thin模式，不改代码或SSS深度。714个已完成回读帧实际写出S/L/H/T记录994,083/96,749/111,588/38,784条；未逐材质归因，因此不声称T全部来自定向屋顶。cached 2,670,053条与fresh k2 1,140,772次/deep 409,804次全过原0.1%门，scope/miss/geometry/invalid/numeric、codec位变化/误差及incident误差全0，source factors 1,787,470/1,787,470。最大scatter/PDF/denom为0.00788675%/0.00362071%/0.00361578%，cached PDF最大0.00746446%。D-thin仍0，原因仍是既有SSS深度预算，不冒充深层D-thin覆盖。游戏正常保存关闭，VUID0；配置和options逐字节恢复，仅移除本轮安装的临时包，原存档未被夹具改写。r2b22-thin-fixture-summary、原始日志、CSV、资源包与activation快照完整归档；这补齐局部T实际覆盖，仍不等于完整transport/shift恒等或R2完成。

### R2b-23：逐边传输种子、纯透射前缀与完整源事件恒等（2026-11-07）

按用户既有G10重构授权，把recorded路径的medium/cloud随机样本分别地址化为pathSeed与逻辑边index的函数，两个域使用独立盐，NEE/复用/roulette的顺序流不参与推导。源追踪仍调用完整integrateSegment与traceClouds；只有recordPath切换到新传输种子，未记录路径仍传原seed。ON源样本的浮点/随机表现变化属于该接线的预期，公式、密度场、步进预算及OFF行为保持。新path_reuse_transport只调用共享kernel的segmentPathTransmittance/cloudPathTransmittance，不执行整套照明后丢掉RGB，不加可见性或几何射线。

fresh replay按需累计每条源前缀边的T；普通几何identity不请求它。源侧独立累计实际完整积分产生的T乘积，并随12个WRS选中reference同步局部或D锚点前缀；完整RGB直接以路径树实际selectedValue为ground truth。重放只用新命中材质/几何、record seed/lobe/direction/incident重建，不使用参考值求方向或补系数，不做RGB除法。原scatter/PDF/jac检查保留，再增加每个透射通道及完整事件RGB的0.001门，失败仍进原numeric分类。record96 B、segment48 B、阴影payload不变；stats增4个实际使用lane到76/304 B，72/73为T-prefix/full-event最大误差，74/75明确为source非unity medium/cloud边计数，不能把可能位于锚点后的source边计数当成每层T-only覆盖证明。FrameTotals回归覆盖新最大值在后续健康帧之后仍保留。

首轮实际2451个readback的透射与RGB数值已过，但deep有1次referenceSupported=false，严格累计仍RED。源码旧门只持久记walk，dielectric仅清previousValid，后续两个opaque顶点又会恢复deep候选资格；这与当前不支持interface前缀的scope冲突。现在统一footprintPrefixUnsupported，在dielectric/particle分支入口置true并保留walk累计，所有候选前门使用它。该资格限制发生在WRS前，保留此前合法事件与完整自身L；不在选中后删除坏记录或放宽scope。首轮失败日志、模块与CSV独立保留为r2b23-first证据。未获取该次失败样本完整材质序列，因此具体界面种类是结合代码可达性的解释，不能伪称逐顶点现场见证。

修后正式模块：74套367测试、55shader及全部spirv-val通过，三OFF与R2a规整化字节一致。一个旧数值测试依赖注释文字截取函数，因注释更新失效，已按函数结束边界截取并重跑；另新增source分支sticky barrier的契约检查。没有改规整化器或数值阈值。

| 场景 | 已完成回读帧 | fresh k2 / deep（全部通过） | 最大T-prefix误差 | 最大完整RGB误差 |
| --- | ---: | ---: | ---: | ---: |
| 原场景，SSS off | 2100 | 1,045,018 / 24,638 | 0.0000398379% | 0.00166166% |
| 云层内向下看，低密度云 | 3161 | 127,810 / 805 | 0.0000346639% | 0.00261758% |
| 原场景，噪声雾启用 | 1143 | 553,274 / 13,082 | 0.0000788985% | 0.000964146% |
| thin独立夹具 | 1222 | 1,953,112 / 702,445 | 0.0000260019% | 0.0123134% |

合计7626个已完成readback、8,635,890条lobe检查、4,420,184次fresh检查；scope/miss/geometry/invalid/numeric、codec位变化/误差及incident误差全0，VUID全0。thin实际写出S/L/H/T为1,702,528/166,258/190,574/66,256条，新传输逻辑下重新验证了T完整事件；D-thin仍不扩大既有SSS深度。每帧仅1/16像素被诊断，不是全像素或1 Hz抽样证明。

云场景复制独立存档，所有player数据改为feet y224、yaw-85/pitch80、creative flying；保存后的实际位置/姿态再次核对一致。临时coverage1/density0.05/extinction0.003/thickness128/weatherfalse，普通fog仍开，这是实际云层前缀场景的回归，不声称覆盖所有云预算/空间位置。噪声雾只临时启用既有开关，原contrast8在forcing中clamp4；未修改默认参数或校准。所有游戏已正常保存关闭，config/options逐字节恢复，thin临时资源包移除，原存档未被夹具改写。

另以真实segmentPathTransmittance做20,003组无窗口GPU对照double Beer law（tau 0..80，含零吸收、零距离、混合RGB），两个seed输出逐位相同，最大相对误差7.3811865e-6。完整source函数因静态TraceRay capability不能用于compute，故这个结果只验证均质passive/glass的T-only，不冒充完整source或水照明试验。

F:\MC\Shader\evidence\restir-r2-r3-2026-11-05的r2b23-scenes-summary聚合上述正式结果，各场景日志/严格checker/CSV、配置激活与恢复快照、原场景ON模块hash及closed probe输入输出保留。实际GPU代价未按同会话比值法测量，不从诊断帧时间推断性能。当前完成的是已有源前缀与选中事件的恒等；封闭水初始介质专测、新连接/shift、record-only A到B到A及R3时域/pairwise RGB own-split继续开放，R2/R3不能据此宣布完成。

### R2b-24：记录模式的同会话原始HDR A→B→A（2026-11-07）

落实记录功能的能量检查，增加独立`RtRawHdrProbe`，仅通过直接客户端JVM属性`fluorite.rt.rawHdrProbe`启用。Fabric提供`-PbenchRawHdrProbe=<绝对目录>`转发vmArg；未启用时没有GPU分配/命令或pipeline切换。读取位置在pass B结束的既有barrier和stats copy之后、DLSS-RR之前，图像是完整camera-prefix+leaf的RGBA16F noisy composite，不使用post-RR曝光统计。每pushSlot一个按需host-visible buffer，copy后恢复GENERAL布局、发布host可见性，在既有graphics-use等待之后invalidate并以CPU double计算线性RGB moments；不增加fence、射线或shader。NaN/Inf像素独立计错并使帧均值NaN，不能静默丢弃。每8像素记录一个原始RGB half像素，共3600个固定像素，方差按各像素随时间计算，不能以空间方差代替噪声。

手动arm之前和实验结束之后均保留配置master值；实验内按成功recorded draw序号驱动真实reservoir分配及pass B选择，CSV保存copy当时的实际模式而非回读时开关。每阶段96 draw预热，每4 draw采一次，共96样本；OFF/ON/OFF三阶段288样本。每个样本记录尺寸、camera、矩阵pose hash、server tick、DLSS-RR、SPP和bounce预算。结束有complete.json，既有文件CREATE_NEW防覆盖。新增5个测试覆盖half subnormal/有符号moments、非有限值、固定像素bit、阶段边界及插入位置/等待关系；总75套372项测试、55shader及spirv-val通过。三个OFF与R2a规整化字节相同，两个ON与R23原始SPIR-V逐字节相同。

独立复制存档建立封闭白墙/蓝墙房间和自制平坦材质包，原存档不改。临时关DLSS-RR、volumetrics、重复identity诊断及bobView；Russian roulette、SPP1、maxBounces3和既有M24 DI不改。客户端命令给玩家隐身、冻结server tick，并留下未发送的命令栏防止镜头意外移动，再写arm文件。实际288个样本均为640×360、tick744765、camera(40.5,−61.38000011444092,23.5)、同一pose hash。没有无效像素。

事先在activation manifest固定判据：每8捕获样本一个batch（每阶段12个batch），比较B与(A1+A2)/2，差值须在2个合成标准误内；另检验A1/A2平稳性。原始RGB均值如下：

| 阶段 | R | G | B |
| --- | ---: | ---: | ---: |
| A1 OFF | 0.0127074495753 | 0.00865585153148 | 0.00508829484488 |
| B ON | 0.0128620388535 | 0.00874794388685 | 0.00511839995925 |
| A2 OFF | 0.0129710948023 | 0.00881692171566 | 0.00514986117735 |

B相对两A平均的RGB差为+0.1773%/+0.1323%/−0.01321%，对应z为0.02034/0.01519/0.001525；A1/A2漂移z为0.1596/0.1431/0.09305，均通过预设门。固定像素亮度时间方差比为0.988220743。**这个实验未检出均值变化，不是严格无偏证明，也不证明降低方差**：只有96样本/阶段，均值2SE约为基线的17.44%/17.42%/17.37%，小偏差仍可能藏在置信区间内。没有消费history，且旧DI仍启用，不能冒充纯PT对照、R3收益或性能基准。

此前aba-1/aba-2未arm、没有有效样本；aba-2因额度中断由用户手动关闭，记录保留。发现MC options里的overrideWidth/overrideHeight会覆盖启动width/height，首两次实际1600×900，不能据CLI声称640×360；本轮明确设置并从分配日志和样本元数据核对实际640×360。第三次独立目录保留所有288份grid、CSV、arm元数据与analysis，不覆盖旧失败/中断。游戏正常保存退出、BUILD SUCCESSFUL、VUID0；config/options恢复原始字节，临时资源包移除。原始frame stats、构建日志、测试结果汇总、shader hash、配置和分析器位于F:\MC\Shader\evidence\restir-r2-r3-2026-11-05的r24/r2b24文件。

本片只补记录模式的初步A→B→A数值检查及可复用取证工具。封闭水初始介质专测、新连接shift恒等、R3的history/k2前后向移位/pairwise MIS/RGB own-split与用户观感仍待完成，不能宣告R2/R3完成。

### R2b-25：实际k2连接的回归闸门、端点身份缺口与初始水介质

新增`path_reuse_connection.slang`，首先取实际primary，再从tracer的biased receiver.p向记录的reconPos构造新连接。一条联合nearest TraceRay同时返回可见性命中、真实材质和几何；没有补metadata query，不把纯T-only变成完整照明。两端selected-lobe、ray cone、原双footprint、逐边T及Eq.2分母/Jacobian均独立重算，源参考只参与诊断。现阶段只在1/16像素identity诊断中调用，与旧source-seed重放检查并存，仍返回完整自身L，不读history。新增8个正式统计lane（76attempt/77pass/78scope/79hit/80footprint/81numeric/82RGBmax/83Jacmax），合计84 lanes/336 B，保留所有完成readback的失败和最大值，不在1 Hz日志前丢掉异常。

正式模块76套375项测试、55shader与spirv-val、三OFF与R2a字节对照通过。首次全量测试有4处旧76-lane尺寸断言未更新，及一次替换脚本未匹配无空格断言，失败日志保留；只改当前尺寸期望，原数值门和检查不删。新增测试验证PSS Jacobian的双PDF抵消/互逆关系、实际shared hit/T-only调用约束和独立0.1%门，原FrameTotals回归覆盖新增最大值及失败计数跨健康帧保存。

原场景实际3911个readback：lobe710,702条、fresh k2 320,848/deep7,332均全部过旧源门，方向/incident codec0、VUID0；新连接仅320,771/320,848通过，hit1/numeric76，最大RGB100%、Jac45.2115%。不能由旧源重放健康推断新连接健康，严格验收保持RED，不消费这些结果进入图像。固定显示640×360、保留原DLSS配置，实际trace213×120，不据此报告正式性能。

临时[DEBUG-r25]探针为每帧第一个异常保存双端原始bit packet：frame/record bits、状态、RGB/Jac误差、真实source RGB、reconPos；两端incoming direction、hit position、UV、F0、normal、albedo、roughness、前驱/事件PDF、分母、alphaX/Y及完整triangle key。旧source检查直接输出自己的真实新命中字段，不为取证多追射线，不用参考值回填连接。临时stats148 lanes/592 B，64word包随既有readback回收；另一实际会话5653个readback，旧fresh k2 464,790/deep10,670均全过，连接464,677/464,790，hit1/numeric112，最大RGB100%、Jac89.7951%。因同帧最多一包，共保留112个见证，不冒充覆盖所有113个失败。

111个numeric见证中101个实际triangle key不同（98个source terrain bucket1、3个bucket0），尽管部分world position逐bit相同、几何法线检查通过。不能只由bucket推断具体方块或所有共面原因。另10个key相同：4个实际材质字段变化，包含frame2985的UV相差一ULP、normal/roughness改变、RGB差30.72998%；6个已记录材质字段相同，仍有方向重建小残差（RGB最大约0.1609%或Jac约0.13735%），不由分类删除这些失败。已证明位置/法线不足以标识同一端点；完整LOD/调制与全部残差因果仍需分别验证。raw hex、decoded JSON、全部失败/编译模块和parser留档，不能把诊断版本当正式发布ABI。

见证关闭后，以逐字节比对恢复正式3文件、移除唯一临时debug include。重新构建的375 tests/55shader通过，两ON原始SPIR-V与取证前正式候选逐字节相同（cde91b.../ff49ac...），不是用源代码相似替代运行时覆盖证明。

独立存档restir-r2-water-room复制R24房间，封闭内部填1331个source-water方块，继续自制matte包，只临时开既有water-medium-trace、窗口640×360，其它renderer配置原样。实际5810个readback的lobe8,455,459条全过；fresh k2 11,316/deep22及新k2连接11,316次全过，scope/hit/footprint/numeric等失败0、codec/incident误差0，最大连接RGB0.00104366%、Jac0.00652075%，最大T-prefix0.000038239%、完整事件0.000133627%。389条中心probe中385条完整确认pathFlags current-water bit9、enclosed profile1及opaque first hit，首段T约(0.5628,0.6260,0.6533)。前10秒之后仍新增10,178个成功连接，不是只拿加载时数据凑覆盖。此测试验证封闭水初始介质和均匀材质场景，不测试穿越水界面/折射，也不能替复杂表面的端点错误放行。

三次游戏均由助手正常保存退出，BUILD SUCCESSFUL、VUID0，原config/options逐字节恢复，水房临时包移除。观感仍留用户，GPU成本未按1080p同会话比值法测量。所有证据位于F:\MC\Shader\evidence\restir-r2-r3-2026-11-05的r25/r2b25文件：first-red、witness modules/decoded/restore、water-cumulative/water-summary及各日志/CSV/activation快照。

128 B完整端点方案已独立compile/spirv-val验证std430 stride和offset：保留原96 B，加完整object/geometry/primitive key、float2 barycentrics及flags/padding。预计联合几何返回32→40 B逻辑（原shadow不增长），不加连接射线或独立query；1080p实际追踪双缓冲多126.5625 MiB（33.33%），实际GPU/寄存器/栈成本待测。拟不同key判shift失败保留自身，同key在共享着色函数中用原bary重建真实材质。它不自动消除所有源self-connection失败率或同材质方向残差，跨帧generation仍欠。**已依CLAUDE铁律1请用户裁决，尚未批准、未改产品ABI**；替代选择是96 B硬预算继续研究布局，不承诺无成本解决。详见`r25-endpoint-decision.md`及layout-prototype/summary。R2整体和R3 history/双向shift/pairwise RGB own-split仍未完成。
