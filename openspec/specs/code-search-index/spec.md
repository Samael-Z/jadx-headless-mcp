# code-search-index Specification

## Purpose

`search_in_code` 的持久化缓存 + 倒排索引能力:以 **dex 稳定身份**为结构键,流式构建(解→落盘→入库→释放堆)在内存预算内、按 APK 哈希一次构建跨重启复用,并在 rename 时增量失效。为 `mcp-re-toolset` 的全文代码搜索提供索引支撑,构建于 `headless-jadx-server` 之上。

## Requirements

### Requirement: search_in_code 持久化缓存与倒排索引

为在 60s 预算内支持全文代码搜索,服务 SHALL 维护**双层磁盘持久化**:① 反编译文本 code cache;② token 倒排索引(token → 类集合)。`search_in_code` SHALL 以索引查询实现,而非每次全量扫描。索引/缓存 SHALL 按 APK 内容哈希为键,一次构建、跨重启复用。

#### Scenario: 二次搜索走索引

- **WHEN** 索引建好后再次 `search_in_code`
- **THEN** 以倒排索引返回命中类(秒级),不重新反编译全部类

#### Scenario: 重启复用

- **WHEN** 服务重启后加载同一个 APK(哈希不变)
- **THEN** 直接复用已落盘的 cache/索引,无需重新反编译/重建

### Requirement: 以 dex 稳定身份为键

cache/索引的结构键 SHALL 使用 **dex 层稳定身份**(原始类型描述符 / dex 类索引 / 方法&字段 ref),而非 jadx 的显示名。显示名(source-name/kotlin-metadata/deobf/用户 rename)SHALL 作为挂在稳定键上的元数据层。改变名字恢复设置 MUST NOT 使结构索引整体失效(仅刷新显示名层)。

#### Scenario: 改命名设置不毁索引

- **WHEN** 切换 `deobf` 开/关或调整 source-name 策略
- **THEN** 结构倒排索引仍有效,仅显示名元数据需要刷新,无需全量重建

### Requirement: 流式构建在内存预算内

索引构建 SHALL 流式进行(解一个类 → 落盘文本 → 分词入索引 → 释放堆),使构建期堆占用有界,叠加在加载基线之上仍 < 20 GB。构建可折叠进(较久的)加载阶段或后台进行,并经 `index_status` 暴露进度。

#### Scenario: 构建抖音索引不爆内存

- **WHEN** 为抖音构建全文索引
- **THEN** 反编译文本与索引落盘,堆占用保持有界(< 20 GB),不一次性把全部反编译文本驻留内存

### Requirement: 改名增量失效

当发生 rename(用户改名或映射应用)时,服务 SHALL 仅对受影响的类增量更新 cache/索引,而非全量重建。

#### Scenario: 单类改名只增量更新

- **WHEN** `rename_class` 改了一个类
- **THEN** 仅该类(及直接引用它的类)的缓存文本/索引项被更新,其余条目不动
