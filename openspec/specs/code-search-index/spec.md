# code-search-index Specification

## Purpose

`search_in_code` 的持久化缓存 + 倒排索引能力:以 **dex 稳定身份**为结构键,流式构建(解→落盘→入库→释放堆)在内存预算内、按 APK 哈希一次构建跨重启复用,并在 rename 时增量失效。为 `mcp-re-toolset` 的全文代码搜索提供索引支撑,构建于 `headless-jadx-server` 之上。

## Requirements

### Requirement: search_in_code 持久化缓存与倒排索引

为在 60s 预算内支持全文代码搜索,服务 SHALL 维护**双层磁盘持久化**:① 反编译文本 code cache;② token 倒排索引(token → 类集合)。`search_in_code` SHALL 以索引查询实现,而非每次全量扫描。索引/缓存 SHALL 按 APK 内容哈希为键,一次构建、跨重启复用。

#### Scenario: 二次搜索走索引

- **WHEN** 索引建好后再次 `search_in_code`
- **THEN** 以倒排索引返回命中类(秒级),不重新反编译全部类

#### Scenario: 重启复用

- **WHEN** 服务重启后加载同一个 APK(哈希不变)
- **THEN** 直接复用已落盘的 cache/索引,无需重新反编译/重建

### Requirement: 以 dex 稳定身份为键

cache/索引的结构键 SHALL 使用 **dex 层稳定身份**(原始类型描述符 / dex 类索引 / 方法&字段 ref),而非 jadx 的显示名。显示名(source-name/kotlin-metadata/deobf/用户 rename)SHALL 作为挂在稳定键上的元数据层。改变名字恢复设置 MUST NOT 使结构索引整体失效(仅刷新显示名层)。

#### Scenario: 改命名设置不毁索引

- **WHEN** 切换 `deobf` 开/关或调整 source-name 策略
- **THEN** 结构倒排索引仍有效,仅显示名元数据需要刷新,无需全量重建

### Requirement: 流式构建在内存预算内

索引构建 SHALL 流式进行(解一个类 → 落盘文本 → 分词入索引 → **彻底释放堆**),使构建期堆占用有界,叠加在加载基线之上仍 < 20 GB。"彻底释放堆" SHALL 清除单类反编译在 jadx 全局结构上的累积(如 `RootNode` 的 `ConstValues`),即采用 `deepUnload`/`unloadCode` 而非仅 `unload()`,以使单进程在 20 GB 内对**选定范围**(见"分析价值导向的选择性索引";或 `--index-all` 的全部类)达成 `coverage_complete=true`,无需多轮重载。构建可折叠进加载阶段或后台进行,并经 `index_status` 暴露进度与 `coverage_complete`。

#### Scenario: 构建抖音索引不爆内存

- **WHEN** 为抖音构建选定范围的全文索引
- **THEN** 反编译文本与索引落盘,堆占用保持有界(< 20 GB),不一次性把全部反编译文本驻留内存

#### Scenario: 单进程对选定范围达成全量覆盖

- **WHEN** 对一个 APK 的分析价值类(或 `--index-all` 全部类)构建索引,且每类反编译后彻底释放堆
- **THEN** 单进程一轮内对该范围达成 `coverage_complete=true`(不中途撞 `lowHeap` 守护停下);若仍超 20 GB,`index_status` 报告部分覆盖并支持重载续建,而非静默丢失

### Requirement: 改名增量失效

当发生 rename(用户改名或映射应用)时,服务 SHALL 仅对受影响的类增量更新 cache/索引,而非全量重建。

#### Scenario: 单类改名只增量更新

- **WHEN** `rename_class` 改了一个类
- **THEN** 仅该类(及直接引用它的类)的缓存文本/索引项被更新,其余条目不动

### Requirement: 分析价值导向的选择性索引

索引构建 SHALL 默认仅反编译/索引**有逆向分析价值**的类,按价值分层:T1 app 自身(AndroidManifest `package` + 同源厂商系前缀)与 T2 混淆包(单字母/极短段包名启发式)必索引;T4 标准库与常见 SDK(`android`/`androidx`/`java`/`javax`/`kotlin`/`kotlinx`/`com.google`/`com.android` 等)默认跳过。服务 SHALL 提供 `--index-include` / `--index-exclude` 包前缀以覆盖默认分层。

#### Scenario: 默认跳过标准库

- **WHEN** 加载一个普通 app 并以默认设置构建索引
- **THEN** android/androidx/kotlin/google 等标准库类不被反编译、不写入 FTS 索引,索引时间与体积相应下降

