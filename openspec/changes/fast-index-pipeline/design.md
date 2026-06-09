## Context

`analysis-value-code-search` 修好了 IR 留存(`unload()`+`removeForClass` 分块串行释放),抖音单轮全量达成(312,498/100%,峰值 19.3GB)。但**首次构建仍慢**,本变更专攻**构建速度**,不改覆盖/质量/查询语义。

**已实测的瓶颈画像**(同会话):
- 吞吐 ~68 类/s;构建期 CPU ~17%(22 核中 ~3.7 核)。
- 每类 CPU ≈ 54ms:反编译 ~40ms(并行,~2.7 核)+ `indexCode` ~14.7ms(**串行**,1 核,全局 `writeLock`)。
- `indexCode` 串行段内 = FTS5 trigram 分词整段源码 + `extractStringLiterals` 全文扫描 + SQLite insert(WAL,每条不 fsync)。
- 磁盘非瓶颈:~2.9 MB/s、~190 文件/s。
- `structure+usage` 导出 ~21.5min(4.56M 符号 / 29.5M 边,= 2.3万边/s,疑未批量)。
- jmap 已确认 code cache 有界(`AnnotatedCodeInfo`=1500=LRU),非泄漏源;`CacheStorage` 仅 `Set<String> rootPkgs`,无关。

**通用性约束(本次的硬前提)**:这是面向任意 APK 的通用 MCP。抖音"名字已被擦除(X 包 17.7万 `C####` 混淆类)"是特例;别的 APK 可能保留 debug-info/source-name。**因此不能用 `-m simple`/`--no-debug-info`/`--no-inline` 降反编译质量**——那会毁掉有元数据 APK 的 `get_class_source` 可读性。质量恒定,只在"反编译之外"和"并行度"上要速度。

## Goals / Non-Goals

**Goals:**
- 首次全量构建贴近**反编译 CPU 地板**:消除串行 FTS、串行结构、锁争用,让多核吃满。
- 零质量代价、通用(不针对某 APK 的混淆特性)。
- `search_in_code` 查询语义不变(仍亚秒、按类聚合、过滤标准库、相关性排序)。

**Non-Goals:**
- 不降反编译质量(no SIMPLE/FALLBACK/no-debug-info)。
- 不针对抖音特性写死逻辑。
- 不改 analysis-value 的过滤/聚合/排序、不改 ≤60s/20GB 约束、不改 xref 出堆设计。
- 不追求突破反编译 CPP 地板的"魔法"(地板靠加核/减类,不靠软件)。

## Decisions

### D0 · 先测"纯反编译地板",再定 8min 可达性【决定性,task 0】
软件并行最多把构建带到"反编译 CPU 地板"(估 ~9-13min/312k/22 核,但 ~40ms/类是从被锁堵着的状态反推、不够纯)。先用 `--bench-decompile` 测纯反编译速率(NoOp cache、无 FTS、无 index),算出真实地板:`floor = inScope / rate`。
- 若 floor·效率 < 8min → 8min 在 22 核可达,本方案达成即可。
- 若 floor > 8min → 8min 必须**加核**(线性、通用安全)或**减类**(T3 可配),写回结论。
*备选*:直接猜 + 实现 → 可能做完发现被地板卡住、8min 本就不可能,白费。

**【task 0 实测裁定】** 纯反编译地板 = **227.8 类/s**(22 核全质量,远低于设计臆测的 ~40ms/类≈550/s——那是被锁堵着反推的)。312k in-scope ⇒ 地板 **~22.9min ≫ 8min**。**结论:8min 在 22 核 + 全质量 + 全量下物理不可达**;本方案目标修正为「贴近 ~23min 反编译地板」(从 ~100min → 估 ~25-30min)。8min 仅在 **加核到 ~84 核** 或 **减到 ~80k in-scope 类(T3 跳)** 时可达。详见 §数据支撑。

