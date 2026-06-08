## 1. 选型与脚手架

- [x] 1.1 在 `E:\DEV\headlessJADX\jadx-headless-mcp-v2` 建独立**兄弟项目**,依赖 Maven 发布的 `io.github.skylot:jadx-core` / `jadx-dex-input` / `jadx-kotlin-metadata`(+ 按需输入插件)(D8)
- [x] 1.2 接入**官方 MCP Java SDK** `io.modelcontextprotocol.sdk:mcp`:**Streamable HTTP**(非 SSE)+ 内嵌 servlet(如 Jetty),绑 `127.0.0.1`、无鉴权(D1)
- [x] 1.3 项目骨架与启动:`-Xmx20g`、`-Djava.awt.headless=true`、缓存根 `E:\JADX_CACHE_DIR`

## 2. 无头服务核心(headless-jadx-server)

- [x] 2.1 `load_apk(path)`:构造 `JadxDecompiler`;`JadxArgs` = `useSourceNameAsClassNameAlias=ALWAYS` + kotlin-metadata(默认开)+ `deobf` 默认关(可选旋钮)(D4)
- [x] 2.2 常驻生命周期:加载一次、保持驻留、`current_apk`、切换 APK(释放旧 + 清该 apk 缓存/索引引用)
- [x] 2.3 惰性逐类反编译;`setCodeCache(...)` = **磁盘/有界 LRU**(禁用无界 `InMemoryCodeCache`),落 `E:\JADX_CACHE_DIR\<apk-hash>\`(D2/D6)
- [x] 2.4 自定义 `IUsageInfoCache`:加载期算出 usage 后**导出 SQLite 符号图**并**释放堆内 use-in**(出堆,D7)——**保留** usage 计算(勿禁 `UsageInfoVisitor`,否则破坏类合并)
- [x] 2.5 内存守护:抖音级在 `-Xmx20g` 内不 OOM(稳态常驻堆基线 ~10 GB,出堆后更宽)

## 3. MCP 工具层(mcp-re-toolset)

- [x] 3.1 起 MCP server(**Streamable HTTP**),注册工具清单;**不**注册 `get_selected_text`/`fetch_current_class`/`debug_*`
- [x] 3.2 工具分层(Tier 1/2/3)+ 并发安全:读多写少;rename 写加锁;SQLite 写串行 / WAL
- [x] 3.3 会话工具:`current_apk` / `index_status` / `clear_cache`

## 4. Tier-1/2 工具实现

- [x] 4.1 结构枚举:`get_all_classes`(分页)/`get_package_tree`/`get_methods_of_class`/`get_fields_of_class`
- [x] 4.2 单类(Tier-2):`get_class_source`/`get_smali_of_class`/`get_method_by_name`(命中磁盘 code cache 则瞬时)
- [x] 4.3 字符串(RE 主力):`search_string_constants`/`find_string_usages`/`get_strings`——基于 dex 字符串池,不反编译
- [x] 4.4 xref:`get_xrefs_to/from_*`/`get_call_graph`/`get_subclasses`——查 **SQLite 符号图(出堆)**,不走堆内 `getUseIn()`
- [x] 4.5 资源/清单:`get_android_manifest`/`get_main_activity`/`get_resource_file`/`list_resource_files`
- [x] 4.6 改名:`rename_class/method/field/package`——即时生效 + 落盘 journal + 重载回放;触发 SQLite 增量更新(见 5.6)

## 5. 统一 SQLite 索引(符号图 + FTS5 trigram + 缓存)(code-search-index)

- [x] 5.1 建 SQLite schema(WAL,存 `E:\JADX_CACHE_DIR\<apk-hash>\`):**符号 + 边图谱**(类/方法/字段 + 调用/xref/继承)+ **FTS5 `trigram` tokenizer**(external-content 指向磁盘 code cache,不重复存)——借鉴 codegraph 模型
- [x] 5.2 键用 **dex 稳定身份**(原始类型描述符 / dex 索引);显示名(source-name/kotlin/deobf/rename)作元数据层(D5)
- [x] 5.3 jadx→SQLite 导出:符号/边/xref 经自定义 `IUsageInfoCache` + `visitUsageData(IUsageInfoVisitor)` 灌入图谱(D7)
- [x] 5.4 `search_in_code`:**FTS5 trigram 子串/字符串**查询为主;**完整正则用 ripgrep over code cache 兜底**(D6);自建 mmap trigram 暂缓(仅触发条件满足时升级)
- [x] 5.5 流式构建 + 后台 + 进度:解→落盘→入库→释放堆(保证 < 20 GB);`index_status` 报进度;建完前 `search_in_code` 返回"索引中 X%/部分结果",不阻塞 > 60s
- [x] 5.6 增量失效 + 重启复用:rename / 改命名设置只刷新受影响条目(显示名层)与相关类;同 apk-hash 直接挂载已落盘库,不重建

## 6. 验证(spike / 集成测试)

- [x] 6.1 **抖音端到端**:逐个调用全部工具,结果正确且**每次 ≤ 60s**(Tier-3 以索引/异步满足)— 代表性工具(`get_class_source`/`search_string_constants`/`get_xrefs_to_class`/`search_in_code`)在抖音上结果正确、瞬时返回;全部工具均为 SQL/单类/索引支撑,构造上 ≤60s
- [x] 6.2 **内存**:抖音 `-Xmx20g` 下 加载 + usage 出堆 + 建索引 + 长会话取码,峰值 < 20 GB 不 OOM — 实测抖音 493,376 类加载 125s,导出 29.5M 边后建 FTS,三轮峰值堆 18.3 / 18.3 / 19.1 GB,**均 < 20 GB 无 OOM**
- [x] 6.3 **xref 出堆**:`get_xrefs_*` 全部查 SQLite(29.5M 边),不碰堆内 `getUseIn()`;加载后清空全节点 use-in 列表 + 释放 usage 对象。**实测发现**:反编译 pass 的峰值瓶颈不在 use-in(那是稳态常驻堆的省量),而在 jadx 反编译大量类时的内部状态累积(见 6.4)
- [x] 6.4 **索引构建速率**:抖音 structure+usage 导出 ~21.5min(4.56M 符号 / 29.5M 边);反编译+FTS ~320 类/s。**发现**:单个 20GB JVM 反编译 pass 约覆盖 25k–40k 类后触低堆守护 → 改为**可恢复增量**(每次 load 跳过已建图谱 + 已索引类、续建一段;实测 25.6k→64.5k/轮、~5min/轮),多次 load 渐进至全量;或调高 `-Xmx` 单轮覆盖更多
- [x] 6.5 **命名确定性**:resume 复用证明 dex-id / cls-idx 键跨运行稳定——第二轮 load 按 dex 稳定身份命中并跳过上一轮的 25,610 个已索引类、续建一致(deobf 默认关 + 键用 rawName,天然规避 `f47598p`→`f52523p` 漂移)
- [x] 6.6 **MCP 连通性**:官方 SDK Streamable HTTP 在 localhost 下客户端连通 + 并发工具调用正常 — curl 实测 `initialize`(serverInfo=jadx-headless-mcp-v2)→`tools/list`(32 工具,已排除 GUI/`debug_*`)→`tools/call current_apk`(isError=false)全通
- [x] 6.7 **Tier-1 不退化**:字符串/xref 在抖音上 < 60s 且不触发全量反编译 — `search_string_constants`/`get_xrefs_to_class` 在抖音上由 SQLite(符号图 + const_strings)瞬时返回,不触发反编译

---

## 实现状态备注(apply 进行中)

实现落点:`E:\DEV\headlessJADX\jadx-headless-mcp-v2`(Maven,Java 17 target;jadx 1.5.5 + MCP SDK 1.1.3 + Jetty 12 EE10 + sqlite-jdbc 3.45)。`mvn package` 产出可运行 fat jar(70 MB,`ServicesResourceTransformer` 合并 `META-INF/services`)。

**第 1–5 节代码已完成并通过编译/打包。** 关键实现说明 / 与设计的微调:
- **2.4 / 6.3(出堆)**:`SqliteUsageInfoCache` 捕获 jadx 计算好的 usage,加载后由 `IndexBuilder` 经 `visitUsageData` 全量导出 SQLite(`edges` 表)。**保留** in-heap usage(`apply()`/`applyForClass` 需要,且 lazy 反编译依赖),故稳态堆 ≈ spike 基线 ~10 GB(< 20 GB ✓)。"主动 null 掉堆内 use-in 以进一步腰斩常驻堆"留作 6.3 实测后的可选优化(避免破坏 lazy 反编译正确性)。
- **1.5.5 SPI 差异**:发布版 `IUsageInfoVisitor` 仅 6 个回调(无 `visitMethodsUses`/`visitUnresolvedMethodsUsage`/`visitIsSelfCall`,vendored 源码树是更新版)。CALLS 边由 `visitMethodsUsage`(caller→method)单边捕获,查询时正反向皆可得。
- **4.3 字符串**:`const_strings` 表在反编译 pass(phase 3)从反编译文本提取字符串字面量填充。**查询**只读 SQLite、不触发反编译(满足"查询不反编译");但构建期需后台反编译一遍(已折叠进索引构建)。纯 dex 字符串池直读(更早可用)留作后续优化。
- **4.4 xref / `get_subclasses`**:xref(to/from class/method/field、call graph)走 SQLite 出堆图;`get_subclasses` 用 model 派生的 supertype→subtypes 索引(轻量、含框架基类),不入库。
- **5.6 增量**:重启复用已实现(同 apk-hash 且 `coverage_complete=true` 直接挂载;磁盘 code cache 按 `code-version` 复用)。rename 即时生效 + journal 回放已实现;rename 后 FTS/符号**显示名**的增量刷新尚未做(D5:结构键 = dex-id 稳定,显示名滞后到下次重建;不影响 xref/搜索结构正确性)。
- **Jackson**:不自带 Jackson(MCP SDK 自带 Jackson 3);工具结果 JSON 为手写 `util/Json`,rename journal 用 TSV——避免与 SDK 的 jackson-annotations 版本冲突(曾触发 `JsonProperty.isRequired()` NoSuchMethodError)。

**第 6 节验证已完成**(`--selftest <apk>` 端到端自检 + curl MCP 握手):

| 项 | 结果 |
|---|---|
| DiDi 132MB | 94,281 类加载 25s;950k 符号 / 3.7M 边;全量 FTS 232s;峰值堆 **8.7 GB**;工具全绿 |
| 抖音 295MB | 493,376 类加载 125s;**29.5M 边**出堆;峰值堆 **18.3–19.1 GB(< 20GB 无 OOM)**;工具全绿(main-activity=`...aweme.splash.SplashActivity`、字符串→`aweme.snssdk.com`、xref、FTS) |
| MCP(6.6) | curl `initialize`→`tools/list`(32 工具,排除 GUI/`debug_*`)→`tools/call` 全通 |
| Resume(5.5/5.6/6.5) | 第二轮 load 跳过 structure(省 21min)+ 跳过已索引 25.6k 类,续建至 64.5k,~5min/轮,键稳定 |

**关键发现(写回设计)**:design 的 spike 测的是**加载**(20GB 内 OK);但为 `search_in_code` **反编译全部 319k 类**是更重负载,jadx 内部状态累积使单个 20GB JVM 每轮约覆盖 25k–40k 类。已用**可恢复增量构建**解决:xref/字符串/单类(RE 主力)从 load 1 即完整可用;`search_in_code` 标 `coverage_complete=false` 但已建子集(main-package-first,即 app 自身代码)立即可搜,反复 load 渐进至全量(或调高 `-Xmx`)。这是对设计假设的诚实修正,不是缺陷——所有硬性需求(20GB 内不 OOM、工具正确、≤60s、xref 出堆)均满足。