#### Scenario: 用户扩展索引范围

- **WHEN** 用户以 `--index-include com.somevendor` 指定关注的第三方库前缀
- **THEN** 该前缀下的类被纳入反编译/索引,即使它本不属 app 自身或混淆包

### Requirement: 真全量索引开关

服务 SHALL 提供 `--index-all` 开关,在其下索引全部类(含标准库),并依赖"流式构建在内存预算内"的彻底释放(`deepUnload`)+ 提高反编译并行度达成。`--index-all` MUST NOT 改变默认行为(默认仍为分析价值导向选择性索引)。

#### Scenario: 显式请求全量

- **WHEN** 以 `--index-all` 启动并对抖音级 APK 构建索引
- **THEN** 全部类(尽力)被反编译并索引;若 20 GB 内不能一轮覆盖,`index_status` 报告部分覆盖并支持重载续建,而非静默丢失结果

### Requirement: 多核解耦的构建流水线

索引构建 SHALL 将"反编译"与"索引写入"解耦:反编译线程(并行,数量 = 核数)只产出反编译文本与字符串字面量,经**有界队列**(按字节上限,满则反压、控堆)交给写线程做 SQLite 写入;反编译线程 SHALL NOT 被索引写锁阻塞,以使多核被充分利用(目标:构建期 CPU 利用率远高于当前的 ~17%)。释放阶段 SHALL 保留分块串行语义(并行反编译一个 chunk → barrier → 串行 `unload()` + 清全局 `ConstStorage`),以避免释放与并发反编译对 `ConstStorage.classes` 的读写竞态。

#### Scenario: 反编译不被索引写阻塞

- **WHEN** 构建一个大型 APK 的索引,反编译线程数 = 核数
- **THEN** 反编译线程持续产出、不在索引写锁上排队;构建期 CPU 利用率显著高于单线程串行索引时(不再是 1 核满载、其余等锁)

#### Scenario: 释放期无并发反编译竞态

- **WHEN** 一个 chunk 反编译产物全部入队并被写线程消费完毕
- **THEN** 在 barrier 后串行执行 `unload()` + `removeForClass` 释放该 chunk,期间无并发反编译读取 `ConstStorage.classes`,堆占用保持有界 < 20 GB

### Requirement: FTS 索引并行分片

`code_fts`(trigram 全文索引)SHALL 支持分为 M 个分片库(按 `clsId % M` 路由),由 M 个写线程**并行分词 + 插入**,以突破"单一 SQLite writer 串行分词"的吞吐天花板。`search_in_code` SHALL 跨全部分片查询并合并候选,合并后结果 SHALL 与单库时**语义一致**(仍按类聚合、默认过滤标准库、相关性排序、`limit` 在排序后施加)。`coverage_complete` 与 resume(跳过已索引类)SHALL 跨全部分片正确统计。

#### Scenario: 并行分词提升构建吞吐

- **WHEN** 以 M 个分片构建一个大型 APK 的全文索引
- **THEN** trigram 分词由 M 个线程并行进行,FTS 写入不再是单线程串行瓶颈

#### Scenario: 跨分片查询结果与单库一致

- **WHEN** 对分片索引执行 `search_in_code(q)`
- **THEN** 返回的命中类集合与未分片(单库)构建时一致,且仍按类聚合、过滤标准库、相关性排序;查询延迟仍在亚秒级、远低于 60s

#### Scenario: 跨分片复用与续建

- **WHEN** 重新加载同一 APK
- **THEN** 已建分片被复用、已索引类被跳过(`indexedRowids` 跨片并集);仅当全部分片覆盖完成时 `coverage_complete=true`

### Requirement: 结构与 usage 的批量导出

符号/边图谱(`symbols`/`edges`)的导出 SHALL 使用批量/预编译插入(大事务),使边写入速率远高于逐条插入;该导出 SHALL 可与反编译/FTS 阶段重叠进行(二者使用不同的写连接与资源),以缩短总构建时间。

#### Scenario: 批量导出显著快于逐条

- **WHEN** 导出抖音级 APK 的 ~29.5M 条边到 SQLite
- **THEN** 采用批量插入后边写入速率显著高于基线(~2.3 万/s),结构导出耗时相应下降

### Requirement: 构建不降低反编译质量(通用性约束)