### D1 · 解耦流水线(producer-consumer),保留 chunk barrier 安全模型
反编译线程只做 `getCode()` + `extractStringLiterals`(纯 CPU,移出锁)→ 投有界队列(按字节计 ~50MB,满则反压、控堆)→ 写线程消费做 SQLite。反编译不再被 `writeLock` 阻塞 → 吃满核。
**安全不变**:`analysis-value-code-search` 已确立的"分块:并行反编译 → barrier → 串行 `unload()`+`removeForClass` 释放"必须保留(`ConstStorage.classes` 是 HashMap,释放期不能有并发反编译读)。流水线是**chunk 内**的并行化:chunk 的反编译产物全部入队 + 写线程排空 → barrier → 串行释放 → 下一 chunk。
*备选*:全程无 barrier 流水线 → 与释放期的 ConstStorage 竞态冲突,否。

### D2 · FTS trigram 多分片(M 库并行分词)
SQLite 一个库只允许一个 writer → 要并行分词必须多库。`code_fts` 分 M 片(`fts/shard-<i>.db`),路由 `clsId % M`,M 个写线程各持一片的 WAL 写连接并行分词+插入。
- **M=8**(贴合 `ATTACH` 默认上限 10:主库+≤9 片;22 核留足反编译余量)。抖音每片 ~39k 类。
- 查询:`code_fts MATCH` fan-out 到 M 片(每片更小更快)→ 收候选 `cls_idx` 并集 → 关联主库 `classes` 取 fqn → **现有层一过滤/排序/limit 不变**。`ATTACH ... UNION` 或逐片读连接 + Java 合并(M>9 时用后者)。
- `const_strings`/`string_fts`:string_fts 也是 trigram;按 task 0 拆分结果决定**一并分片**还是留主库(若扫字符串移出锁后字符串侧已不卡,可留主库)。
*备选*:自建 mmap trigram 索引(Zoekt 式,分词完全并行)——终极但工程量大,`headless-jadx-mcp` 设计里本就"暂缓";本变更不做。

### D3 · `extractStringLiterals` 移出 `writeLock`
纯 CPU 全文扫描,现在白占着写锁。挪到反编译线程侧(生产者),与 getCode 一起并行。零质量代价、零风险。

### D4 · `structure+usage` 批量插入 + 与 phase-3 重叠
- 批量:`SymbolGraph`/`SqliteExportVisitor` 改大事务 + 预编译批量 `addBatch`(目标从 2.3万 → ≫10万 边/s)。
- 重叠:structure/usage 导出是 **SQLite 写 + 图遍历**,phase-3 反编译是 **CPU**——不同资源,可并发(usage 写主库,FTS 写分片,互不抢同一 writer)→ 总时间 ≈ max 而非 sum。需确认顺序依赖(phase-3 的 cls_idx 映射依赖 structure 已注册类;可先跑 structure 的类注册、再并发 usage 导出 ∥ phase-3)。

### D5 · 质量恒定 + T3 可配减量(通用)
- 反编译 `JadxArgs` 恒为现状(AUTO/RESTRUCTURE + debug-info + source-name ALWAYS + kotlin-metadata)。**不**引入降级模式。
- 通用减量杠杆 = **T3 具名三方可配跳过**(沿用 `AnalysisScope` 分层;T4 已默认跳)。`--index-tier`/`--no-index-third-party` 之类开关;默认是否含 T3 待定(抖音 T3 占比小,普通 app 三方多则收益大)。
- sub-floor 的通用安全杠杆 = **加核**(线性);写进结论,不写进代码。

### D6 · M / chunk / 队列容量 可调
`JADX_INDEX_SHARDS`(M,默认 8)、`JADX_INDEX_CHUNK`(默认 4000)、队列字节上限(默认 ~50MB)、`JADX_INDEX_THREADS`(反编译并行,默认 cores)——env 可调,供 task 0/5 实测寻优。

## Risks / Trade-offs

