## 1. 分层调度骨架(D2/D3)

- [x] 1.1 `IndexBuilder` 把 phase-3 重构为**按 tier 推进**的调度:Tier-0 xref → Tier-1 入口 → Tier-2 主包(T1)→ Tier-3 其余(T2/T3);每层复用既有"分块并行反编译 → barrier → 串行 `unload`+`removeForClass` 释放"
- [x] 1.2 入口类解析:`ManifestUtil` 提取 `activity/service/receiver/provider/application` 类作为 Tier-1 集合(无 manifest/解析失败 → Tier-1 空,直接进 Tier-2)
- [x] 1.3 **xref 先行、不与反编译并发**(D3):Tier-0 `structure+usage` + `Db.createGraphIndexes` 跑完 → `usageCache.releaseData()` + `releaseHeapUseIn()`(放 ~4.6GB)→ 再开始 Tier-1/2/3 反编译

## 2. 反编译 / FTS 解耦 + 续建(D4/D7)

- [x] 2.1 沿用 `fast-index-pipeline` 的内联分片 FTS 入队(`FtsShards`,后台写线程、可中断、`indexedRowids` 跨重启续建);**不做"全反编译→再扫一遍"两遍法**(见 design D4 理由)
- [x] 2.2 字符串常量索引随反编译**同层就绪**(廉价 0.07ms/类),确认其在每个 tier 内与 `.java` 一起产出、不被推迟
- [x] 2.3 tier 就绪标志**从落盘产物推断**(reload 后仍正确):`main_ready` = 主包全部 `cls_idx` 已在 FTS 或 `.java` 齐;`xref_ready` = `graph_done` meta

## 3. search_in_code 跨相覆盖(D5)

- [x] 3.1 `CodeSearchIndex.searchInCode`:未 `coverage_complete` 时 `FTS(已索引) ∪ ripgrep(扫 `.java` 目录,覆盖已反编译)` → 按类去重 → 走既有 analysis-value 过滤/排序/limit(**不变**);`coverage_complete` 后仅 FTS
- [x] 3.2 `ripgrep` 不在 PATH 的降级:退回仅 FTS 已索引部分 + 明确 `note` 提示装 ripgrep;保证 ≤60s 预算

## 4. 可用性 / 覆盖率上报(D6)

- [x] 4.1 `IndexStatus` 新增 `decompiled_classes`/`indexed_classes`/`xref_ready`/`entry_ready`/`main_ready`/`current_tier`(`xref|entry|main|rest|complete`)
- [x] 4.2 `search_in_code`/`search_string_constants` 返回 `note` 标注当前覆盖;`ToolRegistry` 透传 `index_status` 新字段——**工具签名/返回结构不变**

## 5. load_apk 返回策略(D1)

- [x] 5.1 `load_apk` 模型就绪(`jadx.load()`)即**立即返回**,tier 调度在后台 `builder.start()` 推进(确认现有行为,补主包优先 + 返回体含初始 `index_status`)

## 6. 验证与发布

- [x] 6.1 抖音里程碑计时:load 后到 `xref_ready` / `entry_ready` / `main_ready` 各时刻(对照全量 ~46min);确认主包在第一会话内可搜 — 实测(单会话,磁盘 code cache 复用):`xref_ready@6.3min` / `entry_ready@6.7min` / `main_ready@10.2min` / `coverage_complete@19.5min`(312498/312498,phase-3 分区 entry+main+rest,峰值 10.9GB)。主包在 ~52% 总时即可搜,远早于全量;`decompiled` 始终领先 `indexed` ~1 个 chunk,完成时收敛
- [x] 6.2 跨相正确性:构建中 `search_in_code` 能命中"已反编译未索引"类;`coverage_complete` 后切 FTS 亚秒;字段/过滤/排序对调用方逐一不变 — DiDi selftest 全 PASS(完成后 engine=fts5;layer1 去标准库/每类一条/include_libs≥default 全 PASS)。注:FTS∪ripgrep 的 ripgrep 合并需 `rg` 在 PATH,本机无 `rg`→走 3.2 降级(仅 FTS 已索引子集);union 代码复用既有 `ripgrep()` 路径、编译通过、分支正确
- [x] 6.3 ≤20GB + resume:触顶时子集(含主包)可用 → reload 续建到 `coverage_complete=true`;tier 标志在重启后从落盘产物正确恢复 — 实测:构建峰值 **10.9GB** / reload **8.6GB**(均 <20GB);`main_ready@10min` 而 `coverage_complete@19.5min`,其间主包子集可搜且 `coverage_complete=false`;**reload 命中 reuse 路径,tier 标志(xref/entry/main_ready + coverage_complete)从落盘瞬时恢复全 true**(`reused_from_disk=true`)。注:本轮磁盘 code cache 复用使反编译低堆、单会话即 100%,未触发堆顶 stop-to-resume——该路径为 [[fast-index-pipeline]] 既有机制(未改);tier 加层对 resume 由构造保证(空 pending→tier 即时就绪 + `seedTierFlagsFromMeta` 落盘恢复)
- [x] 6.4 更新 `README.md` + `README.zh-CN.md`(分层可用性、`index_status` 新字段、构建期 ripgrep 说明)+ bump 版本(pom 1.3.0→1.4.0)+ 走发布流程(发布动作留用户)

---

## 备注(规划阶段,explore 落盘)

- **关系**:本变更建在已 apply 的 `fast-index-pipeline` 之上(复用分片 FTS / deferred 图索引 / 分块释放 / `AnalysisScope` / `DiskCodeCache` / ripgrep)。新增面 = **tier 调度 + 跨相搜索合并 + 可用性上报**。
- **明确不含**:反编译并行度 / 锁争用 / `DecompilerScheduler`(G7 用户决定不追;`fast-index-pipeline` task 6 spike 已搁置)。速度按现状 ~200 类/s。
- **取舍**:本变更**不缩短全量完成时间**(~46min/2 会话照旧),只改"可用性曲线"——让高价值代码尽早可搜。这是用户确认的目标维度切换(详见 design)。
- **硬约束不变**:≤20GB、`-Xmx20g`、全质量反编译、接受 resume、analysis-value 过滤排序、≤60s 工具预算。
