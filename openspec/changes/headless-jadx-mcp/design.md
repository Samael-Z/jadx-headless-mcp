## Context

为 RE 重做一个**无头常驻 jadx + MCP 服务**,取代/超越早期 `jadx-headless-mcp`,且不依赖 GUI(区别于 `jadx-ai-mcp`)。本文档的设计选择已通过对真实大型 APK 的 **spike 实测** + 对索引机制 / MCP SDK / jadx 扩展点的**专项调研**验证(见各决策"依据")。

**现状约束(已核实):**
- `jadx-ai-mcp` 是 GUI 插件:`init()` 在 `getGuiContext()==null` 时退出;路由经 `MainWindow.getWrapper()`,但实际 ~90% 仅用 `JadxDecompiler`(只有 `/current-class`、`/selected-text` 真用 Swing 状态)。
- `jadx-gui` 的"搜索"**不是索引**:`CodeSearchProvider` 逐类实时反编译并 substring 扫描(`SearchJob`/`ISearchProvider`),且强耦合 `MainWindow`;jadx-core 内也无搜索索引设施。→ 不可作为"可复用的索引"。
- **jadx-core 提供 usage/xref 的可插拔 SPI**:`JadxArgs.setUsageInfoCache(IUsageInfoCache)`(默认 `InMemoryUsageInfoCache` 在堆内);`IUsageInfoData.visitUsageData(IUsageInfoVisitor)` 可遍历导出整张 usage 图;`JavaClass/Method/Field.getUseIn()` 是查询面。jadx-gui 已有磁盘实现 `cache/usage/UsageInfoCache`(存盘 `UsageFileAdapter` + 用时载入内存)。→ **D7 出堆改造有干净扩展点,无需改 jadx-core。**
- `jadx-core` 默认:`useSourceNameAsClassNameAlias = NEVER`、`deobfuscationOn = false`、`codeCache = InMemoryCodeCache`(无界)、`DEFAULT_THREADS_COUNT = availableProcessors()/2`。GUI 与 CLI 共享这些默认(`JadxSettingsData extends JadxGUIArgs extends JadxCLIArgs`)。
- CLI 是一次性批处理(load→反编译→写盘→退出),不能托管常驻 HTTP 服务。

**实测基线(commit `8c28a853`,`-Xmx20g`,抖音 281 MB / 55 dex,`--single-class`):**
| 配置 | 耗时 | 峰值堆 | 峰值 RSS | OOM |
|---|---|---|---|---|
| source-name=always + kotlin-metadata(基线) | 254s | 12.7 GB | 16.2 GB | 否 |
| 基线 + `--deobf` | 246s | 13.9 GB | 16.4 GB | 否 |
- DiDi(126 MB / 24 dex)实测:`--use-source-name-as-class-name-alias always` 把 `i0`→`TextUtil2`、`l0`→`LoginService` 等几乎全恢复;`--deobf` 仅加 `m<num>`/`p<num>`/`C<num>` 稳定别名(噪音),不恢复语义。
- 抖音重混淆:`X.C11FU.LIZ()` 类/方法名 source-name/kotlin 都救不回;但代码里字符串字面量丰富(`"launch_app"`、`"cold_boot_splash_duration"`…),xref 完整。
- **常驻堆探针(库化,GC 后 retained heap)**:full(默认带 usage)= 319,263 类 / **~9.8 GB** / load 206s;nousage(禁 `UsageInfoVisitor`)= 456,333 类 / **~5.2 GB** / load 52s。→ ① 稳态常驻堆 ~10 GB(低于 12.7 GB 峰值),20G 实际余量更宽;② usage 是堆大头(full−nousage ≈ 4.8 GB,且 nousage 类数反而更多),**出堆可近乎腰斩常驻堆**;③ 注意混淆:禁 `UsageInfoVisitor` 同时关掉了依赖它的类合并(故 319k↔456k、206s↔52s),4.8 GB 含级联、非纯 usage 存储。抖音有效类数 ≈ **319k**。

