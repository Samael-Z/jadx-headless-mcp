## Context

`fast-index-pipeline` 把抖音全量构建从 ~100min 压到 ~46min(2 会话),并实测**反编译是不可压缩的 CPU/锁绑定成本**(~140–228 类/s),且本变更**明确不动**反编译并行度。既然总时长压不下去,改变交付维度:**不追求"更快全部完成",而是让高分析价值的代码尽早可搜,其余后台渐进补齐**,全程 ≤20GB。

**关键事实(决定设计)**:
- jadx `load()` 后,**usage 信息已就绪**——xref 图谱(structure+usage)**不依赖反编译**,可最先建好(~2min)。
- `DiskCodeCache` 在反编译时即把 `.java` 写盘;`{磁盘 .java} ⊇ {FTS 已索引}`(FTS 紧随反编译入队,略滞后)。⇒ **ripgrep 扫 `.java` 目录 = 当前所有"已反编译"类的完整可搜集**,天然覆盖"已反编译未索引"。
- 字符串常量从 `.java` 抽取(0.07ms/类,极廉价),对混淆 app 是头号定位 → 应随反编译就绪,不该被推迟。
- 反编译主包(62,842 类)远在 20GB backstop(~22 万类)之前完成 → **主包必在第一会话内就绪**。

## Goals / Non-Goals

**Goals:**
- 分析价值分层:入口/主包先反编译 → 尽早可搜,不等全量。
- 反编译与 FTS 解耦:FTS 后台、可中断、跨重启续建;搜索不阻塞于 FTS 完成。
- `search_in_code` 跨相透明:覆盖所有"已反编译"类;`index_status` 报可用性/覆盖率。
- 全程 ≤20GB,接受 resume 续建。

**Non-Goals:**
- 不改反编译质量(恒全质量 RESTRUCTURE);不引入 simple/fallback。
- **不动反编译并行度 / 锁争用 / `DecompilerScheduler`**(G7 搁置;速度按现状)。
- 不强求单会话 100%;不调大 `-Xmx`。
- 不重做 xref 出堆 / analysis-value 过滤排序;不改工具签名。

## Decisions

### D1 · `load_apk` 立即返回 + 主包后台优先(不阻塞)
`load_apk` 在 `jadx.load()`(出模型,~2min,不可省)后**立即返回**,构建在后台按 tier 推进;搜索随时可调,未就绪部分由 `index_status` 报进度。
*备选*:阻塞到主包反编译完(~5–8min)再返回 → load 太久、且 stdio 握手不能等;否。

### D2 · 四层调度顺序(value-prioritized)
```
load 后台:
  Tier-0  xref 图谱(structure+usage)   ← 不依赖反编译,最先建(~2min);get_xrefs_* 最早可用
  Tier-1  入口类(manifest 的 activity/service/receiver/provider/application,数百个)
            反编译 + 字符串 → 秒级可搜最关键代码
  Tier-2  主包(AnalysisScope T1:manifest 包 + 同源)反编译 + 字符串
  Tier-3  其余(T2 混淆 + T3 三方)反编译 + 字符串
  (FTS 随各 tier 反编译就绪而入队建索引;详见 D4)
```
入口/主包/其余复用 `AnalysisScope`;入口集从 `ManifestUtil` 解析。每层内仍按 `fast-index-pipeline` 的"分块并行反编译 → barrier → 串行释放"。

### D3 · xref 先行、与反编译不并发(堆安全)
Tier-0(structure+usage)**先于**反编译跑完,随后 `releaseHeapUseIn` 释放 ~4.6GB use-in,再开始 Tier-1/2/3 反编译。⇒ xref 在 ~4min 就绪(早于主包),且反编译期堆里没有 use-in。
*备选*:xref ∥ 反编译并发(省 ~2min)→ use-in 与反编译 IR 共存抬堆破 20GB;`fast-index-pipeline` 已证实风险,默认不并发(`JADX_INDEX_OVERLAP` 仍可实验)。

### D4 · 解耦 = 内联建 FTS + ripgrep 补缺(**不做两遍**)
反编译线程产 `.java` 的同时,把文本入队给分片 FTS 写线程(沿用 `fast-index-pipeline` 的 `FtsShards`,后台、可中断、`indexedRowids` 跨重启续建)——这已满足 G2 的"解耦/后台/可续"。**搜索不等 FTS**:见 D5。
- **为何不做用户设想的"全反编译→再扫一遍建 FTS"两遍法**:两遍要把 ~3–5GB `.java` 从磁盘**重读一遍**建 FTS,纯增 I/O,而**可用性收益为零**(ripgrep 已覆盖"已反编译未索引");故采内联 + 补缺,以更低成本达成同一目标。*若后续要把 FTS 完全移出反编译相,两遍法可作为演进,但当前不做。*

### D5 · `search_in_code` 跨相:构建期 ripgrep 全覆盖,完成后切 FTS
- **未 `coverage_complete` 时**:`FTS(已索引,亚秒) ∪ ripgrep(扫 `.java` 目录,覆盖所有已反编译)`,按类去重 → 再走既有 analysis-value 层(过滤/排序/limit 不变)。延迟以 ripgrep 为主(~1–3s,仅构建期)。
- **`coverage_complete` 后**:仅 FTS(亚秒),行为同 `fast-index-pipeline`。
- 因 `{.java} ⊇ {FTS}`,ripgrep 扫目录即"已反编译"全集,无遗漏;FTS 并入仅为构建期对已索引部分提速。语义对调用方不变。

### D6 · `index_status` 扩展可用性字段
新增:`decompiled_classes`、`indexed_classes`、`xref_ready`(bool)、`entry_ready`、`main_ready`(bool)、`current_tier`(`xref|entry|main|rest|complete`)。`search_in_code`/`search_string_constants` 返回的 `note` 标注当前覆盖("searching N decompiled (M via FTS + ripgrep); main_ready=true; P classes pending")。

### D7 · 续建与持久化(沿用 + 扩展)
`.java`(disk code cache,codeVersion 戳)+ FTS 分片 + 主库 + xref 图谱均持久化。reload:模型重载后,已反编译类命中磁盘缓存(不重反编译),`indexedRowids` 跨分片跳过已索引 → 续建剩余;coverage 跨会话累积到 `coverage_complete=true`。tier 状态从已落盘产物推断(主包 .java 齐 → main_ready)。

## Risks / Trade-offs

- **构建期 ripgrep 延迟**:~1–3s/查询(扫数 GB `.java`),比 FTS 慢;仅构建期、且换来"已反编译即可搜"。可优化为只扫"未索引"文件子集(按 cls_idx ∉ indexedRowids 选文件),复杂度高,暂不做。
- **入口类解析依赖 manifest**:无 manifest/解析失败时 Tier-1 退化为空,直接进 Tier-2(主包),不影响正确性。
- **tier 调度与 resume 的状态一致性**:tier 就绪标志须从落盘产物可靠推断(避免内存态丢失后误报),`main_ready` 以"主包全部 cls_idx 在 FTS 或 .java 齐"为准。
- **ripgrep 不在 PATH**:构建期跨相搜索退化为"仅 FTS 已索引部分"(给明确 note 提示装 ripgrep);完成后不受影响。
- **总时长不变**:本变更不缩短全量完成时间(~46min/2 会话照旧),只改"可用性曲线"。这是设计取舍,不是缺陷——速度另由减类(跳 T3)或(已搁置的)反编译并行度处理。
