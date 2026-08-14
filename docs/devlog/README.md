# Fluorite 开发日志索引

开发日志保存已经结束的实现过程、实验、失败路线、决策依据和验收记录。它们是历史证据，不是当前待办清单；当前架构、规则、风险和下一步只以 [`docs/DEVELOPMENT.md`](../DEVELOPMENT.md) 为准。

阅读时先从主文档找到对应成果或风险，再进入这里追溯“为什么变成现在这样”。代码注释中的 `M*`、`D*`、`F*`、`R*` 编号也通过下表定位。

## 按里程碑查找

| 日志 | 内容 | 主要编号 |
| --- | --- | --- |
| [M00–M08：平台、渲染地基、BSDF 与 BSSRDF](M00-M08-foundations.md) | Fabric/NeoForge 迁移、wavefront 地基、统一介质起点、Disney BSDF、随机游走 SSS | M0–M8、F1–F13、R1–R16 |
| [M09–M10：水体介质与 LUT 大气](M09-M10-water-atmosphere.md) | 水体 σa/σs、七轮诊断、焦散、大气 LUT | M9–M10、R17、R20 |
| [M11：体积云](M11-clouds.md) | 云密度、光照、双层、天气、二次光线、4π 能量修复 | D33–D38、D43、D61、F18–F21、R18–R20 |
| [M12–M12.5：水体仿真与真形变](M12-water-simulation.md) | 交互涟漪、障碍、BLAS refit、波谱、FFT 裁决 | D39–D58、F22–F28、R21–R22、F24 |
| [M13：体积可见性、结构雾与天气](M13-fog-weather.md) | 可见性网格、随机阴影线、3D 雾、统一风向与天气 forcing、焦散天气衰减 | D59–D73、M13.2/.3 |
| [M14：维度 Provider、地狱介质与末地环境](M14-dimension-presets.md) | 版本化 preset、地狱本地光/均匀雾、末地 HDR/Kerr 技术 Provider 与后续动态 HDRI 路线 | D74–D97 |
| [M15–M17：统一介质与体积光照](M15-M17-medium-lighting.md) | 介质接口、Radiance 源、水下前缀、Slang 错编规避、散射顶点与 NEE | D1–D27、D29–D31、R24 |
| [M18：动态光源数据层](M18-dynamic-light-data.md) | 手持/火焰/发光实体球灯、粒子 cell、稳定 source key、未绑定上传 | D98–D104、M20.4 |
| [M19–M20：实体 overlay 与粒子](M19-M20-entities-particles.md) | 受伤 overlay、实体火焰、glint、粒子发光/透明/阴影 | D6、D28、D32、F16 |
| [M21：雨天表面系统](M21-rain.md) | 世界锚定降雨暴露、湿润历史、水膜/水坑/涟漪、RT 水花和受光雨丝 | D105–D129 |
| [M22：后处理、输出变换与镜头效果](M22-post-processing.md) | ACES 2、分区调色、景深/动态模糊、颗粒、Bloom/Flare 与 EV 域自动曝光 | D130–D155 |
| [测量、诊断与失败经验](lessons-and-measurements.md) | 基准纪律、GPU 日志、observer effect、设备故障取证、F/R 索引 | F1–F28、R1–R24 |

## 维护规则

1. 进行中的事项留在主文档和 GitHub Issues，不提前写成“完成日志”。
2. 一个里程碑完成后，把过程、数据、被否决方案和验收结果写入对应日志；主文档只保留一句摘要和链接。
3. 影响当前代码的长期不变量必须同时写在代码附近和主文档；日志只解释来源。
4. 方向性决策先向用户提供候选方案、物理准确度差距与性能代价。获批后实施；结案时再把完整决策记录归档到这里。
5. 旧的 `C:\Users\Denni\.claude\plans\` 文件只作考古，不再更新，也不再是项目事实来源。
