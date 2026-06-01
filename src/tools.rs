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
    /// Wall-clock budget in milliseconds for code/comment scans (default 25000).
    /// class/method/field name matching is fast and unaffected; code/comment
    /// decompile every class, so on a huge APK they stop at this budget and
    /// return partial results with `timed_out: true`. Prefer `search_in=class`
    /// (fast) when you can; raise this only for exhaustive code sweeps.
    #[serde(default)]
    pub timeout_ms: Option<u64>,
}

#[derive(Debug, Deserialize, JsonSchema)]
pub struct FindStringUsagesReq {
    /// The string literal to search for. By default this is matched against
    /// the smali const-string opcodes of every class (authoritative; works
    /// even for classes jadx cannot decompile).
    pub literal: String,
    /// Which source(s) to search:
    /// - "smali" (default): scans `const-string vN, "<literal>"` opcodes.
    ///   Works on R8/anti-tamper hardened classes whose decompile is empty.
    /// - "code": scans jadx-decompiled Java/Kotlin source (faster on big
    ///   APKs because decompile is cached, but returns 0 hits on hardened
    ///   classes).
    /// - "both": report classes matched by either source. `matched_in`
    ///   in the response tells you which.
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