- **查询合并复杂度**:`search_in_code`/`search_string_constants` 要跨 M 片 fan-out + 合并候选 → 改 `CodeSearchIndex` 查询层;合并后再走层一过滤/排序,需保证 limit 语义不变。
- **resume/coverage 跨分片**:`indexedRowids()` 要 union M 片;`coverage_complete` 要全片完成;`meta.index_scope` + schema_version 仍管复用。
- **结构∥phase-3 重叠的依赖与争用**:usage 导出与 phase-3 都在 load 后;需厘清"类注册"先行,且两者写不同库(主库 vs 分片)避免 writer 冲突。
- **分片不均**:`clsId % M` 一般均匀;极端可按 cls 大小加权(暂不做)。
- **WAL 伴随文件**:M 片运行期 ×3(.db/-wal/-shm)→ 归拢 `fts/`,close 时 checkpoint。
- **8min 可能本就低于地板**:若 task 0 测出地板 > 8min,需如实告知"22 核 + 全质量 + 全量"下 8min 不可达,给加核/减类的量化路径——不靠降质量硬凑。

## Open Questions（task 0/5 实测回填）

- **纯反编译地板速率**(类/s,22 核,NoOp cache):决定 8min 可达性。
- `indexCode` 14.7ms 的 **FTS 分词 vs 扫字符串** 拆分:决定 D3 单独能拿多少、string_fts 是否需分片。
- **M 的最优值**(8 vs 12 vs 16)与查询 fan-out 的合并开销实测。
- structure 批量化后的真实边插入速率,及与 phase-3 重叠的净收益。
- 默认是否索引 T3(通用性 vs 速度)。

## 数据支撑（基线 + task 0 实测）

已测(`analysis-value-code-search` 会话):抖音 phase-3 ~68 类/s、CPU ~17%(3.7/22 核)、`indexCode` 串行 ~14.7ms/类、反编译 ~40ms/类(反推)、structure+usage ~21.5min/29.5M 边、磁盘 ~2.9MB/s。

### task 0 SPIKE 实测（`--bench-decompile`,抖音 v34.1.0,22 核,全质量 AUTO/RESTRUCTURE,limit=20000 主包优先,3 趟)

| 趟 | 配置 | 反编译速率 | load |
|---|---|---|---|
| 1 | DECOMPILE-ONLY (NoOpCodeCache) | **227.8 类/s** | 112s |
| 2 | +DISK (DiskCodeCache 写 .java) | 240.0 类/s | 109s |
| 3 | +FTS(旧 单库串行 `indexCode`) | 184.5 类/s | 110s |

- **趟2/趟1 = 1.05**:磁盘**不是**瓶颈(SSD,异步写,SQLite 不 fsync)。✔ 证实 design 假设。
- **趟3/趟1 = 0.81**:旧 单库串行 FTS 在**干净条件下**只拖慢 ~19%(远没有满载——`indexCode` 拆分见下)。
- **`indexCode` 拆分(task 0.2)**:FTS trigram 分词 = **2.54ms/类**;`extractStringLiterals` + const_strings 插入 = **0.07ms/类**。即:索引串行成本里**分词占 97%,扫字符串可忽略**。
  - ⇒ task 2.5 定论:**`const_strings`/`string_fts` 无需为性能分片**(扫字符串移出锁后 0.07ms 已无足轻重);本实现仍把它们随 `code_fts` 一并分片,纯为「每片自包含、写连接零跨片争用」的连接模型整洁,非性能必需。
  - ⇒ 干净条件单类索引 ~2.6ms,**远低于全量构建观测的 14.7ms**——那 14.7ms 是全量(312k 类 + usage 图驻堆 + 近 20GB 堆压 + 22 线程挤一把锁)下被 **GC/堆压 + 锁争用**放大的,不是分词本身。

### 地板与 8min 裁定（task 0.3,决定性）

