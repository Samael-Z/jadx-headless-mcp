## Why

全量反编译 + 索引抖音级 APK(312k in-scope 类)在 20GB 内是一个**~46min / 2 次会话**的任务(`fast-index-pipeline` 已实测:冷构建单会话只能覆盖 ~71%,触 20GB backstop 后 reload 续建到 100%)。当前设计下,**在构建基本跑完前几乎没有可用结果**——用户要等很久才能搜到任何东西。

但反编译本身是 CPU/锁绑定的不可压缩成本(实测 ~140–228 类/s,且本变更**明确不动**反编译并行度),无法靠软件把总时间砍到"快"。既然总时长压不下去,就**换一个维度**:不追求"更快地全部完成",而是让**最有分析价值的代码尽早可搜**,其余在后台渐进补齐——把"一个长任务"变成"分层渐进可用"。

## What Changes

- **解耦反编译与 FTS 索引**:反编译(产 `.java` 到磁盘)与建 FTS trigram 索引拆成可独立推进的阶段;FTS 在后台进行、可中断、可跨重启续建,不再与反编译强耦合在同一相。
- **按分析价值分层调度**:`load_apk` 后优先反编译**主包**(manifest 包 + 同源,抖音 62,842 类),其余 24.9 万类在后台继续;**xref 图谱**(structure+usage,不依赖反编译)最早建好;**字符串常量索引**(对混淆 app 头号定位、极廉价)跟随反编译就绪。
- **`search_in_code` 跨相透明**:对调用方签名/语义不变,底层合并 `FTS(已索引) ∪ ripgrep(已反编译未索引)`,使"已反编译但 FTS 未覆盖"的类也可搜;并通过 `index_status` 上报覆盖率(反编译% / 索引% / 主包就绪 / xref 就绪)。
- **≤20GB + resume**:全程不超 20GB 硬约束;接受"分块 → backstop → reload 续建"的多会话闭合(不强求单会话 100%)。
- **质量与速度恒定**:反编译恒为全质量 RESTRUCTURE;**不引入降级、不改反编译并行度/锁争用**(速度按现状 ~200 类/s)。

## Capabilities

### New Capabilities
(无 —— 演进现有能力。)

### Modified Capabilities
- `code-search-index`:索引构建从"反编译+FTS 耦合的单一长相"改为"**按分析价值分层 + 反编译/FTS 解耦 + 各产物就绪即可用**";新增反编译相与索引相的独立推进、跨重启续建语义,及 xref/字符串/代码三类产物的 tier 排序。
- `mcp-re-toolset`:`search_in_code` 底层改为**跨相合并**(FTS ∪ 已反编译 ripgrep),对调用方结果语义不变;`index_status` 扩展**可用性/覆盖率字段**(decompiled/indexed/main_ready/xref_ready)。

## Impact

- **代码**:`IndexBuilder`(分层调度 + 反编译/FTS 解耦 + 优先级队列)、`CodeSearchIndex.searchInCode`(FTS ∪ ripgrep 跨相合并 + 覆盖上报)、`IndexStatus`(可用性字段)、`JadxService.loadApk`(返回策略/主包优先)、`mcp/ToolRegistry`(透传不变 + index_status 字段)。
- **复用**:站在 `fast-index-pipeline` 之上——沿用分片 FTS(`FtsShards`)、deferred 图索引(`Db.createGraphIndexes`)、分块 barrier 释放、`AnalysisScope` 分层、`DiskCodeCache`(.java 落盘)、ripgrep 兜底。
- **不变**:≤20GB、`-Xmx20g`、全质量反编译、analysis-value 过滤/聚合/排序、xref 出堆设计、≤60s 工具预算。**不触碰**反编译并行度(`DecompilerScheduler`/锁争用)——那是另案(`fast-index-pipeline` task 6 已搁置)。
- **关联**:延续 `fast-index-pipeline`(分片/结构批量)与 `analysis-value-code-search`(分层 + 根因释放)。
