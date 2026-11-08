# R4 空间复用预检与取数布局裁决（2026-11-08）

R3基线为d35631b，R4尚未接入产品。用户要求继续，允许自行功能/数值测试，观感集中留给用户。此次未启动Minecraft，未改动产品shader/ABI/运行配置。

## 已核原文与估计量

Junkins 2026原页7–8的Eq.14–15与Algorithm1：h=exp(−length(p−q)/s)·max(dot(n,m),0)^8，s=d·sqrt(Ω/π)，Ω=0.05（是立体角，不是角度θ）。M=1用A-Chao；M>1用A-ES，无放回且输入唯一时输出唯一。评分只依赖主命中属性，不能以record是否抽中正贡献、W或radiance筛邻居。旧survey的高斯和σ=2θd转述不能作实现依据。

本项目D231/R0已批读取上一帧候选、逐域Cap，故R4拟统一合并fresh自身树、可选时域域及M个空间域。每个valid旧域与同一个fresh canonical做一对MIS，RGB对各pair求和后除以有效域数；tree外项仅加一次。有效空域和forward失败候选仍保留该域及reverse own support。没有旧域则严格返回原ownL。confidence=1+Σmin(Cap,c_i)，M≤8并含时域时上界181；不能把R3已经合成的整幅RGB再乘某个样本的MIS权重。

GPU独立原型60,000组含2,197种三棵彩色WRS树的精确枚举，RGB积分[2.9999998619,4.5999999736,6.1000002212]，double真值[3,4.6,6.1]；随机0–9域、非单位Jac、空候选/失败映射与Cap检查最大归一化误差3.88e−7。Chao概率与A-ES有序对频率均在预定6σ内，A-ES两个输出不重复。评分对double最大绝对差1.77e−6。此为独立计算原型，不冒充实际空间RT验收。

## 当前数据与候选路线

128 B记录保留原始primary ray但不保存primary t/normal；现有gNormal/gDepth只有当帧一份，会被Pass A覆盖。K个候选全部追真实命中成本很高，并非免费取数。选中M域的真实primary材质可沿用R3的一次joint TraceRay，供前后向连接共享，不重复metadata query。

| 方案 | 持久显存 | 代理精度与工程边界 |
| --- | --- | --- |
| A：独立8 B PrimaryGuide（原首选，新增未获批） | depth32+oct16×2 normal；仅空间开启分配，1080p实际追踪双缓冲+33,177,600 B=31.640625 MiB，现有128 B记录不变，增加6.25%相对路径存储 | 实测60,000组GPU stride8、深度逐bit；法线最大0.003645°/RMS0.001435°。类型/所有权清晰，不侵入端点key编码。K评分不加射线，M选中后仍真实取材质。 |
| B：在128 B空闲位编码代理 | 不新增逐像素持久缓存 | 将geometryIndex现有0–3范围的高30位、confPdf高16位与flags空闲5位编码t32+n19。60,000组深度/key/已有confidence与flags逐bit不变，代理法线最大0.353878°/RMS0.146277°。需统一编码/解码并改变geometryIndex字段含义，后续几何桶扩展受约束。K评分不加射线，实际材质/BSDF/方向不压缩。 |

代理仅选择邻居，不进入BSDF、几何Jac或真实端点验证；正常代理量化改变评分/相关性，不直接改变目标积分。两者实际GPU时间/寄存器成本尚未测量，不能从compute原型耗时推断生产收益。另可重构既有gNormal/gDepth为历史ping-pong（多一套12 B/pixel约23.73 MiB@1080p），但需调整RR/lens/FG等所有guide消费者及反深度重建；用户已要求优先核查此复用路线；改动范围大不能直接成为另加缓存的理由。所有产品方案仍未实施。

拟采用论文基线K32、R30 tracing pixels、Ω0.05、β8，M继续已有0–8旋钮且默认0，R6再校准半径等参数。是否开启早停、R2 disk低差异序列和重复tap去重还须在实现/测试中明确，不能把M次有放回抽样冒充论文Algorithm1。

## 待用户裁决

先核实既有guide的重构复用，再比较取数布局和初始基准参数；此前独立8 B首选建议不视为结论。用户此次要求记录新增与重复操作，不是批准任何新增预算。依据CLAUDE.md铁律1“任何方向性决策必须请示用户”；仅提交可复核原型与预算，不把继续开发视为新增显存或位布局的批准。主材质继续R3真实joint TraceRay为已授权路径，不重复请示。

证据根目录：F:/MC/Shader/evidence/restir-r4-2026-11-08。prototype-summary.json、guide8-summary.json、check-r4-prototypes.py、check-guide8.py及相应Slang/input/output/SPIR-V；原文页截图与PyMuPDF抽取保留为junkins-page-7/8。产品空间复用、0邻居等于R3的shader字节门、真实RT与GPU比值测试均未执行。


## 新增操作登记

本预检涉及的缓存、历史副本、重新追踪、位编码/解码和重复候选问题，已登记到[统一复用审查台账](../REUSE_REVIEW_LEDGER.md) OP-009至OP-013；后续每次取数路线变化均同步更新，不因原型成功就跳过已有数据复用调查。
