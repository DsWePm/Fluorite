# M22：后处理、输出变换与镜头效果

M22 于 2026-08-14 完成功能视觉验收。它在 DLSS-RR 与 UI 之间建立了明确的 scene-linear HDR 后处理链，加入 ACES 2 输出、分区调色、景深、动态模糊、镜头几何、胶片颗粒、Bloom、Lens Flare 和 EV 域自动曝光。当前长期架构、风险和待办仍以 [`docs/DEVELOPMENT.md`](../DEVELOPMENT.md) 为准；本文保留 D130–D155 的实现依据、边界和验收记录。

## 最终管线与资源边界

稳定顺序是：

`RR/雨丝 → 自动曝光测光 → 动态模糊 → 景深 → Bloom/Lens Flare → 畸变/色散/暗角 → 曝光倍率/调色/Output Transform → UI`

- 自动曝光只测量未经创意调色和镜头构图修改的 scene-linear HDR 画面；UI 不进入景深、动态模糊、Bloom、Flare、色散或暗角。
- 动态模糊、景深和光学高亮复用一张 display-resolution `RGBA16F` ping-pong scratch。固定 descriptor set 分别表示两个读写方向，禁止在已经录制的 command buffer 中间重写 descriptor。
- 景深按需增加全分辨率 signed CoC、16×16 tile 极值/扩张和半分辨率 near/far layer；1600×900 时约 8.28 MiB。Bloom/Flare 的十张五级图约 7.32 MiB，Flare 五边形 bokeh 图另约 0.69 MiB。
- 所有镜头效果默认关闭；不需要的 pass 不 dispatch，按需资源在效果全部关闭后释放。诊断视图旁路镜头效果。

## D130–D138：ACES 2、显示输出与公共调色

- AgX 保留为兼容默认项；新增 `ACES 2 LUT（快速）` 与 `ACES 2 精确`。两者实现相同的 ACES 2 Output Transform，不是两个不同 look，也不是 fitted curve。
- 实现固定到 ACES `v2.0.0+2025.04.04`，以 OpenColorIO 2.5.2 提取的解析 GPU 形式为基准；官方版本、commit、许可证与第三方声明记录在源码和 `THIRD_PARTY_NOTICES.md`。
- 快速模式在启动时烘焙 SDR 100 nit 与 HDR 500/1000/2000/4000 nit 五张 `65³ RGBA16F` LUT，合计约 10.48 MiB；输入采用每通道 `log2` EV `[-16,+16]` shaper，第 0 texel 保持精确黑色。精确模式使用单独 pipeline，避免其解析表和寄存器压力污染日常路径。
- 输出信号明确区分 `SDR — Rec.709/sRGB` 与 `HDR10 — Rec.2020/PQ`；HDR 仍要求交换链支持并在不可用时回退 SDR。显示信号开关不冒充色调映射模型。
- `tools/verify_aces2_output.py`、`tools/verify_aces2_lut.py` 和 JUnit 固定官方参数、逐像素参考值、源码哈希与 LUT 误差。65³ LUT 对极端饱和色域压缩边界存在已知插值误差；升级 129³、改四面体/多采样或接受现状属于新的质量、显存与性能裁决。

## D139–D147：相机与艺术调色

- 动态模糊读取 render-pixel 单位的 `gMotion`，换算到显示分辨率后按 0–360° 快门角作 8/16 tap 居中 gather，并以重建深度拒绝跨轮廓样本。它无法恢复遮挡历史，反射仍借用主表面运动。
- 景深使用 35 mm 虚拟传感器与薄透镜 CoC。D145A 将早期廉价 16-tap 单层 gather 替换为 signed CoC、tile 分类、半分辨率 near/far 分层和 full-resolution 合成；支持中心平滑自动对焦、手动焦距及圆形或 5–9 叶程序化光圈。它仍是屏幕空间近似，不能恢复画外或被遮挡辐亮度。
- 镜头畸变采用带自动裁切的径向 `k1+k2` 艺术曲线；色散是 RGB 横向色差近似；暗角是屏幕椭圆构图控制。它们不冒充具体镜头标定、光谱折射或真实 `cos⁴θ` 衰减。
- 艺术调色子菜单按全局、暗部、中间调、亮部和明度范围组织。白平衡使用 Bradford 色适应；分区曝光、RGB 平衡、饱和度与对比度烘入一张 dirty-rebaked `65³ RGBA16F` scene-linear LUT，稳定帧只读取一次三线性 3D LUT。

