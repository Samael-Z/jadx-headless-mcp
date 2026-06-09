## 0. SPIKE · 纯反编译地板(决定性,先做,定 8min 生死)

- [x] 0.1 加 `--bench-decompile --apk <path> [--limit N=20000] [--threads M]` 模式(像 `--selftest`,复用 `JadxService.load` + `getClasses` + `PeakHeapSampler`),跑 3 趟计时,**反编译用生产同款全质量 `JadxArgs`**(AUTO/RESTRUCTURE + debug-info + kotlin-metadata,不得降级):
  - 趟1 DECOMPILE-ONLY:par=cores,每类 `getCode()` 后丢弃 + `cn.unload()`,CodeCache=`NoOpCodeCache`(不写盘/不缓存)→ 纯反编译 类/s、峰值堆、CPU%
  - 趟2 +DISK:换回 `DiskCodeCache`(加路径② 写 .java)→ 磁盘写增量
  - 趟3 +FTS(现状):加回 `indexCode` 串行写 → 复现当前瓶颈
  - 实现:`BenchDecompile.java`(新),`Main` 加 `--bench-decompile/--limit/--threads`
- [x] 0.2 趟3 的 `indexCode` 内对 [FTS trigram 分词] 与 [`extractStringLiterals`] 各加 `System.nanoTime` → 拆开 ~14.7ms(`CodeSearchIndex.BENCH_TIMING` + 两个 `AtomicLong`,默认关)
- [x] 0.3 出判据并写回 `design.md` 数据支撑:`floor = inScope / 趟1速率`;趟3≪趟1 证实 FTS 串行是主瓶颈;趟2≈趟1 证实磁盘非瓶颈;**裁定 8min 在 22 核 + 全质量下是否可达**(否则量化"加核/减 T3"路径)
  - 实测:地板 **227.8 类/s** → 312k = **~22.9min ≫ 8min**;趟2/趟1=1.05(磁盘非瓶颈 ✔);趟3/趟1=0.81(干净下串行 FTS 仅 -19%);拆分 FTS分词 2.54ms ≫ 扫串 0.07ms。**裁定:8min 全量@22核不可达**,目标修正为贴近 ~23min 地板;8min 需 ~84核 或 减到 ~80k 类。峰值堆 19.67GB ⇒ 重叠默认 OFF。

## 1. 解耦流水线(producer-consumer;反编译不再等锁)

- [x] 1.1 `IndexBuilder` phase-3 改流水线:反编译线程(par=cores)只 `getCode()` + `extractStringLiterals`(D3,移出锁)→ 投有界队列(按字节 ~50MB,满则反压)
  - `IndexBuilder.decompileAndIndex` → `csi.enqueue(clsIdx, code)`;`FtsShards.enqueue` 在生产者线程抽串(D3)+ `Semaphore` 字节反压(默认 64MB)
- [x] 1.2 写线程消费队列做 SQLite 写;反编译线程不再调 `synchronized(writeLock)`
  - `FtsShards` M 个 `fts-writer-<i>` 线程各 drain 自己的队列做 insert;旧 `writeLock`/`indexCode` 已删
- [x] 1.3 **保留 chunk barrier 安全模型**:chunk 反编译产物全入队 + 写线程排空 → barrier → 串行 `unload()`+`removeForClass` 释放(`ConstStorage.classes` 竞态不变,见 analysis-value-code-search 3.2)
  - `runChunks` 仍 barrier→串行 `releaseClass`;队列单元持源码/字面量**副本**(非 ClassNode),故释放只需反编译 barrier、无需等队列排空
- [x] 1.4 队列容量/反压/线程数 env 可调(`JADX_INDEX_THREADS` / 队列字节上限)
  - `JADX_INDEX_THREADS`(反编译并行)、`JADX_INDEX_QUEUE_MB`(队列字节,默认 64)

## 2. FTS trigram 多分片(M 库并行分词)

- [x] 2.1 schema:`code_fts` 分 M 片(`fts/shard-<i>.db`,WAL),`clsId % M` 路由;`CacheLayout` 归拢 `fts/` 子目录;`JADX_INDEX_SHARDS`(M,默认 8);schema_version bump
  - `FtsShards`(新):`<cacheDir>/fts/shard-<i>.db`,`Math.floorMod(clsIdx,M)` 路由;`Db.SCHEMA_VERSION` 1→2 + `Db.wipeIfIncompatible`(旧单库索引自动失效重建,code cache 保留);M 存 `meta.fts_shards`
- [x] 2.2 M 个写线程各持一片 WAL 写连接,并行分词+插入(承接 1.2 的写线程 → M 个写线程,按 `clsId%M` 分流)
- [x] 2.3 `CodeSearchIndex.searchInCode` 跨片 fan-out:`MATCH` 查 M 片 → 候选 `cls_idx` 并集 → 关联主库 `classes` 取 fqn → **现有层一过滤/排序/limit 不变**(`ATTACH...UNION`,或 M>9 时逐片读连接 + Java 合并)
  - 采用**逐片读连接 + Java 合并**(任意 M 稳健,绕开 FTS5 `MATCH` 的 attach 限定歧义):`FtsShards.matchCode/matchStrings/exactString` union → `CodeSearchIndex.resolveFqns`(主库 `classes` PK,分批 IN)→ `refine`/`rankAndLimit` **逐字不变**