- **纯反编译地板 = 227.8 类/s**(22 核,全质量,NoOp、无 FTS、无 index)。
- `floor = inScope / rate`:抖音 in-scope ≈ 312,498 类 → **312498 / 227.8 ≈ 1371s ≈ 22.9 min**(纯反编译,未计 ~1.8min load)。
- **裁定:8min 在 22 核 + 全质量 + 全量(~312k)下物理不可达**——纯反编译一项就要 ~23min,软件并行无法突破反编译 CPU 地板(D0 已预案)。
  - 全量 8min 需 **加核**:8min 留 ~6min 反编译 → 312498/360s ≈ 868 类/s → 需 ≈ **84 核**(227.8 类/s 是 22 核,线性外推 868/227.8×22)。
  - 或 **减类**(T3 可配跳,task 4.1):8min 上限 ≈ 6min×227.8 ≈ **~82k in-scope 类**(留 load/结构余量则 ~70–90k)。抖音 T3 占比小,收益有限;三方多的普通 app 收益大。
- **本变更的真实目标 = 贴近 ~23min 反编译地板**(从 baseline ~100min → 估 ~25–30min,4× 提升),而非 8min。提速来自:并行分片分词(消除趟3 的 19% + 全量下的锁争用)、deferred 索引(结构phase 提速)、有界队列(控堆,避免全量 GC 抖动把 227→68)、phase-3 前清 use-in(已在 baseline)。
- **峰值堆**:bench 三趟累计峰值 **19.67 GB**(单趟 312k 模型 + 20k 反编译工作集已逼近 20GB)。⇒ **D4 重叠(usage∥phase-3)默认必须 OFF**:重叠期 use-in(~4.6GB)与 phase-3 IR 共存会破 20GB。本实现 `JADX_INDEX_OVERLAP` 默认关,task 5 再实测能否在某些 APK 上安全开启。

### task 5 验证实测

**结构批量速率(3.1 deferred-index)**:抖音 structure+usage = **126.7s**(4.56M 符号 / 29.5M 边)= **~23.3万 边/s**,对比 baseline 2.3万/s = **~10× 提速**,远超 ">10万/s" 目标。根因:baseline 把 edges/symbols 索引(含 symbols.dex_id UNIQUE)建在前,逐条插入维护索引 = bulk-load 杀手;改为 `Db.createGraphIndexes` 在 bulk load 后一次性建,即得 10×。

**抖音从零全量(5.1)**:三次冷构建实测,phase-3 在 ~70% 触发低堆 backstop(可恢复 PARTIAL,非崩溃):

| 跑 | 配置 | 覆盖 | 峰值堆 | 端到端 |
|---|---|---|---|---|
| 1 | backstop 1/8, chunk 4000 | 187k/312k (60%) | 19.3GB | 1043s |
| 2 | backstop 1/16, chunk 4000 | 227k/312k (72%) | 20.3GB | 1150s |
| 3 | + writePool 有界, chunk 2000 | 223k/312k (71%) | 20.0GB | 1250s |

- **关键诊断**:chunk 4000→2000 与 writePool 有界**都没提升覆盖**(72%→71%)→ 瓶颈**不是**单 chunk 瞬态 IR、**不是**写盘积压。冷构建能做 ~222k 才触顶 → **堆随已反编译类数单调增长(~22KB/类)**:jadx 惰性模型在 `unload()` 后仍残留每类解析元数据(类型/方法节点等),反编译 N 类 → 残留 ∝ N。这是**单会话全质量反编译 312k 类的固有堆上限**,与 analysis-value 既有的「堆有界 + reload 续建」行为同源,**非本变更新引入**(已上的重排/flush/checkpoint/temp_store/backstop 修复降低了 RAM 压力、把 60%→72%,但消不掉单调残留)。
- **续建闭合(已实测)**:reload FIP3 → RESUME 路径跳过 structure、`indexedRowids` 跨 8 片 union 222k 全跳过、只反编译剩余 ~90k → **`COMPLETE (312498/312498) coverage_complete=true`,峰值 19.2GB(<20GB)**。task 2.4 跨分片 resume ✔。即抖音全量 = **冷构建(~21min/71%)+ 1 次续建(→100%)**,**总 ~47min vs baseline ~100min ≈ 2.1×**;非单轮——这是 20GB 硬约束 + 全质量 + 312k 的诚实结果(8min/单轮均非 22 核全量可达,见 D0)。phase-3 早段贴近地板(~300/s),近尾随堆变满 GC 压力降到 ~64/s(每类元数据残留),故端到端未达纯地板的 ~23min。
- **修复清单**(降 RAM 压力、提升单轮覆盖):① `releaseHeapUseIn` 重排到 `createGraphIndexes` 前;② 每 chunk `csi.flush()` + 分片 `wal_checkpoint(TRUNCATE)`;③ writer/分片 `temp_store=FILE`;④ backstop `free<1/8` GC、`free<1/16` 才停;⑤ `DiskCodeCache` writePool 有界 + CallerRuns 反压(防快流水线下写盘积压)。

