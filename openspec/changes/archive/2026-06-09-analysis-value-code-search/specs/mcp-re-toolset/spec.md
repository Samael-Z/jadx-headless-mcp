## ADDED Requirements

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
