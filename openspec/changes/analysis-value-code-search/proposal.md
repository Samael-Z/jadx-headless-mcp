## Why

`search_in_code` 自 v1.0.0 起就不是全量:`IndexBuilder` 的反编译流式构建用 `ClassNode.unload()` 释放不彻底(漏清 `RootNode` 的 `ConstValues` 等全局累积点),单进程单轮撞 `lowHeap` 守护仅覆盖 ~14%(抖音实测 47114/319263 类,`coverage_complete=false`)。但逆向分析的真实痛点并非"搜不全",而是**信噪比**:标准库(android/androidx/java/kotlin/google/常见 SDK)的类淹没 app 自身命中、同一类重复出现、以及全量反编译的高耗时(外推 ~2.7h)。

因此把 `search_in_code` 从"追求全量全文"**重定位为"分析价值导向(analysis-value-driven)"**:默认只搜索/索引有逆向价值的代码(app 自身 + 混淆包),把结果调干净;真·全量降级为可选开关。

## What Changes

- **层一 · 搜索结果优化**(改动小、立即见效、不动索引):结果**按类聚合**(每类一条 + 命中行号)、默认**过滤标准库**命中、**相关性排序**(app 包 > 混淆包 > 具名三方);`search_in_code` 新增 `scope`(限包子树)与 `include_libs`(回到含库)参数。
- **层二 · 索引范围优化**(省耗时省内存):反编译/索引按**分析价值分层**选择性进行 —— T1 app 自身(manifest package + 厂商系)必索引、T2 混淆包(单字母/短段启发式)索引、T3 具名三方默认索引可配、T4 标准库跳过;`--index-include` / `--index-exclude` 可配。范围砍小后更易单进程一轮覆盖价值类。
- **兜底 · 真全量**:`--index-all` 开关,由 `deepUnload`/`unloadCode`(清 `ConstValues` 等全局、彻底释放)+ 解开并行枷锁(heap 不再是瓶颈后提高 `par`)支撑 —— 这是**补全** `code-search-index` spec 原定的"流式构建全量、堆 <20GB 不爆"目标,而非新增能力。
- **根因修复**:`IndexBuilder` phase-3 以 `deepUnload`/`unloadCode` 替代 `cn.unload()`,消除单进程反编译的全局状态累积。

## Capabilities

### New Capabilities

(无 —— 本变更演进现有能力,不引入新 capability。)

### Modified Capabilities

- `code-search-index`: 索引构建从"尽力全量(实际仅 ~14%)"改为"**分析价值导向的选择性索引**(默认)+ `--index-all` 真全量(由彻底释放的 `deepUnload` + 提高并行支撑)";新增反编译/索引的**范围过滤**(T1–T4 分层 + include/exclude);全量路径要求在 20GB 内达成 `coverage_complete=true`。
- `mcp-re-toolset`: `search_in_code` / `find_string_usages` / `search_string_constants` 的**结果语义**变更 —— 按类聚合去重、默认过滤标准库命中、相关性排序;`search_in_code` 新增 `scope` / `include_libs` 参数。

## Impact

- **代码**:`index/IndexBuilder`(范围过滤 + `deepUnload` + 并行度)、`index/CodeSearchIndex`(结果聚合/过滤/排序)、`mcp/ToolRegistry`(工具参数与结果组装),可能 `index/Db`(若需价值标记列)。
- **行为**:`search_in_code` 默认返回结果变化(过滤标准库 + 按类聚合)、新增可选参数。**非破坏**:默认更贴合逆向,可经 `include_libs` / `--index-all` 回到全量。
- **性能**:一般 app 索引时间显著下降、噪点消除;抖音因 `X` 包(29.7 万类混淆)属 T2 必索引,层二收益有限,但层一仍解决噪点。
- **数据支撑**:全量反编译耗时基线(滴滴单轮 + 抖音多轮至 `coverage_complete`)正由 `--selftest` 后台采集,完成后补入 `design.md`。
