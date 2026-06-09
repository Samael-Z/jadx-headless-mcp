## ADDED Requirements

### Requirement: 分析价值分层的渐进式构建

索引构建 SHALL 按分析价值分层、渐进推进,使高价值产物先就绪即可用,而非等全量完成:顺序为 **xref 图谱(structure+usage)→ 入口类 → 主包 → 其余**。每一层的产物 SHALL 在其就绪后立即对相应工具可用(xref 就绪后 `get_xrefs_*` 可用;某层类反编译后该层代码即可搜),不得阻塞在后续层或全量完成上。层的划分 SHALL 复用既有 `AnalysisScope`(入口=manifest 的 activity/service/receiver/provider/application;主包=T1 manifest 包+同源;其余=T2/T3)。

#### Scenario: 主包先于全量可搜

- **WHEN** `load_apk` 完成且主包类已反编译,但其余类仍在后台反编译
- **THEN** 主包类已可被 `search_in_code`/`search_string_constants` 搜到,无需等待其余类完成

#### Scenario: xref 最早可用

- **WHEN** `load_apk` 完成(模型就绪)、反编译尚未开始或进行中
- **THEN** xref 图谱(structure+usage)独立于反编译先行构建并就绪,`get_xrefs_*`/call-graph/subclasses 可用

### Requirement: 反编译与 FTS 解耦、后台可续

反编译(产出 `.java` 到磁盘 code cache)与 FTS trigram 索引构建 SHALL 解耦:FTS 在后台进行、可被中断、并可跨进程重启续建(已索引类按 `indexedRowids` 跨分片跳过);搜索的可用性 SHALL NOT 阻塞于 FTS 完成。字符串常量索引 SHALL 随反编译就绪(廉价,跟随同层产出),不得被推迟到全量之后。

#### Scenario: FTS 滞后不阻塞搜索

- **WHEN** 一批类已反编译(`.java` 在磁盘)但 FTS 尚未覆盖它们
- **THEN** 这些类仍可被 `search_in_code` 搜到(经 ripgrep 覆盖已反编译集),不必等其进入 FTS

#### Scenario: 跨重启续建至完整

- **WHEN** 重新加载同一 APK
- **THEN** 已反编译的类命中磁盘缓存(不重反编译)、已索引的类跨分片被跳过,构建续建剩余部分直至 `coverage_complete=true`

### Requirement: 20GB 内的渐进构建与多会话闭合

构建全程 SHALL NOT 超过 20 GB;当堆逼近上限时 SHALL 停转为可续状态(stop-to-resume,非 OOM),此时已索引/已反编译子集 SHALL 仍为可搜的 READY 状态,`coverage_complete=false` 直至后续 reload 续建完成。SHALL NOT 为单会话完成而要求放宽 20 GB 或降低反编译质量。

#### Scenario: 触顶转续建,子集仍可用

- **WHEN** 单会话反编译使堆逼近 20 GB
- **THEN** 构建停转为续建态,已覆盖子集(含主包)保持可搜,reload 后续建并最终达到 `coverage_complete=true`

### Requirement: 可用性与覆盖率上报

`index_status` SHALL 上报分层可用性与覆盖率,使调用方可判断当前可搜范围:至少包含 `decompiled_classes`、`indexed_classes`、`xref_ready`、`main_ready`、`current_tier`。这些标志 SHALL 可从落盘产物可靠推断(以便会话重启后仍正确)。

#### Scenario: 报告分层就绪状态

- **WHEN** 主包已反编译、其余仍在后台
- **THEN** `index_status` 显示 `main_ready=true`、`current_tier` 指示当前层、以及 decompiled/indexed 计数
