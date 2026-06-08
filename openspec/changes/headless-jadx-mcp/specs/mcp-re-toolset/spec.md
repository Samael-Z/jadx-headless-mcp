## ADDED Requirements

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
