# mcp-re-toolset Specification

## Purpose

面向逆向分析(RE)的 MCP 工具集能力:通过 HTTP(Streamable)暴露 MCP 接口,提供从 jadx-ai-mcp + jadx-headless-mcp 提炼、按"是否反编译"分层的工具集,保证单次调用 ≤ 60s,并以字符串常量 + xref 为重混淆 app 的主要定位手段。构建于 `headless-jadx-server`(引擎)与 `code-search-index`(全文搜索)之上。

## Requirements

### Requirement: MCP over HTTP 工具接口

服务 SHALL 通过 **HTTP(streamable/SSE)** 暴露 MCP 接口,使 LLM 客户端能发现并调用工具;单进程内 MCP server 与 jadx 引擎同栈,**无内部 HTTP 桥、无跨语言中转**。

#### Scenario: 客户端经 HTTP 连接并调用工具

- **WHEN** MCP 客户端连到服务的 HTTP 端点并列出工具
- **THEN** 返回提炼后的工具清单,且任一工具可被调用并返回结果

### Requirement: 提炼的 RE 工具集

服务 SHALL 提供从 jadx-ai-mcp + jadx-headless-mcp 提炼的工具集,覆盖:枚举(`get_all_classes`/`get_package_tree`/`get_methods_of_class`/`get_fields_of_class`)、单类(`get_class_source`/`get_smali_of_class`/`get_method_by_name`)、字符串(`search_string_constants`/`find_string_usages`/`get_strings`)、xref(`get_xrefs_to/from_*`/`get_subclasses`)、资源(`get_android_manifest`/`get_main_activity`/`get_resource_file`)、改名(`rename_class/method/field/package`)、全文(`search_in_code`)、会话(`load_apk`/`current_apk`/`index_status`/`clear_cache`)。MUST NOT 包含 GUI 专属工具(`selected-text`、`current-class`)与 smali 调试器组(`debug_*`)。

#### Scenario: GUI 专属工具被排除

- **WHEN** 客户端枚举工具
- **THEN** 不存在 `get_selected_text` / `fetch_current_class` / `debug_*`(无头无意义)

#### Scenario: 改名持久化

- **WHEN** 调用 `rename_class` 等
- **THEN** 改名即时生效并落盘 journal,重载 APK 后回放(参考 jadx-headless-mcp 既有行为)

### Requirement: 单次工具调用 60 秒预算

每个工具 SHALL 在抖音级 APK 上将单次调用控制在 ~60s 内。瞬时类(结构/字符串/xref)与单类反编译 MUST 直接满足;**反编译密集的全量操作 MUST NOT 阻塞单次调用**(改为索引查询或异步任务),否则视为违规。

#### Scenario: 重操作不阻塞

- **WHEN** 客户端发起全量代码搜索 `search_in_code`
- **THEN** 调用以索引查询返回,或返回任务句柄 + `index_status`/进度供轮询,而非阻塞 > 60s

#### Scenario: 瞬时工具在大型 APK 上仍快

- **WHEN** 在抖音上调用 `search_string_constants` 或 `get_xrefs_to_method`
- **THEN** 不反编译全部类,基于 dex 字符串池 / 加载期 usage 索引返回,耗时 < 60s

### Requirement: 字符串常量与 xref 为 RE 主力

对重混淆 app(类/方法名为 `X.C11FU.LIZ()` 等无意义形式),工具集 SHALL 以**字符串常量搜索 + 字符串用法定位 + xref** 为主要定位手段(名字搜索退居其次),且这些手段 MUST 不依赖全量反编译。

#### Scenario: 靠字符串定位重混淆逻辑

- **WHEN** RE 用户在抖音里搜索字符串 `"launch_app"` 的用法
- **THEN** 返回引用该字符串的类/方法,无需把全部类反编译成文本

### Requirement: 代码搜索结果的分析价值优化

`search_in_code` / `find_string_usages` / `search_string_constants` 的结果 SHALL 面向逆向信噪比优化:**按类聚合**(同一类至多一条,附命中行号/片段),**默认过滤标准库命中**(`android`/`androidx`/`java`/`javax`/`kotlin`/`kotlinx`/`com.google`/`com.android` 等),并按**相关性排序**(app 包 > 混淆包 > 具名第三方);`limit` 在排序之后施加,使最具分析价值的结果优先返回。`search_in_code` SHALL 接受 `scope`(限定包前缀子树)与 `include_libs`(显式纳回标准库命中)参数。

#### Scenario: 结果按类聚合不重复