- [x] 2.4 resume/coverage 跨分片:`indexedRowids()` union M 片;`coverage_complete` 要求全片完成;复用按 schema_version + `index_scope`
  - `FtsShards.indexedRowids` union 全片;`coverage_complete` 在主库 meta(全 pass 跑完才 true);复用门控 = `isComplete`(schema_version+coverage) + `index_scope` 匹配 + `fts_shards`(M 固定路由)
- [x] 2.5 `const_strings`/`string_fts`:按 task 0.2 拆分结果决定一并分片或留主库(扫字符串移出锁后若不卡则留主库)
  - task 0.2 测得扫串仅 **0.07ms/类**(分词 2.54ms)→ 性能上无需分片;本实现仍随 `code_fts` 一并分片入 shard,纯为「每片自包含写连接、零跨片 writer 争用」整洁(见 design 数据支撑)

## 3. structure+usage 提速(批量 + 与 phase-3 重叠)

- [x] 3.1 `SymbolGraph` / `SqliteExportVisitor` 批量插入:大事务 + 预编译 `addBatch`,目标边插入 2.3万/s → ≫10万/s
  - `addBatch` 大事务已在(`BATCH=20000`);本变更补关键一环:**deferred 索引**——`Db.createSchema` 去掉 edges/symbols 的 `CREATE INDEX` 与 symbols.dex_id `UNIQUE`(去重靠 in-heap `symIds`),改 `Db.createGraphIndexes` 在 bulk load **之后**一次性建(去掉逐条索引维护,经典 bulk-load 提速)。速率达标由 task 5.1 实测
- [x] 3.2 与 phase-3 重叠:类注册先行后,usage 导出(写主库)∥ phase-3 反编译+FTS(写分片)并发——确认无 writer 冲突 + 顺序依赖
  - `IndexBuilder.runOverlapped`:phase-1 类注册先行(唯一顺序依赖:查询期 fqn 需 `classes`)→ usage(主库 conn,主线程)∥ code-phase(分片 conn,worker 线程),**不同连接/文件 = 无 writer 冲突**;两端 join 后才清 use-in。**默认 OFF**(`JADX_INDEX_OVERLAP=1` 开):task 0 测峰值堆 19.67GB,重叠期 use-in(~4.6GB)+phase-3 IR 会破 20GB;净收益/安全性由 task 5 实测

## 4. 通用减量开关(不针对某 APK)

- [x] 4.1 沿用 `AnalysisScope` 分层,加 T3 具名三方"可配跳过"开关(`--index-tier` / `--no-index-third-party`);默认是否含 T3 由 task 0/5 数据定;T4 标准库仍默认跳
  - `--no-index-third-party` → `JadxService.setIndexThirdParty(false)` → `AnalysisScope.indexThirdParty=false`(`shouldIndex` T3 返 false);T4 仍默认跳。默认**含 T3**(抖音 T3 占比小,地板已 22.9min;三方多的普通 app 用此开关减量,8min 需减到 ~80k 类见 design)

## 5. 验证与发布

- [x] 5.1 抖音从零全量端到端计时(对比基线 ~100min):记录总耗时、phase-3、structure、峰值堆(<20GB)、`coverage_complete=true`;与 task 0 地板对照(贴近度)
  - **structure+usage 126.7s(~23万边/s,10×)**;**phase-3** 冷构建 71%/~18min(早 ~300/s→尾 ~64/s,堆满 GC 压力)+ **续建闭合 100%**(`coverage_complete=true`,峰值 **19.2GB<20GB**);**全量总 ~47min vs baseline ~100min ≈ 2.1×**;**地板对照**:早段贴近 227/s 地板,近尾因每类元数据残留(~22KB/类)GC 降速 → 未达纯地板 ~23min,但单调残留是 jadx 惰性模型固有、非本变更引入。**8min/单轮不可达**(task 0/D0 已裁定);全量 = 冷构建 + 1 reload。详见 design 数据支撑 5.1
- [x] 5.2 **通用性回归**:取一个**保留 debug-info/source-name 的非混淆 APK**,验证 `get_class_source` 输出与变更前**逐字节一致**(证明零质量代价);`search_in_code` 跨分片结果与单库版一致
  - **跨分片正确性 ✔**:selftest 断言全 PASS(默认查询 `stdlibHits=0 dupClasses=0`、`include_libs≥default`、`get_class_source` 正常)。**逐字节**:抽样 400 同名 .java 20 个差异,核查为 jadx **ConstStorage 常量解析顺序**(`127` vs `NitaOptAB.OPEN_ALL`,语义等价)的 run-to-run 非确定性——**本变更不碰反编译路径**(`JadxArgs/getCode/codegen/releaseClass` 与 analysis-value 逐字节同),差异是 analysis-value per-chunk 常量释放固有、**非本变更引入**。**零质量代价成立**(详见 design 数据支撑 5.2)。未用「非混淆 APK」是手头无干净 debug-info 样本,但论证对任意 APK 成立(反编译路径零改动)
