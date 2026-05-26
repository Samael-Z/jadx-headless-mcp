# jadx-handless-mcp

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

### 方式一 —— 直接下载预编译二进制（推荐）

从 [最新 release](https://github.com/Samael-Z/jadx-handless-mcp/releases/latest) 下载对应系统的压缩包：

| 操作系统 / 架构 | 文件 |
|---|---|
| Linux x86_64 | `jadx-handless-mcp-linux-x86_64.tar.gz` |
| Linux arm64 | `jadx-handless-mcp-linux-arm64.tar.gz` |
| macOS Intel | `jadx-handless-mcp-macos-x86_64.tar.gz` |
| macOS Apple Silicon | `jadx-handless-mcp-macos-arm64.tar.gz` |
| Windows x86_64 | `jadx-handless-mcp-windows-x86_64.zip` |

解压后把 `jadx-mcp` 放到 `PATH` 下的任意目录。

### 方式二 —— `cargo install`

```bash
# 需要 Rust 1.80+、JDK 11+ 和 Maven 在 PATH 中（会本地构建 sidecar JAR）
git clone https://github.com/Samael-Z/jadx-handless-mcp
cd jadx-handless-mcp
cd bridge && mvn -DskipTests package && cd ..
cargo install --path . --locked
```

## 注册到 MCP 客户端

### Claude Code CLI

```bash
claude mcp add jadx -- jadx-mcp --apk /绝对路径/到/你的.apk
```

如果要同时分析多个 APK，用不同的名字注册：

```bash
claude mcp add jadx-app -s project -- jadx-mcp --apk ./apks/app.apk
claude mcp add jadx-sample -s user -- jadx-mcp --apk ~/samples/sample.apk
```

### Claude Desktop / Codex / 通用 stdio 配置

```json
{
  "mcpServers": {
    "jadx": {
      "command": "jadx-mcp",
      "args": ["--apk", "/绝对路径/到/你的.apk"]
    }
  }
}
```

如果 Desktop 应用的运行环境里 `java` 不在 PATH，可以设置 `JADX_MCP_JAVA` 环境变量，或者用 `--java /完整/路径/到/java` 显式传入。

### 常用参数 / 环境变量

| 命令行参数 | 环境变量 | 用途 |
|---|---|---|
| `--apk PATH` | `JADX_MCP_APK` | 要加载的 APK / DEX / AAB / XAPK / APKM / JAR 文件。**必填** |
| `--java PATH` | `JADX_MCP_JAVA` | 覆盖 Java 可执行文件路径。默认顺序：`$JAVA_HOME/bin/java` → `PATH` |
| `--bridge-jar PATH` | `JADX_MCP_BRIDGE_JAR` | 使用其他 bridge JAR（比如本地构建的）。默认用内嵌的 |
| `--jvm-arg ARG` | `JADX_MCP_JVM_ARGS` | 额外的 JVM 参数。默认 `-Xmx2g`。可以多次指定 |
| `--bridge-startup-secs N` | — | 等待 bridge 完成 APK 加载的超时秒数。默认 600 |
| `--bridge-host`, `--bridge-port` | — | 覆盖 bridge 的回环监听地址。默认 `127.0.0.1:0`（OS 自动分配） |
| — | `JADX_MCP_LOG` | `tracing` 过滤器，比如 `debug,reqwest=warn`。默认 `info` |

## 暴露的工具

五大类共 24 个工具。在客户端里运行 `tools/list` 可以看到完整 schema —— 下表只列了简介。

| 类别 | 工具 |
|---|---|
| **类相关** | `get_all_classes`、`get_class_source`、`get_methods_of_class`、`get_fields_of_class`、`get_smali_of_class`、`get_main_activity_class`、`get_main_application_classes_names`、`get_main_application_classes_code`、`get_package_tree`、`search_classes_by_keyword`、`get_cache_stats`、`clear_cache` |
| **方法相关** | `get_method_by_name`、`search_method_by_name` |
| **资源相关** | `get_android_manifest`、`get_strings`、`get_all_resource_file_names`、`get_resource_file` |
| **交叉引用** | `get_xrefs_to_class`、`get_xrefs_to_method`、`get_xrefs_to_field` |
| **重命名** | `rename_class`、`rename_method`、`rename_field`、`rename_package` |

典型分析流程：

```
get_package_tree                                  → 看 APK 整体结构，找到应用包名
get_main_application_classes_names                → 列出业务方代码（过滤掉三方库）
get_class_source { class_name: "..." }            → 看反编译源码
get_xrefs_to_method { class_name, method_name }   → 追调用链
search_classes_by_keyword { search_term, ... }    → 跨包全文搜索
```

**重命名仅在内存生效** —— 当前会话里的后续工具调用能看到改名结果，但不会持久化到磁盘。

## 从源码构建

```bash
git clone https://github.com/Samael-Z/jadx-handless-mcp
cd jadx-handless-mcp

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
- 重命名暂不持久化到 `.jobf` mappings 文件。集成 `jadx-rename-mappings` 插件在 roadmap 上。

## 致谢

- [`skylot/jadx`](https://github.com/skylot/jadx) —— 真正干活的反编译器。
- [`zinja-coder/jadx-ai-mcp`](https://github.com/zinja-coder/jadx-ai-mcp) —— 原始的 GUI 插件，本项目把它的路由代码改成了无头版本。
- [`blacktop/ida-mcp-rs`](https://github.com/blacktop/ida-mcp-rs) —— "Rust MCP 服务封装外部分析器" 的架构范式。

## 许可证

Apache-2.0（继承自 `skylot/jadx`）。
