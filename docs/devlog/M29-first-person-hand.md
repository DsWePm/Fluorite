# M29：第一人称手部 RT 光照

进行中。任务书与所有权见 `docs/agents/parallel-workstreams-2026-09-14.md`（B 线，函数级预写）；本日志随实现追加。

## D234：采集、自绘 pass 与解析光照整链落地（2026-09-14）

### 路线（分派层记档的方案，用户 2026-09-14 指令目标：手部接受正确光照 + PBR）

**自绘 display-res 光栅 pass + 解析 RT 光照**（受光雨丝同构），不做手部全光追：手部走独立 viewmodel 投影，进主 pass 意味着第二套 primary/guides/RR 链；1spp 近距高光噪声过不了 RR；解析光照（真阴影线 + 网格天空场 + 光源 NEE）噪声为零。物理缺口（记档）：**GI 缺失**——手只收直接光，天空场的开阔度是间接的替身；**无视雨湿**；**非大气 Provider 无天体项**（雨丝同款选择）。

### 关键架构事实（实现期钉死）

1. **采集 = collector 换入，抑制是副产品**：`renderItemInHand` 内部经 `ItemInHandRenderer.submitHandsWithItems(poseStack, SubmitNodeCollector, …)` 立即模式提交（MC 26.2 源码核验），collector 是参数——mixin 换成 `RtHandCapture` 的 collector（`Platform.newEntityCollector()` 新实例，`begin(capture,false)`、永不 configureDynamicLights，避免与身体链的 D18 手持光双计），vanilla storage 收不到节点 = vanilla 手自然消失；开关关 = 原参数直通 = 发布路径逐位。材质/纹理/染色解析全部白拿（`submitModel` 皮肤整图路径、`submitItem` atlas 路径、`submitCustomGeometry` 地图）。
2. **位姿 = vanilla 自己的**：手部 quad 在视图空间（poseStack 已含 modelView⁻¹+bob）；投影在 `getBuffer` ordinal=1（hud3d）处捕获——**自绘 pass 与 vanilla 用同一个矩阵**，对齐是构造性的不是调参的。视图→世界 = viewRotation 的转置（纯旋转），平移由 frag 从 `worldPush.camOffset` 取（rebased 空间，与世界光照消费者同一坐标系——单一来源）。
3. **frag 直接复用 RT 的 push 形状**（`WorldPushConstants` 128B）：`world_core` 的共享 `worldPush` 全局、`volume_visibility`（16/30 号绑定）、`atmosphere_lut`（10 号）、`cloud_density`、`light_sampling`、`bsdf` 全部原样 import，绑定号不重排（slang 死代码消除已验证：SPIR-V 只引用 10/16/61/62 + set1 的 0-3）。vert 用 binding 63 的 SSBO 拿矩阵（`HandFrameData`，两个 mat4），无 push 重叠。
4. **bindless 镜像**：set1 同号同容量（0=entityAlbedo/1-3=材质页），由 `RtEntityTextures.knownSlots/slotView/blockAtlasView` + `RtBlockMaterials.pageCount/page*View` 新访问器按水位镜像（不能消费 `pending`——那会偷走 world pipeline 的槽）。
5. **寿命**：pass 在 `finishGraphicsUse` 之后一个 submission 读本帧资源——与 gpuTimers 环注明的 PUSH_RING 槽空间论证同一族；自己的 4 槽环（顶点/帧数据/描述符集）各自 await+mark，照 pushRing 纪律。
6. **深度用自绘图**（D32 + CLEAR 0.0=远），与 vanilla overlay 的深度格式/布局解耦；GREATER 与 vanilla 反 Z 约定一致，手臂与手持物互相遮挡成立。

### 光照（frag 四项）

- **天体**：`sampleSquare` + `lightRadiance·sampleTransmittanceToSpace`（atmosphere_lut 的表）+ `cloudSunTransmittance`（D176，云_density 参数版）+ 一条抖动阴影线（mask 0x01=secondary-no-self，雨丝同款——第一人称身体不投影，vanilla 语义）。
- **静态发光体**：`sampleVolumeEmitter` 一候选 + 阴影线，BRDF 换掉雨丝的各向同性 1/4π。
- **动态光（手持火把在这）**：直读 `worldPush.dynamicLightAddr`，扫 32 条球光、按无遮挡估计排 top-3 打影线、其余按可见计入（记档偏差：截断+可见近似）。M18 数据在静态 grid 里**不存在**（M24 S3 只把它接进 DI 候选）——这是实现期修正的第一版任务书漏掉的来源。
- **天空场**：`volumeSkySurfaceIrradiance(p,n)`（volume_visibility 新增、只增）——标量模式 `L̄·openness·(1+n_y)/2`；方向模式 `Σ bins_k·skySectorRadiance[k]·A_k(n)/π`，A_k 用 32 点固定余弦求积（8 方位×4 等分 CDF 仰角层），分区使 `ΣA_k` 逐点复现标量锚点 `π(1+n_y)/2`——**两模式在天顶锚点严格一致**，且定值 tap 不引入新噪声（M28 决策 3 的首个表面消费者）。
- **材质**：LabPBR 解码为 rchit `evaluateMaterial` 的**第二拼写**（并行期 rchit 归 M30，提取共享体延后到 S4 合并窗）——`RtHandLitContractTest` 把常量（AUTHORED 位、发射强度移位、glint 五常量）与 rchit 源文本对钉；tangent 由采集端 CPU 按 `uvTangent` 同式算好随顶点传（光栅 frag 无邻接顶点可重推 UV 梯度）。

### 已注册仪表与开关

- `composite.hand-rt-lighting`（默认关；关 = vanilla 手逐位——采集根本不发生）。
- `handQuads`/`handDraws` 进 `FRAME_COUNTER_NAMES`（D218 规则；`RtFrameStatsNameRegistryTest` 先红后绿验证过一次——首build即被它抓住，机制有效）。
- `RtHandLitContractTest` 六钉：collector 换入/旋转捕获/ordinal=1 投影/beginFrame 复位/计数注册/默认关。

### 验证状态

- slangc + spirv-val 全绿（vert/frag）；`gradlew build` 全绿（fabric + neoforge，238 tests）。
- **待用户游戏内验收**（铁律 7 + 观察点协议）：姿态逐像素对齐（不齐不许调光照）、太阳/发光体/天空场同步、PBR 高光与法线、回归（F5/屏幕效果/望远镜/双端）、`handQuads`/`handDraws` 随开关翻转。清单见工作流文档 §5 B-3。

### 记档偏差与后续（§8 已列）

GI 缺口（路径 reservoir 候选/辐照 probe/接受现状，验收后裁决）；非大气 Provider 天体项；雨湿手；POM 自阴影；per-material 视差深度类ABI 窗口不适用此处。页解码第二拼写的 S4 合并窗提取。
