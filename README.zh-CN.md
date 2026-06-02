# jadx-headless-mcp

[English](README.md) | **简体中文**

无头模式的 **JADX** 安卓反编译器，封装成 **Model Context Protocol (MCP)** 服务。用 Rust 写主体，保证冷启动快、单文件分发简单。让 Claude（或任何 MCP 客户端）直接逆向 `.apk`、`.dex`、`.aab`、`.xapk`、`.apkm` —— 列类、取反编译源码、交叉引用、改名、关键字搜索 —— 不需要拉起 JADX-GUI。

> 架构参考 [`blacktop/ida-mcp-rs`](https://github.com/blacktop/ida-mcp-rs)，这是它的 JADX 版本。

## 架构

```
┌──────────────┐  MCP stdio  ┌────────────────┐  本地回环 HTTP  ┌────────────────────┐
│  MCP 客户端  │ ──────────▶ │   jadx-mcp     │ ──────────────▶ │  jadx-bridge.jar   │
│  (Claude...) │             │ (Rust 二进制)  │  (端口=OS分配)   │  (JVM + JADX 内核) │
└──────────────┘             └────────────────┘                 └────────────────────┘
                                     │                                    │
                                     │ 通过 include_bytes! 把 jar 内嵌    │ JadxDecompiler
                                     ▼                                    ▼
                              每个 OS 一个二进制                读 .apk / .dex / .aab
```

- **Rust 二进制** (`jadx-mcp`)：MCP 服务本体。通过 `include_bytes!` 把 jadx-bridge JAR 内嵌进可执行文件，首次运行解压到用户缓存目录，作为子 JVM 进程拉起来，然后把每个 MCP 工具调用通过 `127.0.0.1` 转发过去。
- **Java sidecar** (`jadx-bridge.jar`)：薄壳 Javalin HTTP 服务，封装 `jadx.api.JadxDecompiler`。每个 APK 加载一次，伴随 MCP 服务的整个生命周期。基于 [`zinja-coder/jadx-ai-mcp`](https://github.com/zinja-coder/jadx-ai-mcp) 改造，剥掉了所有 GUI 依赖（`MainWindow`、`JadxWrapper`、调试器面板）。

打包后的 jar 不小（约 55 MB），但好处是二进制完全自包含 —— 用户机器上只要装了 `java`，`claude mcp add` 就能直接用，不需要再装 JADX。

## 系统要求

- **Java 11 或更新版本** 在 `PATH` 里（或者设置 `JAVA_HOME`）。可以 `java -version` 检查一下。
- 任意操作系统：macOS（Intel / Apple Silicon）、Linux（x86_64 / arm64）、Windows x86_64。

仅此而已 —— 终端用户不需要装 Python、Maven 或 JADX。

## 安装

### 方式一 —— `npx`（零安装，推荐）

只要装了 Node.js 18+：

```bash
claude mcp add jadx -- npx -y jadx-headless-mcp@latest
```

注册一次，分析任意 APK ——在对话里调 `load_apk` 工具指定要分析的文件即可，切换 APK 也是一句话，不需要改配置或重启 Claude。

npm 包是一个轻壳脚本，首次运行时从对应版本的 GitHub Release 下载平台二进制（约 52 MB）缓存到本地，之后再调用就直接用缓存。

> **为什么要 `@latest`？** 不带版本号的 `npx -y jadx-headless-mcp` 会**永远复用首次下载的版本**，不会主动去 npm registry 检查新版本 —— 这是 npx 的设计，不是本包的 bug。两种保持新版的写法：
>
> - `npx -y jadx-headless-mcp@latest` —— 每次启动都问一下 registry（启动多几百毫秒，但永远跟最新）。**推荐配置**。
> - `npx -y jadx-headless-mcp@0.3.2` —— 锁死某个具体版本，要升级时手动改配置。可控性更高。
>
> 如果你已经用裸 `jadx-headless-mcp`（无 `@latest`）注册过，想现在就升级，需要顺带清掉缓存：Windows 跑 `rm -rf "$LOCALAPPDATA/jadx-headless-mcp-npm"`，Linux/macOS 跑 `rm -rf ~/.cache/jadx-headless-mcp-npm`，然后重启 Claude Code。

### 方式二 —— 直接下载预编译二进制

从 [最新 release](https://github.com/Samael-Z/jadx-headless-mcp/releases/latest) 下载对应系统的压缩包：

| 操作系统 / 架构 | 文件 |
|---|---|
| Linux x86_64 | `jadx-headless-mcp-linux-x86_64.tar.gz` |
| Linux arm64 | `jadx-headless-mcp-linux-arm64.tar.gz` |
| macOS Intel | `jadx-headless-mcp-macos-x86_64.tar.gz` |
| macOS Apple Silicon | `jadx-headless-mcp-macos-arm64.tar.gz` |
| Windows x86_64 | `jadx-headless-mcp-windows-x86_64.zip` |

解压后把 `jadx-mcp` 放到 `PATH` 下的任意目录。

### 方式三 —— `cargo install`

```bash
# 需要 Rust 1.80+、JDK 11+ 和 Maven 在 PATH 中（会本地构建 sidecar JAR）
git clone https://github.com/Samael-Z/jadx-headless-mcp
cd jadx-headless-mcp
cd bridge && mvn -DskipTests package && cd ..
cargo install --path . --locked
```

## 注册到 MCP 客户端

### Claude Code CLI

```bash
claude mcp add jadx -- jadx-mcp
```

一份配置，任意 APK。对话里调 `load_apk` 工具指定文件；再调一次换另一个文件。

### Claude Desktop / Codex / 通用 stdio 配置

```json
{
  "mcpServers": {
    "jadx": {
      "command": "jadx-mcp"
    }
  }
}
```

如果 Desktop 应用的运行环境里 `java` 不在 PATH，可以设置 `JADX_MCP_JAVA` 环境变量，或者用 `--java /完整/路径/到/java` 显式传入。

### 在对话里使用

注册完后，直接说：

> 加载 `E:\\path\\to\\app.apk`，给我看包结构

Claude 会调 `load_apk`（首次约 30 秒，jadx 反编译耗时；APK 越大越久），然后调 `get_package_tree`。要切换 APK：

> 现在换成 `D:\\samples\\other.apk`，找包含"encrypt"的类

### 常用参数 / 环境变量

| 命令行参数 | 环境变量 | 用途 |
|---|---|---|
| `--apk PATH` | `JADX_MCP_APK` | **可选。** 启动时自动加载的 APK。等价于立刻调一次 `load_apk`。如果只分析一个 APK 可以用，多 APK 场景不推荐 |
| `--java PATH` | `JADX_MCP_JAVA` | 覆盖 Java 可执行文件路径。默认顺序：`$JAVA_HOME/bin/java` → `PATH` |
| `--bridge-jar PATH` | `JADX_MCP_BRIDGE_JAR` | 使用其他 bridge JAR（比如本地构建的）。默认用内嵌的 |
| `--jvm-arg ARG` | `JADX_MCP_JVM_ARGS` | 额外的 JVM 参数。默认堆 `-Xmx2g`（作为基线；你自己传的 `-Xmx` 会覆盖它）。命令行要用 `=` 写法（`--jvm-arg=-Xmx4g`）；环境变量里多个参数用空格分隔。详见下方[配置 JVM 内存](#配置-jvm-内存) |
| `--bridge-startup-secs N` | — | 等待 bridge 完成 APK 加载的超时秒数。默认 600 |
| `--bridge-host`, `--bridge-port` | — | 覆盖 bridge 的回环监听地址。默认 `127.0.0.1:0`（OS 自动分配） |
| — | `JADX_MCP_LOG` | `tracing` 过滤器，比如 `debug,reqwest=warn`。默认 `info` |

### 配置 JVM 内存

bridge 的 JVM 默认最大堆为 **2 GB**（`-Xmx2g`）。分析超大 APK 时，可以通过 `JADX_MCP_JVM_ARGS` 或 `--jvm-arg` 调大。

在 MCP 配置里最干净的方式是用 `env` 块：

```json
{
  "mcpServers": {
    "jadx": {
      "command": "npx",
      "args": ["-y", "jadx-headless-mcp@latest"],
      "env": { "JADX_MCP_JVM_ARGS": "-Xmx4g" }
    }
  }
}
```

命令行上要用 `=` 写法——用空格（`--jvm-arg -Xmx4g`）会让解析器把 `-Xmx4g` 当成另一个 flag 而报错：

```bash
jadx-mcp --jvm-arg=-Xmx4g
```

2 GB 是**基线默认值**：你传自己的 `-Xmx` 会覆盖堆大小，其他参数则是叠加——比如 `JADX_MCP_JVM_ARGS="-Xmx4g -Xss2m"` 会保留 4 GB 堆并加上 2 MB 线程栈。多个参数用空格分隔。

## 暴露的工具

六大类共 32 个工具。在客户端里运行 `tools/list` 可以看到完整 schema —— 下表只列了简介。

| 类别 | 工具 |
|---|---|
| **会话** | `load_apk`、`current_apk` |
| **类相关** | `get_all_classes`、`get_class_source`、`get_class_sources`（批量）、`get_methods_of_class`、`get_fields_of_class`、`get_smali_of_class`、`get_main_activity_class`、`get_main_application_classes_names`、`get_main_application_classes_code`、`get_package_tree`、`search_classes_by_keyword`（子串或 `regex=true`）、`search_string_constants`、`get_cache_stats`、`clear_cache`、`index_status` |
| **方法相关** | `get_method_by_name`、`search_method_by_name` |
| **资源相关** | `get_android_manifest`、`get_strings`、`get_all_resource_file_names`、`get_resource_file` |
| **交叉引用** | `get_xrefs_to_class`、`get_xrefs_to_method`、`get_xrefs_to_field`、`get_xrefs_from_method`、`get_xrefs_from_class` |
| **重命名** | `rename_class`、`rename_method`、`rename_field`、`rename_package` |

典型分析流程：

```
load_apk { path: "/绝对路径/到/app.apk" }          → 加载 APK（约 30 秒）
get_package_tree                                  → 看 APK 整体结构，找到应用包名
get_main_application_classes_names                → 列出业务方代码（过滤掉三方库）
get_class_source { class_name: "..." }            → 看反编译源码（get_class_sources 可批量）
search_classes_by_keyword { search_term, ... }    → 跨包全文 / 正则搜索
search_string_constants { query: "https://" }      → 枚举内嵌 URL / 密钥及所属类
get_xrefs_to_method { class_name, method_name }   → 追调用链（get_xrefs_from_* 查出边）
load_apk { path: "/另一个/app.apk" }               → 会话内切换 APK
```

**重命名跨重启持久化** —— 通过 jadx 的 code-data 机制应用，并 journal 到 APK 旁的 `.jadx-mcp-cache/<apk>.renames.json`，下次加载该 APK 时自动 replay。

## 从源码构建

```bash
git clone https://github.com/Samael-Z/jadx-headless-mcp
cd jadx-headless-mcp

# 1. Java sidecar（需要 JDK 11+ 和 Maven）
cd bridge && mvn -DskipTests package && cd ..

# 2. Rust 二进制（需要 Rust 1.80+）
cargo build --release

# 产物在 target/release/jadx-mcp（Windows 是 .exe）
```

用 `apks/` 下的 APK 做端到端冒烟测试：

```bash
./scripts/smoke_test.sh ./apks/你的.apk
```

构建脚本（`build.rs`）默认从 `bridge/target/jadx-bridge.jar` 找 jar。要用其他地方的预构建 jar，在 `cargo build` 之前设置 `BRIDGE_JAR_PATH` 环境变量。

## 当前不支持的功能

- **不包含** JADX-GUI 的调试器功能（`debug_get_stack_frames` 等）—— 这些需要活动的 GUI，已经移除。
- **不暴露** `fetch_current_class` / `get_selected_text` —— 无 UI 环境下没有"当前选中"的概念，请直接把 FQN 传给 `get_class_source`。
- 变量重命名仍不支持（需要 GUI 的类重载耦合）。类 / 方法 / 字段 / 包重命名已生效且**跨重启持久化**（经 jadx code-data 应用，journal 到 APK 旁）。

## 致谢

- [`skylot/jadx`](https://github.com/skylot/jadx) —— 真正干活的反编译器。
- [`zinja-coder/jadx-ai-mcp`](https://github.com/zinja-coder/jadx-ai-mcp) —— 原始的 GUI 插件，本项目把它的路由代码改成了无头版本。
- [`blacktop/ida-mcp-rs`](https://github.com/blacktop/ida-mcp-rs) —— "Rust MCP 服务封装外部分析器" 的架构范式。

## 许可证

Apache-2.0（继承自 `skylot/jadx`）。
