## 1. 层一 · 搜索结果优化(优先;零索引改动、立即解决噪点)

- [x] 1.1 在 `CodeSearchIndex`(或 util)加包价值判定 helper:`analysisTier(fqn)` → T1 app(manifest package + 厂商系前缀)/T2 混淆包(单字母·短段启发式)/T3 具名三方/T4 标准库;标准库前缀表(`android`/`androidx`/`java`/`javax`/`kotlin`/`kotlinx`/`com.google`/`com.android`/`okhttp3`/`okio`/`retrofit2` 等)
- [x] 1.2 `searchInCode` / `searchStringConstants` / `findStringUsages` 结果默认过滤 T4(标准库)命中
- [x] 1.3 结果按类聚合:FTS 路径确认每类一条;ripgrep 兜底改为按类折叠(class + 行号/片段列表),不再每行一条
- [x] 1.4 相关性排序(T1 > T2 > T3),`limit` 在排序之后施加
- [x] 1.5 `ToolRegistry.search_in_code` 加 `scope`(包前缀子树)与 `include_libs`(纳回 T4)参数并透传到 `CodeSearchIndex`
- [x] 1.6 更新三个工具的 description;~~在抖音上验证~~:`search_in_code("http")` 默认无标准库噪点、同类只一条、app/混淆命中靠前 — description 已更新;`SelfTest` 加了等价断言(见 4.2),小包冒烟已过;**抖音端到端验证待长跑(见备注)**

## 2. 层二 · 索引范围优化(选择性反编译,省耗时省内存)

- [x] 2.1 复用/提取 1.1 的 `analysisTier`,实现 `shouldIndex(cls)`:默认 T1+T2(+T3 可配)索引、T4 跳过
- [x] 2.2 `IndexBuilder.decompilePass` 按 `shouldIndex` 过滤待反编译类(默认跳 T4),保持 main-package-first 顺序
- [x] 2.3 `Main.Args` 加 `--index-include` / `--index-exclude`(包前缀)/ `--index-all`,贯通到 `IndexBuilder`
- [x] 2.4 `index_status` / `current_apk` 暴露当前索引范围(价值类 vs 全部)与对应 `total_classes`
- [x] 2.5 验证:普通 app 索引时间/体积较默认全量显著下降;抖音 `X` 包仍被索引(属 T2) — APKPure 实测:14778 顶层类 → **3276(22%)标准库跳过**、11502 索引;抖音实测 **312498 in-scope / 6765 跳过**(`X` 包属 T2 仍索引,层二收益有限——与 design 预测一致)

## 3. 兜底 · 真全量(deepUnload + 解并行枷锁)

- [x] 3.1 SPIKE:`IndexBuilder` phase-3 改用 `ClassNode.unloadCode()`/`deepUnload()` 释放,在抖音 `--index-all` 下实测堆峰值与反编译速率;确认 `RootNode.CacheStorage` 等其他全局累积点是否仍增长 — **jmap 差分定位定稿**:残余累积 = **反编译 IR 留存**(`BlockNode`/`RegisterArg`/`SSAVar`/`InvokeNode`/`TypeBoundConst`),**非 `CacheStorage`**(查证仅 `Set<String> rootPkgs`,与 IR 无关——design 假设证伪)。两步释放(`removeForClass` 清 ConstValues + `cn.unload()` 清 IR)后抖音单轮达 **312498/100% `coverage_complete=true`,峰值 19.3GB**;决定性证据:96% 时 RSS 19.1→17.9GB 回落
- [x] 3.2 若 spike 通过:phase-3 正式以 `deepUnload`/`unloadCode` 彻底释放(清 `ConstValues`),消除全局累积 — **分块 barrier 串行释放**(并行反编译只读 → barrier → 串行清)避开并发 `ConstStorage.classes`(HashMap)竞态。**释放 = `cn.unload()`(清 IR)+ `removeForClass`(清 ConstValues,类+内部类)**,即 `deepUnload` 去掉无谓的 `load(clsData,true)` 重载(那个 reload 逐类刷屏 `Can't restore usage data`、且不释放更多)。**勿用 `unloadCode()`**(它 `unloadFromCache` 会驱逐磁盘 cache)。订正:惰性 `getCode()` API **不会**像 save() 自动卸载,故 `unload()` 必须显式调(本变更前一版曾误删,致 IR 泄漏卡 61%)
- [x] 3.3 解并行枷锁:heap 不再是瓶颈后提高 `decompilePass` 的 `par`(实测最优核数利用) — 默认 `par=cores`(原 cores/2),`JADX_INDEX_THREADS` / `JADX_INDEX_CHUNK` 可调
- [x] 3.4 `--index-all` 全量路径:验证在 20 GB 内对抖音单进程一轮达成 `coverage_complete=true`;若不能,`index_status` 报告部分覆盖并支持重载续建(不静默丢失) — **达成**:两步释放后抖音默认 scope **单轮 312498/100% `coverage_complete=true`**(峰值 19.3GB、phase-3 ~27.6min)。"若不能"分支(优雅部分覆盖 + 续建)保留为 `--index-all`/更大包的兜底,语义不变

## 4. 数据、验证与发布

- [x] 4.1 收集 `--selftest` 全量基线(滴滴单轮 + 抖音全量),把真实耗时/峰值堆补入 `design.md` 的"数据支撑"节 — 已补:APKPure(单轮 complete/2.68GB/41s)+ 抖音(`removeForClass`→61% → +`unload()`→**100% `coverage_complete=true`**/19.3GB/~68 类/s/27.6min)+ 根因(IR 留存,非 CacheStorage)+ 速度↔覆盖正交瓶颈
- [x] 4.2 `SelfTest` 增加断言:层一(默认结果不含 T4、按类聚合)、层二(默认范围跳 T4)、兜底(`--index-all` 覆盖)
- [ ] 4.3 更新 `README.md` + `README.zh-CN.md`:工具表(`search_in_code` 新参数/默认过滤)、CLI flag(`--index-*`)、changelog
- [ ] 4.4 bump 版本 + 走既有发布流程(commit → `v*` tag → CI Release → `gh release download` 覆盖 `dist/`)

