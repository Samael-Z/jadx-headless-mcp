//! Request structs for every MCP tool. Kept in one module — they're small,
//! and grouping them with rmcp's #[tool] handlers in server.rs would just
//! make that file even longer.

use schemars::JsonSchema;
use serde::Deserialize;

#[derive(Debug, Deserialize, JsonSchema)]
pub struct LoadApkReq {
    /// Absolute path to the APK / DEX / AAB / XAPK / APKM / JAR to load. \
    /// If another file is already loaded, it is unloaded first.
    pub path: String,
}

#[derive(Debug, Deserialize, JsonSchema)]
pub struct ClassNameReq {
    /// Fully qualified class name, e.g. "com.example.MainActivity".
    pub class_name: String,
    /// Max characters of source/smali to return (default 120000; 0 = unlimited). Huge classes
    /// are truncated with a marker so a single class can't overflow the client; raise to fetch more.
    #[serde(default)]
    pub max_chars: Option<u32>,
}

#[derive(Debug, Deserialize, JsonSchema)]
pub struct PaginationReq {
    /// Starting index (default 0).
    #[serde(default)]
    pub offset: Option<u32>,
    /// Number of items to return (default: server-defined, 0 = all).
    #[serde(default)]
    pub count: Option<u32>,
}

#[derive(Debug, Deserialize, JsonSchema)]
pub struct MethodByNameReq {
    /// Method name to look up.
    pub method_name: String,
    /// Optional class filter. If omitted, the first matching method across all classes is returned.
    #[serde(default)]
    pub class_name: Option<String>,
}

#[derive(Debug, Deserialize, JsonSchema)]
pub struct SearchMethodReq {
    /// Substring to search for (case-insensitive).
    pub method_name: String,
}

#[derive(Debug, Deserialize, JsonSchema)]
pub struct SearchClassesReq {
    /// The keyword to search for.
    pub search_term: String,
    /// Optional package prefix filter (e.g. "com.example.app"). Empty = all packages.
    #[serde(default)]
    pub package: Option<String>,
    /// Comma-separated list of search scopes: class,method,field,code,comment. Defaults to "code".
    #[serde(default)]
    pub search_in: Option<String>,
    #[serde(default)]
    pub offset: Option<u32>,
    #[serde(default)]
    pub count: Option<u32>,
    /// Wall-clock budget in milliseconds (default 25000) for the scan-backed paths. While the
    /// pre-decompiled Java index is still warming up (poll `index_status`), code/comment search
    /// falls back to a bounded live decompile scan that stops at this budget and returns partial
    /// results with `timed_out: true`. Once the index is warm, code search is sub-second. Prefer
    /// `search_in=class` (fast) when you can; raise this only for exhaustive code sweeps.
    #[serde(default)]
    pub timeout_ms: Option<u64>,
    /// Treat `search_term` as a Java regular expression (matched with `.find()`), applied to
    /// every selected location. Default false (plain substring). Enables code patterns like
    /// `Cipher\.getInstance\("[^"]+"\)` or class-name patterns.
    #[serde(default)]
    pub regex: Option<bool>,
    /// Match case-sensitively. Default false (case-insensitive), matching non-regex behavior.
    #[serde(default)]
    pub case_sensitive: Option<bool>,
}

#[derive(Debug, Deserialize, JsonSchema)]
pub struct FindStringUsagesReq {
    /// The string literal to search for. Matched via a bounded live scan (no global index):
    /// against decompiled Java source and/or each class's smali const-string opcodes (see `source`).
    pub literal: String,
    /// Which source(s) to search:
    /// - "code": scans jadx-decompiled Java/Kotlin source (cached, fast on repeat, but returns 0
    ///   hits on hardened classes jadx can't decompile).
    /// - "smali": scans each class's `const-string vN, "<literal>"` opcodes. Works on
    ///   R8/anti-tamper hardened classes whose decompile is empty.
    /// - "both" (default): report classes matched by either source. `matched_in` in the response
    ///   tells you which.
    #[serde(default)]
    pub source: Option<String>,
    /// Code-path only. If false, match the raw substring instead of the
    /// double-quoted form. Useful when looking for a regex component or a
    /// string built by concatenation. Default: true.
    #[serde(default)]
    pub quoted: Option<bool>,
    /// If false, match case-insensitively. Default: true.
    #[serde(default)]
    pub case_sensitive: Option<bool>,
    /// Optional package prefix filter (e.g. "com.example.app").
    #[serde(default)]
    pub package: Option<String>,
    #[serde(default)]
    pub offset: Option<u32>,
    #[serde(default)]
    pub count: Option<u32>,
    /// Wall-clock budget in milliseconds for the scan (default 25000). On a
    /// huge APK (100k+ classes) a full string scan can take minutes; instead of
    /// blocking, the scan stops at this budget and returns whatever it found so
    /// far with `timed_out: true` (and `scanned`/`total_classes` so you can see
    /// coverage). Raise it for an exhaustive sweep, or just page with `offset`.
    #[serde(default)]
    pub timeout_ms: Option<u64>,
}

#[derive(Debug, Deserialize, JsonSchema)]
pub struct ResourceFileReq {
    /// Resource path inside the APK, e.g. "res/xml/network_security_config.xml".
    pub resource_name: String,
    /// Max characters to return (default 120000; 0 = unlimited). Large resources are truncated.
    #[serde(default)]
    pub max_chars: Option<u32>,
}

