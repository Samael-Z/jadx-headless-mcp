## Why

现有两条路都不满足逆向分析(RE)需求:

- **jadx-ai-mcp**:把 HTTP 服务器嵌在 **JADX-GUI 进程**里,`init()` 在 `getGuiContext()==null` 时直接退出 → **无法无头运行**;且路由全部经 `MainWindow.getWrapper()` 取 decompiler(虽然 ~90% 操作其实只需要 `JadxDecompiler`)。
- **jadx-headless-mcp**:早期设计,边界/功能未定义清楚;早期靠"MCP 搜 smali 文本"定位信息,对每个类都要反编译 → **极慢**;加载抖音(281 MB / 55 dex)这类大型 APK 时**加载不全 → MCP 能力等同失效**。

目标:**重做一个为 RE 而生的无头 jadx + MCP 服务**——能加载任意 APK(含抖音)、所有 MCP 工具稳定可用、单次工具调用 **≤ 60s**(加载可以久)、最大 **20 GB** 内存。已通过 spike 实测验证可行性(见 design)。

## What Changes

- **单进程 Java 无头服务(Design 2)**:`jadx-core` + MCP server(Java MCP SDK,**HTTP/SSE 传输**)同进程;**无内部 HTTP 桥、无 Python 中转**(区别于 jadx-ai-mcp 的两进程架构)。
- **常驻 + 惰性**:`load_apk` 一次性加载(可久),之后**按需逐类反编译**;**有界/磁盘 code cache**(禁用默认 `InMemoryCodeCache`);usage/xref 索引在加载期建好,查询免费。
- **工具分层提炼**(并 jadx-ai-mcp + jadx-headless-mcp,按"是否需要反编译"分层):
  - Tier-1 瞬时(结构/字符串池/xref,不反编译)、Tier-2 快(单类惰性反编译)、Tier-3 慢(`search_in_code` 走持久化索引/异步)。
  - **砍掉** GUI 专属(`selected-text`、`current-class`)与 smali 调试器组(`debug_*`)。
- **命名恢复(实测定稿)**:`useSourceNameAsClassNameAlias = ALWAYS` + `kotlin-metadata`(默认开);**`deobf` 默认关**(实测对抖音等重混淆 app 帮倒忙:不恢复语义、反而把 `X`/`ss`/`ug` 套成 `p003X`/`p676ss`/`p321ug`)。
- **`search_in_code` 空间换时间**:双层磁盘持久化(反编译文本 cache + 倒排索引),**键用 dex 稳定身份**(原始类型描述符 / dex 索引),显示名只是挂在其上的元数据层;一次构建、跨重启复用。

## Capabilities

### New Capabilities
- `headless-jadx-server`: 无头常驻 jadx 服务——加载任意 APK、惰性逐类反编译、命名恢复、20 GB 内存纪律(磁盘缓存/流式)。
- `mcp-re-toolset`: 面向 RE 的 MCP 工具集——分层工具、HTTP 传输、单次调用 ≤ 60s、字符串常量 + xref 为主力。
- `code-search-index`: `search_in_code` 的持久化缓存 + 倒排索引——dex 稳定键、流式构建、增量失效。

### Modified Capabilities
（无。`openspec/specs/` 现有能力与本变更无需求重叠。）

## Impact

- **新增组件**:一个单进程 Java 服务;依赖 `jadx-core`(+ `jadx-dex-input`/`jadx-kotlin-metadata` 等输入/元数据插件)与一个 Java MCP SDK。实现落点(jadx 仓内新模块 vs 兄弟项目)在 design 中讨论。
- **不改** `jadx-core` 源码;不触碰其 Java 11 约束与 Gradle 构建。
- **取代/超越** `jadx-headless-mcp`;**不再依赖 jadx-gui**(区别于 jadx-ai-mcp)。
- **运行约束**:RE 场景;`-Xmx20g`;HTTP MCP 端点(可远程连);常驻进程生命周期管理。
- **关联**:可与仓库根 `../CLAUDE.md`(MCP 流水线)、`docs/jadx-decompilation-guide.md`(反编译原理/调优)交叉引用。