## Goals / Non-Goals

**Goals:**
- 单进程 Java 服务:`jadx-core` + MCP(官方 Java SDK,Streamable HTTP),无内部 HTTP 桥、无 Python。
- 加载任意 APK(含抖音),所有提炼工具稳定可用,单次调用 ≤ 60s(加载可久),≤ 20 GB。
- 命名恢复达到 GUI 同等可读性;`search_in_code` 用持久化索引(空间换时间)。

**Non-Goals:**
- 不改 `jadx-core` 源码 / 构建。
- 不做 GUI、不做 smali 调试器、不保留 `selected-text`/`current-class`。
- 不追求恢复重混淆 app 的"原始"名字(信息已删除,只能部分恢复 + 靠字符串/xref)。

## Decisions

> 决策状态:D1/D4/D6/D7/D8 经交互拍板 + spike/调研验证为**已定**;其余为既有设计。仍开放项见末尾。

**D1:架构 = 单进程 Java(Design 2),MCP = 官方 Java SDK + Streamable HTTP,仅 localhost。**【已定】 无历史 GUI 包袱、无跨语言/跨进程一跳、延迟低、单语言维护。
- SDK:官方 `io.modelcontextprotocol.sdk:mcp`(非 Spring,核心自带 server 传输);传输用 **Streamable HTTP**(SSE 规范自 2025-03-26 起已弃用,新建远程服务勿用 SSE),经 `HttpServletStreamableServerTransportProvider` + 内嵌 servlet 容器(如 Jetty)。
- 工具处理器用**同步 facade**(jadx 访问本就阻塞,简单)。
- 端点**绑 `127.0.0.1`、无鉴权**(面向本机 LLM 客户端;需远程走 SSH 隧道)——避免明文 HTTP 无鉴权暴露 `rename_*`/读源码。
- 依据(调研):官方 Java SDK 核心模块无需 Web 框架即提供 STDIO/SSE/Streamable HTTP server 传输;2026 年 Streamable HTTP 为远程推荐传输。备选"两进程复用 Python jadx-mcp-server"被否。

**D2:常驻 + 惰性 + 有界缓存。** `load_apk` 一次性构建 `RootNode` + 全类骨架(久,但一次);`JavaClass.getCode()` 按需逐类反编译;`JadxArgs.setCodeCache(...)` 用**磁盘/有界 LRU**(禁用无界 `InMemoryCodeCache`)。依据:抖音**稳态常驻堆 ~9.8 GB**(峰值 12.7 GB / RSS 16 GB),20G 留约 ~10 GB 给 code cache/索引/工作集;但仍是绑定约束 → 缓存必须可淘汰/落盘,叠加 D7 出堆后余量更舒适。

**D3:工具按"是否反编译"分三层(锁 60s)。**
- Tier-1 瞬时:结构枚举、字符串池搜索、xref/调用图(查 D6/D7 的 SQLite 符号图)、资源、rename。
- Tier-2 快:单类惰性反编译(`get_class_source`/smali)。
- Tier-3 慢:`search_in_code` → 查 SQLite FTS5 trigram 索引;首次构建异步 + `index_status` 进度。
砍掉 GUI 专属与 `debug_*`。

**D4:命名恢复 = source-name(ALWAYS)+ kotlin-metadata;deobf 默认关。**【已定】 实测:可读性来自前两者(已默认/已开),deobf 对重混淆 app**不增反降**(`com.ss`→`com.p676ss`、`X`→`p003X`)。deobf 设为可选旋钮。

**D5:cache/index 键 = dex 稳定身份,不是显示名。** 原始类型描述符 / dex 索引天然跨运行稳定;显示名(source-name/kotlin/deobf/rename)是挂在稳定键上的元数据层。好处:改命名设置/改名**不毁结构索引**,只刷新显示层;彻底绕开"deobf 为稳定名"的纠结(实测见 DiDi 字段 `f47598p`→`f52523p` 的显示名漂移)。