**通用性回归(5.2)**:
- **`search_in_code` 跨分片正确性**:selftest 断言全 **PASS**——默认查询 `stdlibHits=0 dupClasses=0`、`include_libs ≥ default`、`get_class_source` 正常。跨片 fan-out + 主库 fqn 合并语义与单库一致(每类一片,union 即全集,无丢无重)。✔
- **`get_class_source` 逐字节**:抽样 400 个同名 .java(旧 analysis-value 缓存 vs 本变更缓存)20 个有差异。**逐一核查**:差异是 jadx 的 **ConstStorage 跨类常量解析顺序**(例:`127` vs `NitaOptAB.OPEN_ALL`——同值,语义等价),取决于反编译/释放**顺序**(并行 + cold/resume 分块边界),**run-to-run 非确定**。**本变更不触碰反编译路径**(`JadxArgs`/`getCode`/codegen/`releaseClass` 与 analysis-value 逐字节相同)→ 这些差异是 **analysis-value 的 per-chunk ConstStorage 释放固有的非确定性**(baseline 自身重跑也会出现),**非 fast-index-pipeline 引入**。结论:本变更**零质量代价**(不改反编译/不引入降级);"逐字节一致"对该设计本就非稳定属性(常量释放使其顺序敏感),但提速侧零新增差异。

**M 扫描(5.3)**:task 0 已测 FTS 分词 2.54ms/类 → 8 写线程容量 = 8/0.00254 ≈ **3150 类/s ≫ 反编译地板 227 类/s**。即 **M=8 已远超饱和,写侧非瓶颈**;M=12/16 不会再提速(瓶颈是反编译 CPU,非分词)。故 **M=8 即最优**(且贴合 ATTACH 上限、给反编译留核)。bench M-scan 作确认(可选)。

## 复盘:真正的反编译阻塞(post-apply 根因,2026-06-09)

> 用户对"全量 ~46min + 2 次重建"不满,要求从源头定位反编译阻塞。复盘 jadx 源码后发现:**本变更(及原 design)的瓶颈诊断不完整,227 类/s 不是反编译 CPU 地板,而是"朴素 parallelStream 的锁争用地板"**。下列两条根因均**未在本次实现里处理**,记录供后续 change。

### 先证伪原诊断(FTS 锁不是主瓶颈)
task 0 自身数据:趟1 纯反编译 227.8 类/s vs 趟3 +旧单库串行 FTS 184.5 类/s ——**去掉 FTS 锁只快了 23%**。若 FTS 锁是主瓶颈,应快数倍。⇒ 真正阻塞在**反编译本身**,而原 design 把 227/s 当硬地板、据此裁定"8min/23min 不可达"——**该地板前提是错的**。

