# headless-jadx-server Specification

## Purpose

无头常驻 jadx 服务能力:在**无 GUI**环境下加载任意受支持输入(含抖音级大型 APK),构建常驻 `JadxDecompiler`,按需惰性逐类反编译,默认启用命名恢复,并在 **20 GB 内存上限**内运行(有界/磁盘 code cache + 流式/出堆纪律)。是 MCP 工具层(见 `mcp-re-toolset`)与代码搜索索引(见 `code-search-index`)的底座。

## Requirements

### Requirement: 无头加载任意 APK 至常驻服务

服务 SHALL 在**无 GUI**环境下加载任意受支持输入(`.apk/.dex/.aab/.xapk/...`,含抖音 281 MB / 55 dex 级大型 APK),构建常驻的 `JadxDecompiler` 与全部 `ClassNode` 结构,且**不依赖 jadx-gui**。加载允许耗时较长(分钟级),但加载完成后全部类 MUST 可枚举。

#### Scenario: 加载抖音并枚举全部类

- **WHEN** 调用 `load_apk` 指向抖音 APK(281 MB / 55 dex)
- **THEN** 加载在 20 GB 内完成(实测基线 ~254s),且 `get_all_classes` 能分页枚举到全部类(不因体量"加载不全")

#### Scenario: 切换 APK

- **WHEN** 再次 `load_apk` 指向另一个 APK
- **THEN** 释放上一个并加载新 APK,旧 APK 的缓存/索引不污染新会话

### Requirement: 惰性逐类反编译与内存纪律

服务 SHALL 按需逐类反编译(`JavaClass.getCode()` 触发),并使用**有界 / 磁盘 code cache**(MUST NOT 使用默认无界 `InMemoryCodeCache`),使长会话下堆占用不随已访问类数无界增长。usage/xref 索引在加载期建立,查询不再触发反编译。

#### Scenario: 长会话访问大量类不爆内存

- **WHEN** 一次会话中陆续取数千个类的源码
- **THEN** 已反编译文本进有界/磁盘缓存并按策略淘汰,堆占用保持在 20 GB 以内(不 OOM)

### Requirement: 命名恢复以最大化可读性

服务构造 `JadxArgs` 时 SHALL 默认启用名字恢复:`useSourceNameAsClassNameAlias = ALWAYS` + `kotlin-metadata` 插件;`deobf` SHALL 默认**关闭**并作为可选项(实测其对重混淆 app 不恢复语义且反而退化包名)。所有面向用户的反编译输出 MUST 与该配置一致。

#### Scenario: 混淆类带 SourceFile 时恢复可读名

- **WHEN** 一个被混淆的类仍保留 dex `SourceFile` 属性
- **THEN** 输出以源名作类别名(如 `i0` → `TextUtil2`),而非混淆短名

#### Scenario: deobf 默认不污染可读名

- **WHEN** 默认配置反编译抖音的类
- **THEN** 不出现 deobf 的 `p<num>`/`C<num>` 包名/类名退化(如 `com.ss` 不被改成 `com.p676ss`)

### Requirement: 在 20 GB 内存上限内运行

服务 SHALL 在 `-Xmx20g` 下完成加载与服务,峰值不触发 OutOfMemoryError。

#### Scenario: 抖音加载峰值在预算内

- **WHEN** 在 `-Xmx20g` 下加载抖音(可选叠加 deobf)
- **THEN** 峰值堆与 RSS 均 < 20 GB,无 OOM(实测:基线堆 12.7 GB / RSS 16.2 GB;+deobf 堆 13.9 GB / RSS 16.4 GB)
