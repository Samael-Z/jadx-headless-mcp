# jadx-headless-mcp

[![build](https://github.com/Samael-Z/jadx-headless-mcp/actions/workflows/build.yml/badge.svg)](https://github.com/Samael-Z/jadx-headless-mcp/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/Samael-Z/jadx-headless-mcp?sort=semver)](https://github.com/Samael-Z/jadx-headless-mcp/releases)
[![Java](https://img.shields.io/badge/Java-17%2B-blue)](#环境要求)
[![license](https://img.shields.io/badge/license-Apache--2.0-green)](#许可与致谢)

[English](README.md) · **简体中文**

一个**单进程、无头的 [jadx](https://github.com/skylot/jadx) 反编译器 + MCP 服务**,为**大型 Android 应用的逆向分析(RE)**而生(如抖音:295 MB / 55 dex),在 **20 GB 堆**内运行,**每次 MCP 工具调用 ≤ 60s**。通过官方 **Model Context Protocol**——**stdio**(客户端拉起)或 **Streamable HTTP**——向 LLM 客户端暴露 32 个 RE 工具。

> 这是 **v1.0 Java 重写版**,取代早期的 Rust-bridge 实现(`v0.x` tags / `dev` 分支),彻底去掉跨语言桥接与 GUI 依赖。

---

## 目录

- [为什么做这个](#为什么做这个)
- [核心特性](#核心特性)
- [架构](#架构)
- [项目层级结构](#项目层级结构)
- [环境要求](#环境要求)
- [安装 / 构建](#安装--构建)
- [使用](#使用)
- [接入 MCP 客户端](#接入-mcp-客户端)
- [支持的 MCP 工具](#支持的-mcp-工具)
- [大型 APK 行为与内存](#大型-apk-行为与内存)
- [版本迭代记录](#版本迭代记录)
- [许可与致谢](#许可与致谢)

---

## 为什么做这个

现有方案都不满足逆向分析需求:

- **嵌在 GUI 里的插件**(如 jadx-ai-mcp)在没有 GUI 上下文时直接退出——**无法无头运行**。
- **早期的 `jadx-headless-mcp`** 靠 *grep smali 文本* 定位信息,逼着每个类都反编译(**极慢**);加载抖音这类大型 APK 时**加载不全**,MCP 能力等同失效。

本项目**为 RE 重做**:加载任意 APK(含抖音),所有工具稳定快速,**单次调用 ≤ 60s**(加载可以久一次),控制在 **20 GB** 堆内。

## 核心特性

- 🧩 **单 JVM,无桥接,无 Python。** jadx-core 与 MCP 服务同进程;MCP 走 **stdio**(客户端拉起,`--stdio`)或 **Streamable HTTP**(内嵌 Jetty 绑 `127.0.0.1`;非弃用的 SSE)。
- 🧠 **xref 出堆。** 自定义 `IUsageInfoCache` 把 jadx 的 usage 图导出到 **SQLite**(符号 + 边);`get_xrefs_*` / `get_call_graph` 全查 SQL,从不碰堆内 `getUseIn()`。抖音上是 **456 万符号 / 2950 万边**全部出堆。
- 🔎 **索引化代码搜索。** `search_in_code` 用 **SQLite FTS5 trigram** 索引反编译文本(完整正则用 **ripgrep** 兜底),查询不再全量重扫。
- 💾 **有界 + 磁盘 code cache。** 有界堆内 LRU 套磁盘缓存,取代 jadx 无界的 `InMemoryCodeCache`,长会话堆占用不无限增长;跨重启复用。
- 🏷️ **dex 稳定键。** 索引以原始 DEX 描述符为键,改命名设置 / 改名都不会让结构索引失效,只刷新显示名层。
- 🔁 **可恢复的流式构建。** 索引后台构建并报进度(`index_status`);部分构建仍可搜,**下次加载续建扩展覆盖**。
- 📛 **默认可读名字。** `useSourceNameAsClassNameAlias=ALWAYS` + kotlin-metadata;`deobf` **默认关**(对重混淆 app 反而退化包名)。

## 架构

单 JVM,三层:

```
 LLM 客户端 ──Streamable HTTP──▶  MCP 服务 (官方 Java SDK)  ──进程内──▶  jadx-core
                (Jetty 12, /mcp)     32 个工具 → JadxService                  (反编译器)
                                            │
                                            ├─▶ SQLite 符号+边图谱     (xref 出堆, D7)
                                            ├─▶ SQLite FTS5 trigram    (search_in_code, D6)
                                            └─▶ 有界 LRU → 磁盘 code cache (惰性反编译, D2)
```

一个 APK 派生的所有东西都放在 `<JADX_CACHE_DIR>/<apk-hash>/`(磁盘 code cache + `index.db`),APK 哈希一致时跨重启复用。

**关键设计决策**

| # | 决策 |
|---|---|
| D1 | 单进程 Java;官方 MCP Java SDK + **Streamable HTTP**;仅 localhost、无鉴权 |
| D2 | 常驻 + 惰性反编译;**有界 LRU 套磁盘 code cache**(禁用无界 `InMemoryCodeCache`) |
| D4 | 命名 = source-name `ALWAYS` + kotlin-metadata;**`deobf` 默认关** |
| D5 | 缓存/索引键用 **dex 稳定身份**;显示名只是元数据层 |
| D6 | 一个本地 **SQLite**:符号/边图谱 + FTS5 `trigram`,由 jadx 喂数据;**ripgrep** 兜底正则 |
| D7 | **xref/usage 经 `IUsageInfoCache` + `visitUsageData` 导出 SQLite(出堆)** |

## 项目层级结构

```
jadx-headless-mcp/                    (main 分支 = Java 重写版)
├── pom.xml                           Maven 构建;shade fat jar,Java 17 target
├── settings.xml                      Maven 镜像(绕本地代理用;CI 不使用)
├── .github/workflows/build.yml       CI:构建 jar、上传 artifact、打 v* tag 时建 Release
└── src/main/
    ├── resources/simplelogger.properties   日志 → stderr(stdout 保持干净)
    └── java/com/zin/jadxheadless/
        ├── Main.java                 入口 + 命令行参数(--apk/--port/--deobf/--selftest)
        ├── SelfTest.java             无头端到端自检(--selftest)
        ├── jadx/                     ── 无头 jadx 核心(headless-jadx-server)──
        │   ├── JadxService.java          加载/生命周期、JadxArgs、类查找、改名 journal
        │   ├── DiskCodeCache.java        磁盘 ICodeCache(只存源码、跨重启复用)
        │   ├── BoundedCodeCache.java     磁盘缓存前的有界堆内 LRU
        │   └── SqliteUsageInfoCache.java 自定义 IUsageInfoCache——xref 导出的捕获点
        ├── index/                    ── 统一 SQLite 索引(code-search-index)──
        │   ├── Db.java                   SQLite 连接、WAL、schema、读写连接分离
        │   ├── SymbolGraph.java          符号 + 边 DAO;出堆 xref/调用图查询
        │   ├── SqliteExportVisitor.java  IUsageInfoVisitor → 边表(usage → SQLite)
        │   ├── CodeSearchIndex.java      FTS5 trigram + 字符串常量搜索 + ripgrep 兜底
        │   ├── IndexBuilder.java         流式、堆有界、可恢复的后台构建
        │   └── IndexStatus.java          进度/覆盖状态(index_status)
        ├── mcp/                       ── MCP 工具层(mcp-re-toolset)──
        │   ├── McpToolServer.java        Jetty + Streamable HTTP 传输 + MCP 同步 server
        │   ├── ToolRegistry.java         在 JadxService 之上声明 32 个工具
        │   └── Tools.java                工具声明辅助(schema + 结果/错误封装)
        └── util/                      ── 公共工具 ──
            ├── CacheLayout.java          按 APK 的缓存目录(<JADX_CACHE_DIR>/<apk-hash>/)
            ├── DexId.java                类/方法/字段的 dex 稳定身份(D5)
            ├── ManifestUtil.java         AndroidManifest:包名 / 启动 Activity / 原始 XML
            ├── Pagination.java           枚举类工具的 offset/limit 分页
            ├── Json.java                 工具结果用的零依赖 JSON 写出
            └── RenameStore.java          改名 journal(TSV;重载时回放)
```

## 环境要求

- **JDK 17+**(Jetty 12 与 MCP SDK 需要 17;jadx 制品是 Java 11 字节码,向前兼容)。
- 从源码构建需 **Maven 3.9+**。
- 堆:大型 app 用 `-Xmx20g`(小 app 更省)。`ripgrep`(`rg`)在 `PATH` 上是可选的(启用 `search_in_code` 的正则兜底)。

## 安装 / 构建

### 方式 A —— 下载 release

从 [Releases](https://github.com/Samael-Z/jadx-headless-mcp/releases) 页面下载 `jadx-headless-mcp-v2.jar`(CI 在每个 `v*` tag 上自动附带)。

### 方式 B —— 从源码构建

```bash
git clone https://github.com/Samael-Z/jadx-headless-mcp.git
cd jadx-headless-mcp
mvn -s settings.xml package          # → target/jadx-headless-mcp-v2.jar(~68 MB,自包含)
```

`settings.xml` 把 Maven 走镜像,是为了绕开**本地**代理的 TLS 重置;正常网络(以及 CI)里可以去掉 `-s settings.xml` 直连 Maven Central。构建会 shade 所有依赖(jadx-all、Jetty、MCP SDK、sqlite-jdbc)并合并 `META-INF/services`,让所有 ServiceLoader(jadx 输入插件、kotlin-metadata、MCP 的 JSON mapper)都能被发现。

## 使用

两种传输。客户端能拉起进程时(如 Claude Code)用 **stdio** 最简单;**HTTP**(默认)是你自己起的常驻服务,客户端连它。

```bash
# HTTP(Streamable HTTP)——你自己起的常驻服务,客户端连 URL
java -Xmx20g -Djava.awt.headless=true -jar jadx-headless-mcp-v2.jar \
     --host 127.0.0.1 --port 8650 [--apk <apk路径>] [--deobf]

# stdio——客户端拉起并持有进程;目标 APK 运行时用 load_apk 加载
java -Xmx20g -Djava.awt.headless=true -jar jadx-headless-mcp-v2.jar --stdio
```

| 参数 | 默认 | 含义 |
|---|---|---|
| `--host` | `127.0.0.1` | 绑定地址(保持 localhost——**无鉴权**;远程请走 SSH 隧道) |
| `--port` | `8650` | HTTP 端口;MCP 端点是 `http://<host>:<port>/mcp` |
| `--apk` | — | 启动时加载的 APK/DEX/AAB/XAPK/APKM/JAR(也可之后调 `load_apk`) |
| `--deobf` | 关 | 开 jadx deobf(重混淆 app 建议关) |
| `--stdio` | — | 用 stdin/stdout 跑 MCP(而非 HTTP)——给会拉起进程的客户端(如 Claude Code `"type": "stdio"`)。无端口;日志走 stderr。目标 APK 运行时用 `load_apk` 加载。 |
| `--selftest` | — | 对 `--apk` 跑无头端到端自检后退出 |

- 缓存根默认 `E:\JADX_CACHE_DIR`;用环境变量 `JADX_CACHE_DIR` 或 `-Djadx.cache.dir=...` 覆盖。
- 自检(不需要 MCP 客户端):
  `java -Xmx20g -jar jadx-headless-mcp-v2.jar --selftest --apk app.apk`
  (`JADX_SELFTEST_WAIT_MS` 控制等后台索引的时长)。

## 接入 MCP 客户端

**stdio**——客户端拉起进程(Claude Code 推荐;目标 APK 运行时用 `load_apk` 加载):

```jsonc
{
  "mcpServers": {
    "jadx-headless-mcp-v2": {
      "type": "stdio",
      "command": "java",
      "args": ["-Xmx20g", "-Djava.awt.headless=true", "-jar",
               "/abs/path/to/jadx-headless-mcp-v2.jar", "--stdio"]
    }
  }
}
```

**Streamable HTTP**——你自己起服务(见"使用"),再把客户端指向 URL(不会拉起进程):

```jsonc
{
  "mcpServers": {
    "jadx-headless": {
      "type": "streamable-http",
      "url": "http://127.0.0.1:8650/mcp"
    }
  }
}
```

典型流程:`load_apk` → `get_package_tree` → 用 `search_string_constants` / `get_class_source` / `get_xrefs_to_*` 钻进去。`search_in_code` 是否就绪看 `index_status`。

## 支持的 MCP 工具

共 32 个,按代价分层。**Tier-1** 瞬时(结构 / 字符串池 / SQLite xref);**Tier-2** 单类惰性反编译;**Tier-3** 索引/后台支撑。GUI 专属工具(`get_selected_text`、`fetch_current_class`)与 smali `debug_*` 组**故意不提供**(无头无意义)。

### 会话
| 工具 | 说明 |
|---|---|
| `load_apk` | 加载/切换 APK;启动后台索引构建 |
| `current_apk` | 当前 APK + 类数 + 索引状态 |
| `index_status` | 后台构建进度(percent / coverage_complete / symbols / edges) |
| `clear_cache` | 清堆内缓存(磁盘索引保留) |

### 结构枚举(Tier-1)
| 工具 | 说明 |
|---|---|
| `get_all_classes` | 全部类 FQN(分页) |
| `get_package_tree` | 各包及类数 |
| `get_methods_of_class` | 方法(名 + 描述符 + 返回类型) |
| `get_fields_of_class` | 字段(名 + 类型) |

### 单类(Tier-2)
| 工具 | 说明 |
|---|---|
| `get_class_source` | 反编译 Java(命中磁盘缓存则瞬时) |
| `get_smali_of_class` | Smali 字节码 |
| `get_method_by_name` | 定位方法 + 尽力切出方法代码 |

### 字符串 —— RE 主力(Tier-1)
| 工具 | 说明 |
|---|---|
| `search_string_constants` | 在 const-string 字面量里子串搜索 → 类 |
| `find_string_usages` | 含**完整**字符串字面量的类(精确匹配;子串搜索用 `search_string_constants`) |
| `get_strings` | Android `strings.xml` 资源 |

### xref —— 出堆 SQLite(Tier-1)
| 工具 | 说明 |
|---|---|
| `get_xrefs_to_class` | 引用某类的类 |
| `get_xrefs_to_method` | 某方法的调用方 |
| `get_xrefs_from_method` | 某方法的被调方 |
| `get_xrefs_to_field` | 读写某字段的方法 |
| `get_xrefs_from_class` | 某类调用到的类 |
| `get_call_graph` | 某类的直接被调类 |
| `get_subclasses` | 直接子类 / 接口实现类 |

### 资源
| 工具 | 说明 |
|---|---|
| `get_android_manifest` | 原始 AndroidManifest.xml |
| `get_main_activity` | 启动 Activity 全名 |
| `list_resource_files` | 资源文件名(分页) |
| `get_resource_file` | 资源文本内容 |

### 改名(落 journal,重载回放)
| 工具 | 说明 |
|---|---|
| `rename_class` / `rename_method` / `rename_field` / `rename_package` | 持久化改名 |

### 代码 & 名字搜索
| 工具 | 说明 |
|---|---|
| `search_in_code` | 全文(FTS5 trigram;正则走 ripgrep)——Tier-3,索引支撑 |
| `search_classes_by_keyword` | 含关键字的类 FQN(瞬时) |
| `search_method_by_name` | 按方法名子串(model 扫描,有上限) |

## 大型 APK 行为与内存

`-Xmx20g`、AWT 无头、经 `--selftest` 实测:

| App | 类数(含内部类) | 加载 | xref 图谱(SQLite) | 峰值堆 |
|---|---|---|---|---|
| 滴滴 132 MB | 94,281 | 25 s | 950,877 符号 / 370 万边 | **8.7 GB** |
| 抖音 295 MB | 493,376 | 125 s | 4,564,540 符号 / **2950 万边** | **18.3–19.1 GB** |

- **抖音上加载 + 完整出堆 xref 图谱在 20 GB 内**,类源码 / 字符串 / xref / manifest / MCP-over-HTTP 都正确且快——这条 RE 主力链路从第一次加载即完整。
- **`search_in_code` 增量构建。** 为喂 FTS 索引把*每个类*都反编译,比加载重得多——jadx 会累积逐类内部状态,所以单个 20 GB pass 覆盖数万类(main package 优先,即 app 自身代码)后触低堆守护停下。索引对已覆盖子集保持 **READY/可搜**(`coverage_complete=false`),**重新加载同一 APK 会续建**——跳过 structure/usage 阶段和已索引类、继续扩展覆盖。多次加载逼近全量,或调高 `-Xmx`。
- 磁盘 code cache + SQLite 索引**跨重启复用**(同 APK 哈希)。完整索引会瞬时挂载、不重建。

## 版本迭代记录

| 版本 | 日期 | 说明 |
|---|---|---|
| **v1.1.0** | 2026-06-08 | **stdio 传输**(`--stdio`):MCP 客户端(如 Claude Code)拉起并持有进程,目标 APK 运行时用 `load_apk` 加载——HTTP 保留为默认。**修复**:`index_status` 在从磁盘复用完整索引时回填 `symbols`/`edges`/`const_strings`(此前显示 0,但数据一直都在)。 |
| **v1.1.1** | 2026-06-08 | **字符串工具清理:** `find_string_usages` 改为仅精确匹配(完整字面量);子串搜索归 `search_string_constants`(FTS 加速)。移除重叠的 `contains` 选项及其慢速 `LIKE` 全表扫,两个字符串工具职责正交。 |
| **v1.0.1** | 2026-06-08 | 版本号对齐;**CI/CD**(GitHub Actions:构建 fat jar、上传 artifact、打 `v*` tag 时把 jar 附到 Release)。 |
| **v1.0.0** | 2026-06-08 | **单进程 Java 重写首个版本。** 出堆 SQLite xref、FTS5 trigram 代码搜索、有界/磁盘 code cache、可恢复索引、官方 MCP SDK over Streamable HTTP。在滴滴 + 抖音上 `-Xmx20g` 内验证。 |
| `v0.x` | — | 旧的 Rust-bridge 实现(经 `dev` 分支与 `v0.x` tags 可找回)。被 v1.0 取代。 |

## 许可与致谢

- 采用 **Apache License 2.0**,与上游 jadx 一致。
- 基于 [skylot/jadx](https://github.com/skylot/jadx)(反编译引擎,编译时依赖发布的 `io.github.skylot:jadx-*` 制品)与官方 [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk)。
- 是早期 Rust-bridge 版 `jadx-headless-mcp` 的全新 Java 重构。
