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
