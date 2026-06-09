## ADDED Requirements

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