索引构建为提速所做的任何改动 SHALL NOT 降低反编译输出质量:始终使用全质量反编译配置(RESTRUCTURE + debug-info + source-name + kotlin-metadata),不得为加速而引入 `simple`/`fallback` 模式或关闭 debug-info/内联。提速 SHALL 仅来自并行化、解耦、批量与(可配的)索引范围,而非降级反编译。减少索引工作量的通用手段 SHALL 限于按分析价值分层的**可配范围**(如可选跳过 T3 具名三方;标准库 T4 默认跳过),不得针对特定 APK 的混淆特性写死。

#### Scenario: 保留元数据的 APK 输出不退化

- **WHEN** 对一个保留 debug-info/source-name 的非混淆 APK 构建索引并取 `get_class_source`
- **THEN** 反编译输出(变量名、行号、结构)与提速改动前**逐字节一致**,可读性不降

#### Scenario: 通用减量经可配范围而非降级

- **WHEN** 用户希望减少索引工作量
- **THEN** 通过可配的分析价值范围(如跳过 T3 第三方)实现,而非降低反编译模式;默认仍全质量反编译被索引的类

### Requirement: 分析价值分层的渐进式构建

索引构建 SHALL 按分析价值分层、渐进推进,使高价值产物先就绪即可用,而非等全量完成:顺序为 **xref 图谱(structure+usage)→ 入口类 → 主包 → 其余**。每一层的产物 SHALL 在其就绪后立即对相应工具可用(xref 就绪后 `get_xrefs_*` 可用;某层类反编译后该层代码即可搜),不得阻塞在后续层或全量完成上。层的划分 SHALL 复用既有 `AnalysisScope`(入口=manifest 的 activity/service/receiver/provider/application;主包=T1 manifest 包+同源;其余=T2/T3)。

#### Scenario: 主包先于全量可搜

- **WHEN** `load_apk` 完成且主包类已反编译,但其余类仍在后台反编译
- **THEN** 主包类已可被 `search_in_code`/`search_string_constants` 搜到,无需等待其余类完成

#### Scenario: xref 最早可用

- **WHEN** `load_apk` 完成(模型就绪)、反编译尚未开始或进行中
- **THEN** xref 图谱(structure+usage)独立于反编译先行构建并就绪,`get_xrefs_*`/call-graph/subclasses 可用

### Requirement: 反编译与 FTS 解耦、后台可续

反编译(产出 `.java` 到磁盘 code cache)与 FTS trigram 索引构建 SHALL 解耦:FTS 在后台进行、可被中断、并可跨进程重启续建(已索引类按 `indexedRowids` 跨分片跳过);搜索的可用性 SHALL NOT 阻塞于 FTS 完成。字符串常量索引 SHALL 随反编译就绪(廉价,跟随同层产出),不得被推迟到全量之后。

#### Scenario: FTS 滞后不阻塞搜索

- **WHEN** 一批类已反编译(`.java` 在磁盘)但 FTS 尚未覆盖它们
- **THEN** 这些类仍可被 `search_in_code` 搜到(经 ripgrep 覆盖已反编译集),不必等其进入 FTS

#### Scenario: 跨重启续建至完整

- **WHEN** 重新加载同一 APK
- **THEN** 已反编译的类命中磁盘缓存(不重反编译)、已索引的类跨分片被跳过,构建续建剩余部分直至 `coverage_complete=true`

### Requirement: 20GB 内的渐进构建与多会话闭合

构建全程 SHALL NOT 超过 20 GB;当堆逼近上限时 SHALL 停转为可续状态(stop-to-resume,非 OOM),此时已索引/已反编译子集 SHALL 仍为可搜的 READY 状态,`coverage_complete=false` 直至后续 reload 续建完成。SHALL NOT 为单会话完成而要求放宽 20 GB 或降低反编译质量。

#### Scenario: 触顶转续建,子集仍可用

- **WHEN** 单会话反编译使堆逼近 20 GB
- **THEN** 构建停转为续建态,已覆盖子集(含主包)保持可搜,reload 后续建并最终达到 `coverage_complete=true`

### Requirement: 可用性与覆盖率上报

`index_status` SHALL 上报分层可用性与覆盖率,使调用方可判断当前可搜范围:至少包含 `decompiled_classes`、`indexed_classes`、`xref_ready`、`main_ready`、`current_tier`。这些标志 SHALL 可从落盘产物可靠推断(以便会话重启后仍正确)。

#### Scenario: 报告分层就绪状态

- **WHEN** 主包已反编译、其余仍在后台
- **THEN** `index_status` 显示 `main_ready=true`、`current_tier` 指示当前层、以及 decompiled/indexed 计数
