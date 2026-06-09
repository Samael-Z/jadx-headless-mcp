## Context

`search_in_code` 是 `mcp-re-toolset` 的全文代码搜索工具,底层依赖 `code-search-index`(FTS5 trigram + ripgrep 兜底)。自 v1.0.0 起它就**不是全量**:`IndexBuilder` phase-3 流式反编译每个类喂 FTS 时用 `ClassNode.unload()` 释放,但该方法**不清** `RootNode` 上的全局累积(`ConstValues` 等),单进程单轮反编译到几万类即撞 `lowHeap` 守护停下 —— 抖音实测覆盖 47114/319263 类(14%),`coverage_complete=false`。

`code-search-index` spec 原本就要求"流式构建全量、堆 <20GB 不爆";v1.0.0 因释放不彻底未达成,降级为"部分覆盖 + 多轮重载续建"。

但逆向分析的真实痛点(使用反馈)并非"搜不全":
- **噪点**:`android`/`androidx`/`java`/`kotlin`/`com.google` 等标准库类命中淹没 app 自身命中,干扰模型分析。
- **重复**:同一类多处命中时反复出现(尤以 ripgrep 兜底每行一条为甚)。
- **耗时**:全量反编译昂贵(抖音 47114 类反编译 23.1min/33.9 类每秒,全量 319263 类外推 ~2.7h)。

## Goals / Non-Goals

**Goals:**
- 把 `search_in_code` 重定位为**分析价值导向**:默认只碰有逆向价值的代码、把结果调干净。
- 层一(搜索结果):按类聚合、过滤标准库、相关性排序 —— 不动索引即可消除噪点/重复。
- 层二(索引范围):按价值分层选择性反编译,跳标准库,显著降低一般 app 的索引耗时与内存。
- 兜底(真全量):`--index-all` + `deepUnload` 彻底释放 + 提高并行,补全 spec 原定的"流式全量不爆内存"。

**Non-Goals:**
- 不改动 xref / 结构枚举 / 单类反编译 / 资源工具 —— 它们走全量 symbol 图谱或按需反编译,本就不受 14% 覆盖限制。
- 不追求对抖音 `X` 包(29.7 万类混淆,属必索引)的极致缩减 —— 该特例下层二收益有限,靠层一解决噪点。
- 不引入跨进程/分布式反编译。

## Decisions

### D1 · 拆成"搜索结果层"与"索引范围层",层一优先
噪点/重复属**搜索结果质量**,与索引覆盖范围正交:即便索引全量,只要查询时过滤标准库、按类聚合、相关性排序,噪点即消除。故先做层一(改 `CodeSearchIndex` 查询与结果组装,零索引改动、零风险、立即见效),层二独立推进。
*备选*:只做"全量 + deepUnload" —— 贵且不解决噪点,否。

### D2 · 价值分层 T1–T4(白名单 app/混淆 + 黑名单标准库 + 可配)
- T1 app 自身:AndroidManifest `package` + 同源厂商系前缀(如抖音 `com.bytedance.*`),必索引。
- T2 混淆包:顶级单字母/极短段包名启发式(`X`/`a`/`ib`/`bo`),索引。
- T3 具名第三方:`com.<vendor>.*`,默认索引、可配跳过。
- T4 标准库/常见 SDK:`android`/`androidx`/`java`/`javax`/`kotlin`/`kotlinx`/`com.google`/`com.android`/`okhttp3`/`okio`/`retrofit2` 等,默认跳过。
- `--index-include` / `--index-exclude` 包前缀覆盖默认。
*备选*:纯白名单(只 app+混淆)会漏厂商系/关注三方;纯黑名单需无限追 SDK 列表。混合最稳。

### D3 · `deepUnload`/`unloadCode` 替代 `cn.unload()`,补全流式全量
`deepUnload()` 在 `unload()` 基础上 `clearAttributes()` + `root().getConstValues().removeForClass()` + 重置 + 递归内部类,清掉单进程反编译的全局累积。改 `IndexBuilder` phase-3 用它,使单进程对选定范围(或 `--index-all` 全部)在 20GB 内一轮达成 `coverage_complete=true`。
*备选*:多轮 `load_apk` 续建(现状)—— 每轮重复 load dex(~104s)+ 重复 phase1/2,且需外部驱动循环。

### D4 · 解开并行枷锁
现 `decompilePass` 用 `par = cores/2`,受 heap 压制(并行越高同时驻留的反编译状态越多、越早撞墙)。`deepUnload` 让内存不再是瓶颈后可提高 `par`,把反编译速率(当前 33.9 类/s)往上推,压缩全量耗时。
*备选*:保持低并行 —— 浪费多核,全量仍 ~2.7h。

