## MODIFIED Requirements

### Requirement: 流式构建在内存预算内

索引构建 SHALL 流式进行(解一个类 → 落盘文本 → 分词入索引 → **彻底释放堆**),使构建期堆占用有界,叠加在加载基线之上仍 < 20 GB。"彻底释放堆" SHALL 清除单类反编译在 jadx 全局结构上的累积(如 `RootNode` 的 `ConstValues`),即采用 `deepUnload`/`unloadCode` 而非仅 `unload()`,以使单进程在 20 GB 内对**选定范围**(见"分析价值导向的选择性索引";或 `--index-all` 的全部类)达成 `coverage_complete=true`,无需多轮重载。构建可折叠进加载阶段或后台进行,并经 `index_status` 暴露进度与 `coverage_complete`。

#### Scenario: 构建抖音索引不爆内存

- **WHEN** 为抖音构建选定范围的全文索引
- **THEN** 反编译文本与索引落盘,堆占用保持有界(< 20 GB),不一次性把全部反编译文本驻留内存

#### Scenario: 单进程对选定范围达成全量覆盖

- **WHEN** 对一个 APK 的分析价值类(或 `--index-all` 全部类)构建索引,且每类反编译后彻底释放堆
- **THEN** 单进程一轮内对该范围达成 `coverage_complete=true`(不中途撞 `lowHeap` 守护停下);若仍超 20 GB,`index_status` 报告部分覆盖并支持重载续建,而非静默丢失

## ADDED Requirements

### Requirement: 分析价值导向的选择性索引

索引构建 SHALL 默认仅反编译/索引**有逆向分析价值**的类,按价值分层:T1 app 自身(AndroidManifest `package` + 同源厂商系前缀)与 T2 混淆包(单字母/极短段包名启发式)必索引;T4 标准库与常见 SDK(`android`/`androidx`/`java`/`javax`/`kotlin`/`kotlinx`/`com.google`/`com.android` 等)默认跳过。服务 SHALL 提供 `--index-include` / `--index-exclude` 包前缀以覆盖默认分层。

#### Scenario: 默认跳过标准库

- **WHEN** 加载一个普通 app 并以默认设置构建索引
- **THEN** android/androidx/kotlin/google 等标准库类不被反编译、不写入 FTS 索引,索引时间与体积相应下降

#### Scenario: 用户扩展索引范围

- **WHEN** 用户以 `--index-include com.somevendor` 指定关注的第三方库前缀
- **THEN** 该前缀下的类被纳入反编译/索引,即使它本不属 app 自身或混淆包

### Requirement: 真全量索引开关

服务 SHALL 提供 `--index-all` 开关,在其下索引全部类(含标准库),并依赖"流式构建在内存预算内"的彻底释放(`deepUnload`)+ 提高反编译并行度达成。`--index-all` MUST NOT 改变默认行为(默认仍为分析价值导向选择性索引)。

#### Scenario: 显式请求全量

- **WHEN** 以 `--index-all` 启动并对抖音级 APK 构建索引
- **THEN** 全部类(尽力)被反编译并索引;若 20 GB 内不能一轮覆盖,`index_status` 报告部分覆盖并支持重载续建,而非静默丢失结果