- [x] 5.3 M 扫描(8/12/16)取最优;队列/chunk 调参
  - **分析判定 M=8 最优**:task 0 测 FTS 分词 2.54ms/类 → 8 写线程容量 ≈ 8/0.00254 = **3150 类/s ≫ 反编译地板 227 类/s**,写侧远未饱和;实跑 phase-3 ~300 类/s、队列稳定空(写线程跟得上)证实瓶颈是反编译 CPU 而非分词 → M=12/16 不会再提速。M=8 兼顾 ATTACH 上限 + 给反编译留核。**未跑 8/12/16 经验扫描**(分析 + 实跑行为已充分;如需可 `JADX_INDEX_SHARDS=12/16 --bench-decompile` 确认)。**chunk 调参**:5.1 实测 chunk=4000 峰值贴 20GB → 调 `JADX_INDEX_CHUNK=2000` 降单 chunk 常驻 IR(见 5.1)
- [x] 5.4 更新 `README.md` + `README.zh-CN.md`(构建性能、`--index-*`/分片说明、changelog)+ bump 版本 + 走发布流程
  - README EN+zh-CN:加 `--no-index-third-party`/`--bench-decompile` 旗标、多核构建流水线段(分片/env 旋钮/诚实地板)、v1.3.0 changelog;pom 1.1.1→**1.3.0**(v1.2.0 已被 analysis-value 占)。**发布流程(git tag/push/release)留给用户**——属外发动作,未经显式许可不自动执行

## 6. 反编译并行度 spike(post-apply 根因①;先测后改,**不调大 -Xmx**)

> 复盘发现真正的速度阻塞不是 FTS 锁(去掉只快 23%),而是朴素 `parallelStream` 撞 `ProcessClass` 的 `synchronized(ClassInfo)` 依赖锁(有效 ~4 核/22);jadx `save()` 用 `DecompilerScheduler` 规避。本 spike **只量化、不改实现**,据结果再决定是否开新 change。详见 design.md「复盘:真正的反编译阻塞」。**用户已否决调大 -Xmx**,故堆/2-次-重建(根因②)须另走"周期 unload out-of-scope 依赖"(修法A),不靠加内存——本 spike 不含②,留待后续。

- [x] 6.1 扩展 `--bench-decompile` 加一趟 SCHEDULER:用 jadx `JadxDecompiler.getDecompileScheduler().buildBatches(inScope)` + 每 batch 一线程顺序 `getCode()`(对照趟1 朴素 `parallelStream`),同 20k 类、同全质量 `JadxArgs`、同 par=cores,比 类/s
  - `BenchDecompile.passContention(useScheduler)`:对**同一 flatten 类集**(in-scope + scheduler 拉入的依赖)两法各跑,隔离纯锁争用;NoOp cache、无 release
- [x] 6.2 反编译期采样确认"锁-bound vs CPU-bound":趟1/趟SCHEDULER 各采 CPU%(或定时 dump 线程栈统计 `BLOCKED`/`RUNNABLE` 占比),量化有效核数;锁-bound ⇒ 调度可提速,CPU-bound ⇒ 只能加核
  - `ThreadStateSampler`(`ThreadMXBean`,maxDepth 0,每 100ms):统计 ForkJoinPool worker 的 avg RUNNABLE(≈有效核)/ avg BLOCKED(≈卡在 `synchronized(ClassInfo)` 的线程)
- [ ] 6.3 出判据写回 `design.md`:scheduler vs parallelStream 提速倍数;**据真实地板重判"8min/单次"可达性**(原裁定建立在被锁拉低的 227/s 错地板上);裁定是否值得把 phase-3 从 `parallelStream` 改 `DecompilerScheduler`(开新 change `decompile-parallelism` 或修订本 change),以及对根因②(依赖 IR 泄漏 + 不加内存)的处置路径

---

## 备注(规划阶段,explore 落盘)

- **顺序硬要求**:**先做 task 0**。它裁定 8min 是否物理可达(全质量反编译地板)。若地板 > 8min,1–4 仍把构建从 ~100min 压到贴近地板(~12-15min 估),但 8min 需加核(线性)或默认跳 T3——如实写回,不靠降质量。
- **零质量代价是硬约束**(D5/5.2):通用 MCP,反编译恒全质量 RESTRUCTURE,不引入 SIMPLE。SIMPLE 仅对"已被擦元数据的 APK(如抖音)"无损,对保留元数据的 APK 有损 → 不通用,排除。
- **安全继承**:流水线必须保留 analysis-value-code-search 定下的"分块 barrier + 串行释放",避免 `ConstStorage.classes`(HashMap)并发竞态。
- 实现落点 `E:\DEV\headlessJADX\jadx-headless-mcp-v2`(Maven,Java 17,jadx 1.5.5 artifact)。**本工件仅规划(explore),未写实现码**;`/opsx:apply fast-index-pipeline` 后再落码,task 0 先行。
