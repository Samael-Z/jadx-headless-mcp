## ADDED Requirements

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
