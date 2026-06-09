## Why

`analysis-value-code-search` 已让抖音**单轮全量覆盖**(312,498 类 / `coverage_complete=true` / 峰值 19.3GB)。但**首次构建慢**:抖音 phase-3 从零外推 ~78min(实测续建 27.6min/112k 类,~68 类/s)。

瓶颈已实测 + jmap 定位清楚:
- **构建期 CPU 仅 ~17%**(22 核里 ~3.7 核在跑),大量线程在**等锁**;
- `CodeSearchIndex.indexCode` 的全局 `writeLock` 把 **FTS trigram 分词串成单线程**(~14.7ms/类、1 核满载),22 个反编译线程算完都挤在这把锁上;
- **不是磁盘 I/O**(才 ~2.9 MB/s、~190 文件/s,SSD 地板级;SQLite 每条不 fsync);
- **不是反编译本身**(反编译是并行的,只吃 ~2.7 核,还有 ~20 核闲着);
- 另一处串行成本:`structure+usage` 导出 ~21.5min(29.5M 边 = 才 2.3万/s,严重未批量)。

目标:在**不降反编译质量**(这是通用 MCP,不能像抖音那样假设"名字反正没了"就用 SIMPLE——别的 APK 可能保留 debug-info/source-name)的前提下,把首次全量构建从"被串行 FTS 拖住"压到"贴近反编译 CPU 地板",**多核吃满**。

## What Changes

- **解耦流水线**:反编译线程并行产出 `{clsIdx, code}` → 有界队列(~50MB 反压)→ 写线程;反编译不再被 FTS 锁阻塞。
- **`extractStringLiterals` 移出锁**:纯 CPU 扫描并行化(零质量代价)。
- **FTS trigram 多分片**(M≈8,`clsId % M`):分词从 1 核并行到 M 核;`search_in_code` 查询跨分片 fan-out + 合并(结果语义不变)。
- **`structure+usage` 提速**:批量/预编译插入(目标 ≫23k 边/s)+ 与 phase-3 **重叠**(SQLite 写 vs 反编译 CPU,用不同资源)。
- **T3 具名三方"可配跳过"**:通用减量开关(不针对某 APK;T4 标准库已默认跳)。
- **反编译质量恒定**:始终全质量 RESTRUCTURE + debug-info + kotlin-metadata。**不引入 SIMPLE/FALLBACK 降级**。
- **先由 spike 定生死**:实测"纯反编译地板"速率 → 判定 8min 在 22 核 + 全质量下是否物理可达,还是必须加核 / 减类。

## Capabilities

### New Capabilities
(无 —— 演进现有能力。)

### Modified Capabilities
- `code-search-index`:索引构建从"单线程串行 FTS + 串行结构导出"改为"**多核解耦流水线 + M 分片并行分词 + 批量结构导出 + 与反编译重叠**";新增 FTS 分片存储与跨重启复用的分片语义。
- `mcp-re-toolset`:`search_in_code` 底层改为**跨 M 分片 fan-out 查询 + 合并**;对调用方**结果语义不变**(仍按类聚合、过滤标准库、相关性排序、limit 在后)。

## Impact

- **代码**:`index/`(分片 schema + `IndexBuilder` 流水线/队列/写线程 + `SymbolGraph` 批量插入 + `CodeSearchIndex` 跨分片查询)、`mcp/ToolRegistry`(查询透传不变)、`util/CacheLayout`(`fts/` 子目录归拢分片文件)。
- **零质量代价**:反编译输出与现在逐字节一致(全质量 RESTRUCTURE);`get_class_source` 不退化——这是通用性硬约束。
- **schema bump**:多分片文件 + `resume`/`coverage_complete` 跨分片;旧索引按 `schema_version` 失效重建。
- **不变**:≤60s 工具调用预算、`-Xmx20g`、analysis-value 的过滤/聚合/排序行为。
- **8min 目标的诚实边界**:全质量反编译 312k 类在 22 核的 CPU 地板约 ~9-13min;本变更让构建贴近该地板(从 ~100min → 估 ~12-15min),**8min 是否可达由 task 0 的地板 spike 裁定**——若低于地板,则需更多核(线性,通用安全)或减少索引类数(T3 可配),软件并行无法突破反编译地板。
- **关联**:延续 `analysis-value-code-search`(根因修复 + 全量覆盖)与 `headless-jadx-mcp`(D6 SQLite/FTS、D7 出堆)。