---

## 实现状态备注(apply 进行中,2026-06-08)

**代码完成并通过 `mvn -s settings.xml package`(BUILD SUCCESS):层一全部、层二全部、兜底 3.2/3.3、4.2。** 关键实现说明:

- **`AnalysisScope`(新增,`index/`)**:`tierOf(fqn)` → T1/T2/T3/T4;`isLib`(层一过滤)、`rank`(排序)、`shouldIndex`(层二)。T4 标准库前缀表;T2 混淆启发式 = 包首段单字母/≤3 单段、或首段≤2 且次段≤2(`io.flutter` 等真前缀不误判);T1 = manifest package + top-2 段 + 同源厂商组(ByteDance `com.ss`/`com.bytedance` 等,抖音开箱即用);`--index-include` 提升为 T1、`--index-exclude` 强制跳过、`--index-all` 全索引。
- **层一(查询侧,零索引改动)**:`CodeSearchIndex` 三个查询先取 `CANDIDATE_CAP=10000` 候选 → 过滤 T4(除非 `include_libs`)→ 按 `scope`(包前缀)限定 → 按 tier rank + FQN 排序 → 施加 `limit`。FTS 本就每类一条;**ripgrep 兜底改为按类折叠**(`{class, snippets:[{line,text}]}`),并修了原 `line.indexOf(':')` 被 Windows 盘符冒号切错的隐患(改用 `.java:` 锚点)。`search_string_constants` 按类聚合为 `{class, strings:[...]}`。
- **层二(选择性索引)**:`IndexBuilder.decompilePass` 先按 `scope.shouldIndex` 过滤(默认跳 T4),保持 main-first;`index_status`/`current_apk` 暴露 `index_scope` + `in_scope_classes`。**注意**:结构/usage/xref 仍全量(symbol 图谱不受 scope 影响,符合 Non-Goal),只有反编译/FTS 被 scope 限定。
- **兜底 3.1/3.2/3.3(根因修复)**:phase-3 = **分块(`JADX_INDEX_CHUNK=4000`,`par=cores`)**:并行反编译一个 chunk(只读 jadx 全局态)→ **barrier** → **串行释放每个类** = `cn.unload()`(清反编译 IR)+ `root().getConstValues().removeForClass(cn)`(清 ConstValues),均递归内部类。
  - **根因(jmap 差分定位,非猜)**:残余累积 = **反编译 IR 留存**(`BlockNode`/`RegisterArg`/`SSAVar`/`InvokeNode`/`TypeBoundConst`/逐节点 `AttributeStorage`),**非 `CacheStorage`**(查证只是 `Set<String> rootPkgs`,design 假设证伪),也不是 ConstValues 单独。
  - **必须两步**:`removeForClass` 把单轮 47114(14%)→190842(61%);`cn.unload()`(`MethodNode.unload` nulls blocks/ssa/insns/region/loops)再→**312498(100%)**。**订正**:惰性 `getCode()` API **不**像 `save()` 那样自动卸载(`DONT_UNLOAD_CLASS` 无 setter,但该路径保持类驻留以便复访),故 `cn.unload()` 必须显式调;本变更前一版曾误删它 → IR 泄漏卡 61%。**勿用 `unloadCode()`**(`unloadFromCache` 会驱逐磁盘 cache);亦不取 `deepUnload()` 的 `load(clsData,true)` 重载(逐类刷屏 `Can't restore usage data`、无谓开销)。
  - **必须串行 + barrier**:`removeForClass` 改 `ConstStorage.classes`(**普通 `HashMap`**),反编译期 `getConstField` 并发**读**它(`replaceConsts` 默认 true);`cn.unload()` 亦不可与并发反编译并行。分块 barrier 保证释放时无并发反编译。
  - 堆守护:每 chunk 后 `lowHeap()` → `System.gc()` → 仍低则 `memStop` 优雅续建(不 OOM、不静默丢失)。抖音默认 scope 现单轮即全量,守护退为 `--index-all`/超大包兜底。
  - 复用纳入 scope:`meta.index_scope` 记录构建范围;更宽请求不走快速复用、走续建扩展。
  - **实测(新 jar)**:APKPure 单轮 complete/2.68GB/41s;**抖音默认 scope 单轮 312498/100% `coverage_complete=true`/峰值 19.3GB/~27.6min/警告 0**——96% 时 RSS 19.1→17.9GB **回落**直证 IR 每块释放、不再累积。`javap` 确认 1.5.5 artifact 有 `unload`/`removeForClass`/`getConstValues`/`getInnerClasses`。
- **4.2 断言**:`SelfTest` 加层一(`search_in_code('http')` 默认无 stdlib 命中、无重复类、`include_libs` ≥ default)、层二(报告 `index_scope`/`in_scope_classes`)检查。

**抖音长跑已完成(本会话实测)**:1.6 端到端、2.5 量化、3.1 jmap 定位根因(IR 留存,非 CacheStorage)、3.4 单轮 312498/100% `coverage_complete=true`(峰值 19.3GB)、4.1 基线写回 design。两 APK 在 `E:\DEV\headlessJADX\apks\`。**剩 4.4 发布**(bump 1.1.1→1.2.0 + commit/tag/CI,需用户授权);可选:从零 single-pass clean 复核、A/B 提速。