## D148–D155：颗粒、Bloom、Flare、曝光与菜单

- 胶片颗粒使用启动时烘焙的 64² `R16F` 蓝噪声式 rank 图，在输出变换之前执行零均值对数乘法。RGB 分离为 0 时精确保留共享单色路径；非零时只增加 R/B 两次纹理读取，没有额外 RGB 图、扫描线或 CRT 荫罩。
- Bloom 与 Lens Flare 有独立开关和阈值。阈值按 `sceneLinear × 当前曝光` 判断，保存与合成仍保持 scene-linear，避免双重曝光或反向影响测光。
- Lens Flare 先做局部峰值隔离，再在四分之一分辨率生成程序化五边形 bokeh，并用固定轴向鬼影、光环和横向 streak 构图。它是 image-based 屏幕空间美术近似，不包含镜片曲率、玻璃 IOR、镀膜 Fresnel、色散或镜片间多次反射，因此不符合严格物理镜头模型。
- D155A 修复了远距 Bloom 九宫格：旧最粗 `1/32` seed 把九点核间距四舍五入到 2–3 个粗像素，会把单像素远距发光物复制成间隔 64–96 显示像素的九个波瓣。最终固定为连续、能量归一的 3×3 tent；Bloom 半径改由跨层传播权重表达，采样数与显存不增加。
- 自动曝光保留 256-bin `log2` 直方图和 50%–95% 测光区间，但把目标与历史统一为 EV：亮适应默认 0.25 s，暗适应默认 1.5 s，首帧直接对齐。玩家设置拆为自动补偿与手动绝对 EV；旧共享值只在新键首次出现时迁移到自动补偿。
- 后处理根页面只保留曝光、输出变换，以及同一行的“艺术调色”和“镜头效果”入口。镜头页面按颗粒、Bloom 筛选、Bloom、Lens Flare、景深、动态模糊、镜头几何和构图分类。Bloom 总开关明确标记为“启用辉光（Bloom）”。

## 失败路线与长期警示

- ACES 精确模式逐像素执行完整解析变换，视觉基准正确但日常成本过高，因此保留为对照，默认使用 AgX，日常 ACES 使用 LUT。
- 早期景深单层 gather 虽便宜，但前后景互相泄漏且散景廉价；最终分层链增加了按需显存，换取可接受的遮挡边界。
- Bloom 最粗层不能通过稀疏 tap 间距表达“半径”；远距亚像素光源会把离散核直接显形。半径应通过连续滤波权重、层级组合或经过批准的高质量 down/up filter 表达。
- `gDepth` 是 Vulkan reversed-Z 硬件深度，不是线性深度；`gMotion` 是 render-resolution 像素位移。后续显示分辨率 pass 必须分别重建距离和换算尺度。
- Minecraft 纹理与光源不是光谱测量数据。ACES 只承诺符合固定版本输出变换；镜头色散、Bloom、Flare、颗粒和暗角都是相机/美术效果，不改变路径追踪能量。

## 验收与验证

功能验收覆盖 SDR/HDR 输出选项、ACES LUT/精确对照、艺术调色、景深、动态模糊、畸变、色散、暗角、胶片颗粒、Bloom、五边形 Lens Flare、自动曝光适应及菜单结构。D155A 的远距九宫格修复与 Bloom 独立关闭开关于 2026-08-14 最终验收通过。

自动验证包括全套 Fabric JUnit、NeoForge/Fabric Java 编译、全部 shader 编译、中英文 JSON 解析、ACES 固定版本/参考像素/LUT 误差测试及 `git diff --check`。正式 GPU 中位数仍应在 ReSTIR 前性能 review 中按固定机位测量，不能由静态 tap 数推断。
