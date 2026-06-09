## ADDED Requirements

### Requirement: search_in_code 跨分片查询透明

当全文索引以多分片构建时,`search_in_code` SHALL 对调用方透明:工具签名、参数(`query`/`regex`/`scope`/`include_libs`/`limit`)与返回结构 SHALL 不变;底层对 M 个分片并行查询并合并候选后,再施加既有的分析价值层(按类聚合、默认过滤标准库、相关性排序 app > 混淆 > 三方、`limit` 在排序后)。查询延迟 SHALL 仍为亚秒级、远低于 60s 预算。

#### Scenario: 分片对调用方不可见

- **WHEN** 客户端调用 `search_in_code(query)`,底层索引为 M 分片
- **THEN** 返回的字段与结果排序/过滤语义与单库索引时一致;客户端无需感知分片数或分片存在

#### Scenario: 分片查询仍在预算内

- **WHEN** 在抖音级索引(分片)上执行典型 `search_in_code` 查询
- **THEN** 跨分片 fan-out + 合并的总延迟仍在亚秒级,满足 ≤60s 工具调用预算