- **WHEN** `search_in_code` 在同一个类中有多处命中
- **THEN** 该类在结果中只出现一次,附带命中行号/片段,而非每行一条

#### Scenario: 默认过滤标准库噪点并排序

- **WHEN** 在抖音上调用 `search_in_code("http")` 且未设 `include_libs`
- **THEN** 结果不含 androidx/okhttp/kotlin 等标准库类命中,且 app 自身与混淆包命中排在具名第三方之前

#### Scenario: 可显式找回库命中

- **WHEN** 同一查询设置 `include_libs=true`
- **THEN** 标准库类的命中也被返回(用于确需分析某个库时)

#### Scenario: 按包子树限定范围

- **WHEN** 调用 `search_in_code` 并设置 `scope` 为某包前缀
- **THEN** 仅返回该包子树下类的命中

### Requirement: find_string_usages 精确整串匹配

`find_string_usages` SHALL 仅匹配**完整**字符串字面量(整串相等),子串/模糊匹配由 `search_string_constants`(FTS 加速)承担,二者职责正交。

#### Scenario: 精确匹配完整字面量

- **WHEN** 以一个完整字符串字面量调用 `find_string_usages`
- **THEN** 仅返回包含该完整字面量的类,而非包含其子串的更长字符串所在的类

### Requirement: search_in_code 跨分片查询透明

当全文索引以多分片构建时,`search_in_code` SHALL 对调用方透明:工具签名、参数(`query`/`regex`/`scope`/`include_libs`/`limit`)与返回结构 SHALL 不变;底层对 M 个分片并行查询并合并候选后,再施加既有的分析价值层(按类聚合、默认过滤标准库、相关性排序 app > 混淆 > 三方、`limit` 在排序后)。查询延迟 SHALL 仍为亚秒级、远低于 60s 预算。

#### Scenario: 分片对调用方不可见

- **WHEN** 客户端调用 `search_in_code(query)`,底层索引为 M 分片
- **THEN** 返回的字段与结果排序/过滤语义与单库索引时一致;客户端无需感知分片数或分片存在

#### Scenario: 分片查询仍在预算内

- **WHEN** 在抖音级索引(分片)上执行典型 `search_in_code` 查询
- **THEN** 跨分片 fan-out + 合并的总延迟仍在亚秒级,满足 ≤60s 工具调用预算

### Requirement: search_in_code 构建期跨相覆盖

在索引尚未 `coverage_complete` 时,`search_in_code` SHALL 覆盖**所有已反编译的类**(而非仅 FTS 已索引的子集):底层合并 `FTS(已索引) ∪ ripgrep(扫磁盘 `.java`,覆盖已反编译集)`,按类去重后再施加既有的分析价值层(默认过滤标准库、按 app>混淆>三方排序、`limit` 在排序后)。在 `coverage_complete` 之后,SHALL 退回仅用 FTS(亚秒)。工具签名、参数与返回结构 SHALL 不变,调用方 SHALL NOT 需要感知构建阶段或合并机制。

#### Scenario: 已反编译未索引的类也能搜到

- **WHEN** 构建进行中,查询命中某个"已反编译但尚未进入 FTS"的类
- **THEN** 该类出现在结果中(经 ripgrep 覆盖),与已索引类一并按分析价值排序返回

#### Scenario: 完成后回到 FTS 亚秒查询

- **WHEN** `coverage_complete=true`
- **THEN** `search_in_code` 仅走 FTS,延迟回到亚秒级,行为与未分层时一致

#### Scenario: 对调用方语义不变

- **WHEN** 客户端在任意构建阶段调用 `search_in_code(query)`
- **THEN** 返回字段、过滤(默认去标准库)、排序(app>混淆>三方)、`limit` 语义与之前一致;客户端无需感知 FTS/ripgrep 或当前 tier

### Requirement: 工具上报构建可用性

MCP 工具 SHALL 让调用方得知当前可搜范围:`index_status` 暴露 `decompiled_classes`/`indexed_classes`/`xref_ready`/`main_ready`/`current_tier`;`search_in_code`/`search_string_constants` 的返回 `note` SHALL 在未完成时标注覆盖情况(例如"搜索 N 个已反编译类;main_ready;P 个待反编译")。延迟 SHALL 仍在 ≤60s 工具预算内(构建期 ripgrep 路径亦然)。

#### Scenario: 未完成时提示覆盖范围

- **WHEN** 构建进行中调用 `search_in_code`
- **THEN** 结果含 `note` 说明已搜索的反编译类数 / 已索引数 / 主包是否就绪,调用方据此判断结果完整性