### D5 · 结果按类聚合 + 相关性排序
FTS 路径本就每类一条;ripgrep 兜底改为按类折叠(class + 行号列表)。统一相关性排序:app 包 > 混淆包 > 具名三方;`limit` 在排序后,确保最该分析的先返回。`find_string_usages` 收敛为精确整串匹配(子串归 `search_string_constants`,v1.1.1 已正交化)。

## Risks / Trade-offs

- **标准库黑名单维护成本** → 覆盖最常见 ~80% SDK 即消大部分噪点;`--index-include/exclude` 兜底用户按需调整。
- **`deepUnload` 的 `load(clsData,true)` 重置开销** → 全量搜索每类只反编译一次、不重复访问,成本可接受;不适用于反复访问同类的场景(本场景不涉及)。
- **抖音 `X` 包必索引,层二收益有限** → 层一(结果过滤/排序/聚合)独立于范围,照样解决该特例噪点。
- **`deepUnload` 是否清干净未完全验证**(`RootNode.CacheStorage` 等其他全局累积点未确认) → 见 Open Questions,需 spike 实测峰值/速率后再定全量路径。
- **T1 厂商系前缀判定** → 初版可由 manifest package 顶级 2 段 + 一份常见厂商前缀启发式;不确定时偏保守(纳入索引)。

## Open Questions

- `RootNode.CacheStorage`(及其他全局)在 `deepUnload` 后是否仍累积?需 spike:phase-3 换 `unloadCode` + 提高 `par`,实测抖音全量的堆峰值与反编译速率。
- 全量路径目标:`--index-all` 在 20GB 内能否对抖音一轮 `coverage_complete=true`,还是仍需重载续建?
- T1 厂商系前缀:硬编码常见前缀,还是从 manifest/签名推断?

## 数据支撑(基线)

已有(本会话冷启动实测,jar 索引逻辑等价 v1.0.0):
- **滴滴 132MB**:单轮即 `coverage_complete=true`,index build ~246s,950877 符号 / 3695331 边 / 399549 字符串。
- **抖音 282MB**:单轮 `coverage_complete=false`(14%,47114/319263),build ~26min(其中 phase-3 反编译 47114 类 23.1min,33.9 类/s),symbols 4564540 / edges 29500666。全量外推 ~2.7h。

已补(本变更实现后实测,`removeForClass` + 分块 barrier 释放,`par=cores=22`,`-Xmx20g`):
- **APKPure 20MB**:14778 顶层类 → 11502 索引 / **3276(22%)标准库跳过**;**单轮 `coverage_complete=true`**,build 41s,**峰值堆 2.68 GB**;层一/层二断言全 PASS,`Can't restore usage data` 警告 **0**。
- **抖音(默认 scope)**:in-scope **312498 / 跳过 6765**(`X` 包属 T2 必索引,层二收益有限——符合预期)。两阶段实测:
  - *仅 `removeForClass`*:单轮 ~40min 到 **190842(61%)** 触低堆守护停下(`coverage_complete=false`,优雅续建,满足 spec),峰值 19.3GB。
  - *+ `cn.unload()`(最终)*:单轮 **312498(100%)`coverage_complete=true`**,phase-3 ~27.6min(~68 类/s),**峰值堆 19,299 MB(< 20GB,无 OOM)**;层一/层二断言全 PASS、`Can't restore usage data` 警告 **0**。

**结论(spike 3.1/3.4 定稿,jmap 差分 + 绝对计数定位)**:
1. **根因 = 反编译 IR 留存**,非 `ConstValues`、**非 `CacheStorage`**(后者经查仅 `Set<String> rootPkgs`,与 IR 无关,design 假设证伪)。jmap 差分显示 phase-3 增长 top 全是 IR:`BlockNode`/`RegisterArg`/`SSAVar`/`InvokeNode`/`TypeBoundConst`/逐节点 `AttributeStorage`。
2. **必须两步释放**:`removeForClass` 清全局 `ConstValues`(把单轮从旧 47114/14% 推到 190842/61%);`cn.unload()` 清反编译 IR(`MethodNode.unload` nulls blocks/ssa/insns/region,再续到 **312498/100%**)。惰性 `getCode()` API 不像 `save()` 会自动卸载,故必须显式 `unload()`(本变更前一版误删了它)。决定性证据:跑到 96% 时 RSS 从 19.1GB **回落到 17.9GB**(indexed 更多堆反更低)= IR 确被每块释放、不再单调累积。
3. 全量达成:**单进程单轮 20GB 内即可**(上述实测为从 ~200k 续建完成最后 112k;从零单轮可选 clean fresh 复核,堆回落证据已支持)。`--index-all`(含标准库)若仍超 20GB 则回退优雅续建,语义不变。
4. **速度与覆盖正交**:构建期 CPU 仅 ~17%(22 核中 ~3.7),提速空间在**串行 SQLite FTS 写临界区**(`indexCode` 的 `writeLock`),非反编译;为后续可选优化(`extractStringLiterals` 移出锁 / 生产者-消费者单写线程),不影响已达成的全量覆盖。