### 根因①(速度):并行反编译撞"依赖锁"
`ProcessClass.generateCode(cls)`(jadx-core,反编译必经):先 `for dep in cls.getDependencies(): process(dep, false)` 再 `process(cls, true)`;而 `process()` 第 53 行 `synchronized (cls.getClassInfo())`、`ClassNode.decompile()` 本身也 `synchronized`(:381)。
- 我的 `chunk.parallelStream().forEach(getCode)` = 任意 4000 类随机分 22 线程。抖音内聚度高、海量类共享依赖(公共基类/工具/框架壳)→ 线程 A 反编译 C 时 `process(D)` 持有 D 的 `ClassInfo` 锁,线程 B 反编译 G 也要 `process(D)` → **撞锁阻塞**。这正解释 design 观测的"CPU ~17%(22 核 ~3.7 核)"——**不是 CPU 打满,是等锁,有效 ~4 核/22**。
- jadx `save()` 早已用 `DecompilerScheduler` 规避(本变更**完全没用**):依赖感知批处理——每个类 + 其依赖打包给同一线程顺序处理(依赖不跨线程抢锁)+ 低依赖类排前面(等高依赖类轮到时其依赖已 `PROCESS_COMPLETE`,`process()` 第 48 行直接 return 不进锁)。`save()` 用 `decompileScheduler.buildBatches()` + `executor.addParallelTasks()`(JadxDecompiler.java:402)。
- **修法**:索引 phase-3 改用 `DecompilerScheduler.buildBatches` + 每 batch 一线程顺序反编译(替掉朴素 parallelStream)。质量中立(就是 jadx 自己的并行方式)。**预估 2–4×**(取决于抖音依赖密度,需实测)。

### 根因②(2 次重建):out-of-scope 依赖 IR 泄漏
`generateCode` 里 `process(dep, false)` 把每个依赖 **load + 建满 PROCESS_STAGE IR(~100KB/类)但不 codegen、不 unload**(unload 仅在 `if(codegen)` 分支,:90)。
- 索引 in-scope 类 C 会拉起它的 out-of-scope 依赖(`android./kotlin./androidx.` 等 T4)的满 IR;我的 `releaseClass(C)` **只 unload C**,不碰这些被拉起的依赖 → 222k 个 in-scope 类陆续拉起数万 stdlib 依赖 IR,**永不释放 → 堆单调涨到 20GB 顶 → 被迫第 2 次重建**。`save()` 无此问题(它把**所有**类都 codegen+unload);本变更故意只做 in-scope,反让依赖 IR 漏了。这才是 5.1 "~22KB/类残留"的真身(更准确说是"每个唯一 out-of-scope 依赖一份 IR")。
- **修法 A**:chunk 边界周期性 unload 所有"已 LOADED 但不 in-scope"的依赖类(代价:被共享的依赖可能重处理,DecompilerScheduler 批处理可减此重复)。
- ~~**修法 B**:调大 `-Xmx`~~ —— **已被用户否决(2026-06-09):内存不调大,保持 `-Xmx20g`**。故根因② 只能走**修法A(周期 unload out-of-scope 依赖)**降低残留,而非靠加内存装下;若修法A 仍不足,则 2-次-重建是 20GB 硬约束下的既定结果。本轮 spike(§task 6)只测根因①(速度),②的处置待①结论后另议。

### 与"8min 不可达"裁定的关系
原裁定基于 227/s 是硬地板。若根因① 实测证实是锁争用(非 CPU-bound),打满 22 核后 312k/(227×N) 可能重回 8min 量级(N=有效核倍数)。**故"8min 不可达"需在 spike 后重判**,不应作为定论。

### 量化 spike(改之前先测)
| 测什么 | 方法 | 决定 |
|---|---|---|
| 反编译 CPU-bound 还是锁-bound | bench 采 CPU% / 线程栈采样看 BLOCKED 占比 | 是否值得改调度 |
| Scheduler vs parallelStream 吞吐 | 同 20k 类两法各跑比 类/s | 提速倍数 |
| 残留是否 = 依赖 IR | 222k 处 jmap 看 top retained 是否 out-of-scope ClassNode | 确认堆修法 A |
| -Xmx 调大能否单次跑完 | `-Xmx28g` 重跑 | 验证修法 B |