#[derive(Debug, Deserialize, JsonSchema)]
pub struct XrefsClassReq {
    pub class_name: String,
    #[serde(default)]
    pub offset: Option<u32>,
    #[serde(default)]
    pub count: Option<u32>,
}

#[derive(Debug, Deserialize, JsonSchema)]
pub struct XrefsMethodReq {
    pub class_name: String,
    pub method_name: String,
    #[serde(default)]
    pub offset: Option<u32>,
    #[serde(default)]
    pub count: Option<u32>,
}

#[derive(Debug, Deserialize, JsonSchema)]
pub struct XrefsFieldReq {
    pub class_name: String,
    pub field_name: String,
    #[serde(default)]
    pub offset: Option<u32>,
    #[serde(default)]
    pub count: Option<u32>,
}

#[derive(Debug, Deserialize, JsonSchema)]
pub struct RenameClassReq {
    pub class_name: String,
    pub new_name: String,
}

#[derive(Debug, Deserialize, JsonSchema)]
pub struct RenameMethodReq {
    /// Either "ClassName.methodName" or just "methodName" if class_name is provided.
    pub method_name: String,
    pub new_name: String,
    #[serde(default)]
    pub class_name: Option<String>,
    /// Optional method descriptor (the value from get_methods_of_class) to disambiguate overloads.
    #[serde(default)]
    pub descriptor: Option<String>,
}

#[derive(Debug, Deserialize, JsonSchema)]
pub struct RenameFieldReq {
    pub class_name: String,
    pub field_name: String,
    pub new_name: String,
}

#[derive(Debug, Deserialize, JsonSchema)]
pub struct RenamePackageReq {
    pub old_package_name: String,
    pub new_package_name: String,
}

#[derive(Debug, Deserialize, JsonSchema)]
pub struct ClassSourcesReq {
    /// Fully-qualified class names to fetch in ONE batch. Inner classes use `$`.
    /// Keep it modest (≤~30) so the combined response fits the context window.
    pub class_names: Vec<String>,
    /// Per-class max characters (default 120000; 0 = unlimited). Each class is capped independently.
    #[serde(default)]
    pub max_chars: Option<u32>,
}

#[derive(Debug, Deserialize, JsonSchema)]
pub struct SearchStringConstantsReq {
    /// Substring (default) or regex to match against string-literal VALUES in decompiled Java source.
    pub query: String,
    /// Treat `query` as a Java regular expression (matched with `.find()`). Default false.
    #[serde(default)]
    pub regex: Option<bool>,
    /// Match case-sensitively. Default false.
    #[serde(default)]
    pub case_sensitive: Option<bool>,
    /// Optional package prefix filter (e.g. "com.example.app").
    #[serde(default)]
    pub package: Option<String>,
    /// Wall-clock budget in milliseconds for the scan (default 25000). While the pre-decompiled Java
    /// index is still warming up, this runs as a bounded live scan and may return partial results
    /// with `timed_out: true`; once warm it is sub-second.
    #[serde(default)]
    pub timeout_ms: Option<u64>,
    #[serde(default)]
    pub offset: Option<u32>,
    #[serde(default)]
    pub count: Option<u32>,
}

#[derive(Debug, Deserialize, JsonSchema)]
pub struct XrefsFromMethodReq {
    pub class_name: String,
    pub method_name: String,
    /// Optional smali descriptor `(args)ret` (e.g. `(Ljava/lang/String;)V`) to scope to one overload.
    /// Omit to merge callees of all overloads.
    #[serde(default)]
    pub descriptor: Option<String>,
    #[serde(default)]
    pub offset: Option<u32>,
    #[serde(default)]
    pub count: Option<u32>,
}

#[derive(Debug, Deserialize, JsonSchema)]
pub struct XrefsFromClassReq {
    pub class_name: String,
    #[serde(default)]
    pub offset: Option<u32>,
    #[serde(default)]
    pub count: Option<u32>,
}

#[derive(Debug, Deserialize, JsonSchema)]
pub struct SubclassesReq {
    /// Fully-qualified class or interface name (as shown in decompiled source). Returns its DIRECT
    /// subclasses / implementors, enumerated live from the jadx type-hierarchy model (no index).
    pub class_name: String,
    /// Optional package prefix filter on the results.
    #[serde(default)]
    pub package: Option<String>,
    #[serde(default)]
    pub offset: Option<u32>,
    #[serde(default)]
    pub count: Option<u32>,
}

#[derive(Debug, Deserialize, JsonSchema)]
pub struct CallGraphReq {
    /// Fully-qualified class to start the LIVE BFS traversal from (no prebuilt index).
    pub class_name: String,
    /// "callees" (default — what this class transitively calls) or "callers" (what calls it).
    #[serde(default)]
    pub direction: Option<String>,
    /// Max hops to traverse (default 2, capped at 20).
    #[serde(default)]
    pub depth: Option<u32>,
    /// Cap on total nodes visited (default 500).
    #[serde(default)]
    pub max_nodes: Option<u32>,
    /// Optional package prefix filter on returned nodes.
    #[serde(default)]
    pub package: Option<String>,
    #[serde(default)]
    pub offset: Option<u32>,
    #[serde(default)]
    pub count: Option<u32>,
}