**D6:索引 = 统一本地 SQLite(符号/边图谱 + FTS5 trigram),由 jadx 喂数据。**【已定】
- 借鉴 `colbymchenry/codegraph` 的 `symbols·edges·files·FTS5` 模型,但**前端用 `jadx-core` 取代 tree-sitter**(我们解 DEX 不是源码;jadx 的 dex 级符号/边/xref 比再解析反编译文本更准),**在 JVM 内实现、不复用其 Node 代码**。
- 一个本地 SQLite(WAL):① **符号 + 边图谱**(类/方法/字段 + 调用/xref/继承)服务 Tier-1 结构/xref/子类;② **FTS5 `trigram` tokenizer** 索引反编译文本服务 `search_in_code`(经典"trigram 取候选 → 精确匹配"模型)。
- 键用 dex 稳定身份(D5);**FTS5 external-content** 指向已有磁盘 code cache,**不重复存**文本。
- **存储位置 = 固定缓存目录 `E:\JADX_CACHE_DIR\<apk-hash>\`**(code cache / SQLite 索引 / usage 图谱都放这,不放 APK 同目录)。
- **后台构建 + `index_status` 进度**(load 后台起;建完前 `search_in_code` 返回"索引中 X%/部分结果");**流式构建**(解→落盘→入库→释放)保证 < 20 GB;按 APK 哈希**跨重启复用**。
- **全正则**(超出 trigram 子串能力,或 FTS5 trigram `<3` 字符退化)用 **ripgrep over 磁盘 code cache** 兜底。
- 依据(调研):trigram 是代码子串/正则搜索的业界标准(Google Code Search / Zoekt / Cursor);IntelliJ 用**持久化 trigram**(LMDB,出堆、重启零延迟、查询 0.01–0.63 ms);SQLite FTS5 trigram 子串 ~1.75s/1820 万行(我们语料远小,亚秒级);`jadx-gui` 无索引(实时扫描)故不可复用。

**D7:xref/usage 经 jadx SPI 导出进 SQLite(出堆),缓解 20G。**【已定,spike 已量化:高价值】
- 扩展点(已核实,**无需改 jadx-core**):用 `JadxArgs.setUsageInfoCache(IUsageInfoCache)` 接管 usage 存储;在自定义 `IUsageInfoData` 里用 `visitUsageData(IUsageInfoVisitor)` 把整张 usage 图(类/方法/字段 use-in)**导出进 SQLite**;`get_xrefs_*`/`get_call_graph`/`get_subclasses` 直接查 SQLite,**不走** jadx 在堆内的 `getUseIn()`。
- 收益(spike 实测):usage 是常驻堆大头——抖音 full 9.8 GB vs nousage 5.2 GB(差 ~4.8 GB,含依赖级联),出堆可省**数 GB、近乎腰斩常驻堆**。xref 变出堆 SQL 查询 + 跨重启复用。codegraph 启发;jadx-gui 磁盘 `UsageInfoCache` 是持久化先例。
- 实现关键:**不能简单禁用 `UsageInfoVisitor`**(探针实测会破坏匿名/合成类合并,类数 319k→456k、产出错误)。须**保留 usage 计算**,计算后导出 SQLite 再**释放堆内 use-in 列表**;真实可省量(扣除级联)在实现期精确测。

**D8:实现落点 = 兄弟项目 `E:\DEV\headlessJADX\jadx-headless-mcp-v2`,依赖发布版 jadx artifacts。**【已定】 在 `E:\DEV\headlessJADX\jadx-headless-mcp-v2` 新建独立项目,依赖 Maven 发布的 `io.github.skylot:jadx-core` / `jadx-dex-input` / `jadx-kotlin-metadata`;不碰 vendored 上游树,升级 jadx 只改版本号;与 jadx-ai-mcp 现有做法一致。**注意**:该目录在本 openspec 变更的 `allowedEditRoots`(jadx 仓)之外 → apply 时需把编辑范围放开到该兄弟目录(或在该处单独初始化)。

## Risks / Trade-offs

- **[内存绑定:20 GB 仅 ~3.5 GB 富余]** → 磁盘/有界 code cache + 流式索引构建 + 索引/图谱/usage 出堆(D6/D7)为强制项;监控峰值;必要时支持调高 `-Xmx`。
- **[deobf 内存/可读性]** → 实测 deobf 不 OOM(+1.2 GB)但损可读性 → 默认关、可选开。
- **[首次索引构建耗时长(抖音全量反编译一次)]** → 后台构建 + `index_status` 透明化;键化复用使其只付一次。
- **[SQLite FTS5 trigram 不支持原生正则;`<3` 字符查询退化为扫描]** → 子串/字符串走 FTS5;**全正则用 ripgrep over code cache 兜底**;`<3` 字符对代码搜索影响可忽略。
- **[xref/usage 出堆改造]** → 扩展点已确认(SPI,无需改 core);需 spike 量化减堆收益与查询延迟。
- **[Streamable HTTP 并发]** → 官方 SDK 支持并发工具调用;我方工具处理器须线程安全(读多;rename 写加锁;SQLite 写串行化 / WAL)。

## Open Questions

**已解决(本轮拍板/调研):**
- 索引技术 → **SQLite 符号图 + FTS5 trigram(借鉴 codegraph,jadx 喂数据)+ ripgrep 兜底**(D6)。
- 索引构建时机 → **后台构建 + `index_status` 进度**(D6)。
- 实现落点 → **兄弟项目 + 发布版 jadx artifacts**(D8)。
- 网络/鉴权 → **仅 localhost、无鉴权**(D1)。
- MCP SDK / 传输 → **官方 `io.modelcontextprotocol.sdk:mcp` + Streamable HTTP + 内嵌 servlet**(D1)。
- cache/index 存储位置 → **固定目录 `E:\JADX_CACHE_DIR\<apk-hash>\`**(D6)。
- xref 出堆扩展点 → **`setUsageInfoCache` + `visitUsageData` 导出 SQLite,无需改 core**(D7)。
- xref 出堆价值 → **spike 实测高价值**(usage 占常驻堆大头,出堆可省数 GB),纳入 D7 必做(D7)。
- `search_in_code` 引擎 → **FTS5 trigram 足够 + ripgrep 兜底正则**;自建 mmap trigram **暂缓**,触发升级条件:需 <100ms 交互级延迟,或大规模原生正则超出 ripgrep 兜底(D6)。

**仍开放(实现期处理,非阻塞):**
- xref 出堆的**精确可省量**:实现"计算 usage→导出 SQLite→释放堆内 use-in"后实测(spike 已确认方向与量级,精确数留实现期)。
- 各 Tier 工具在抖音上的**端到端 60s 验证**(apply 后集成测试,见 tasks 6.x)。

## Sources(调研依据)

- MCP 官方 Java SDK(非 Spring,Streamable HTTP server 传输): https://github.com/modelcontextprotocol/java-sdk ; 文档 https://java.sdk.modelcontextprotocol.io/latest/ ; SSE 弃用→Streamable HTTP: https://blog.fka.dev/blog/2025-06-06-why-mcp-deprecated-sse-and-go-with-streamable-http/
- Russ Cox — Regular Expression Matching with a Trigram Index: https://swtch.com/~rsc/regexp/regexp4.html
- Trigram-Based Persistent IDE Indices with Quick Startup(IntelliJ,arXiv 2403.03751): https://arxiv.org/html/2403.03751v1
- Cursor — Fast regex search: indexing text for agent tools: https://cursor.com/blog/fast-regex-search
- SQLite FTS5(trigram tokenizer): https://sqlite.org/fts5.html ; 性能实测: https://andrewmara.com/blog/faster-sqlite-like-queries-using-fts5-trigram-indexes
- colbymchenry/codegraph(SQLite + FTS5 + 符号/边图谱模型): https://github.com/colbymchenry/codegraph
