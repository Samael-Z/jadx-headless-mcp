//! MCP server: rmcp ServerHandler + tool router. Each #[tool] handler is a thin
//! proxy that calls the bridge over HTTP and forwards the JSON response.

use crate::bridge::Bridge;
use crate::error::ToolError;
use crate::http::HttpClient;
use crate::tools::*;
use rmcp::{
    handler::server::{router::tool::ToolRouter, wrapper::Parameters},
    model::{CallToolResult, Content, ServerCapabilities, ServerInfo},
    tool, tool_handler, tool_router, ErrorData as McpError, ServerHandler, ServiceExt,
};
use std::sync::Arc;

#[derive(Clone)]
pub struct JadxMcpServer {
    client: HttpClient,
    // Read by the macro-generated `#[tool_handler]` impl, hence the allow.
    #[allow(dead_code)]
    tool_router: ToolRouter<Self>,
}

impl JadxMcpServer {
    pub fn new(client: HttpClient) -> Self {
        Self {
            client,
            tool_router: Self::tool_router(),
        }
    }

    /// Helper: turn a `serde_json::Value` from the bridge into a one-piece tool result.
    fn ok(value: serde_json::Value) -> CallToolResult {
        match Content::json(value) {
            Ok(c) => CallToolResult::success(vec![c]),
            Err(e) => CallToolResult::error(vec![Content::text(format!(
                "internal: failed to serialize tool result: {e}"
            ))]),
        }
    }

    async fn get(&self, path: &str, query: &[(&str, String)]) -> CallToolResult {
        match self.client.get_json(path, query).await {
            Ok(v) => Self::ok(v),
            Err(e) => ToolError::Bridge(format!("{e:#}")).to_tool_result(),
        }
    }

    async fn post(&self, path: &str, query: &[(&str, String)]) -> CallToolResult {
        match self.client.post_json(path, query).await {
            Ok(v) => Self::ok(v),
            Err(e) => ToolError::Bridge(format!("{e:#}")).to_tool_result(),
        }
    }
}

fn pagination_qs(offset: &Option<u32>, count: &Option<u32>) -> Vec<(&'static str, String)> {
    let mut out = Vec::with_capacity(2);
    if let Some(v) = offset {
        out.push(("offset", v.to_string()));
    }
    if let Some(v) = count {
        out.push(("count", v.to_string()));
    }
    out
}

#[tool_router]
impl JadxMcpServer {
    // ----------------------------- Classes -----------------------------

    #[tool(description = "List all decompiled classes (including inner classes) in the APK. \
                          Returns a paginated envelope with `total`, `returned`, `has_more`, and `classes` (array of FQNs). \
                          Use this to discover what's in the APK before searching.")]
    async fn get_all_classes(
        &self,
        Parameters(req): Parameters<PaginationReq>,
    ) -> Result<CallToolResult, McpError> {
        let q = pagination_qs(&req.offset, &req.count);
        Ok(self.get("/all-classes", &q).await)
    }

    #[tool(description = "Fetch the full decompiled Java source of a class by its fully-qualified name. \
                          Returns `{ content: \"<source>\" }`. Inner classes use `$`, e.g. `com.foo.Outer$Inner`.")]
    async fn get_class_source(
        &self,
        Parameters(req): Parameters<ClassNameReq>,
    ) -> Result<CallToolResult, McpError> {
        let q = vec![("class_name", req.class_name)];
        Ok(self.get("/class-source", &q).await)
    }

    #[tool(description = "List all methods declared on a class. Returns name, full_name, return_type, access_flags, is_constructor.")]
    async fn get_methods_of_class(
        &self,
        Parameters(req): Parameters<ClassNameReq>,
    ) -> Result<CallToolResult, McpError> {
        let q = vec![("class_name", req.class_name)];
        Ok(self.get("/methods-of-class", &q).await)
    }

    #[tool(description = "List all fields declared on a class. Returns name, type, access_flags per field.")]
    async fn get_fields_of_class(
        &self,
        Parameters(req): Parameters<ClassNameReq>,
    ) -> Result<CallToolResult, McpError> {
        let q = vec![("class_name", req.class_name)];
        Ok(self.get("/fields-of-class", &q).await)
    }

    #[tool(description = "Fetch the Smali (Dalvik bytecode disassembly) of a class.")]
    async fn get_smali_of_class(
        &self,
        Parameters(req): Parameters<ClassNameReq>,
    ) -> Result<CallToolResult, McpError> {
        let q = vec![("class_name", req.class_name)];
        Ok(self.get("/smali-of-class", &q).await)
    }

    #[tool(description = "Fetch the launcher Activity class from AndroidManifest.xml: its FQN and decompiled source.")]
    async fn get_main_activity_class(&self) -> Result<CallToolResult, McpError> {
        Ok(self.get("/main-activity", &[]).await)
    }

    #[tool(description = "List class names belonging to the application's main package (manifest `package` attribute). \
                          Useful for filtering out third-party libraries.")]
    async fn get_main_application_classes_names(&self) -> Result<CallToolResult, McpError> {
        Ok(self.get("/main-application-classes-names", &[]).await)
    }

    #[tool(description = "Fetch decompiled source for every class in the application's main package. Paginated; \
                          start with offset=0, count=20.")]
    async fn get_main_application_classes_code(
        &self,
        Parameters(req): Parameters<PaginationReq>,
    ) -> Result<CallToolResult, McpError> {
        Ok(self.get("/main-application-classes-code", &pagination_qs(&req.offset, &req.count)).await)
    }

    #[tool(description = "Get a histogram of packages sorted by class count (descending). \
                          Each entry has `name`, `class_count`, `is_likely_library` (heuristic). \
                          Run this FIRST to understand APK structure before searching.")]
    async fn get_package_tree(&self) -> Result<CallToolResult, McpError> {
        Ok(self.get("/package-tree", &[]).await)
    }

    #[tool(description = "Search for classes containing a keyword. \
                          `search_in` accepts a comma-separated list of: class, method, field, code, comment. \
                          Default is `code`. `package` optionally restricts to a package prefix.")]
    async fn search_classes_by_keyword(
        &self,
        Parameters(req): Parameters<SearchClassesReq>,
    ) -> Result<CallToolResult, McpError> {
        let mut q: Vec<(&'static str, String)> = vec![("search_term", req.search_term)];
        if let Some(p) = req.package {
            q.push(("package", p));
        }
        if let Some(s) = req.search_in {
            q.push(("search_in", s));
        }
        q.extend(pagination_qs(&req.offset, &req.count));
        Ok(self.get("/search-classes-by-keyword", &q).await)
    }

    #[tool(description = "Decompilation source cache statistics: hits, misses, hit_rate, cached_classes, compressed_mb.")]
    async fn get_cache_stats(&self) -> Result<CallToolResult, McpError> {
        Ok(self.get("/cache-stats", &[]).await)
    }

    #[tool(description = "Clear the in-process decompilation source cache (used by /class-source and /search-*). \
                          Has no effect on the loaded APK itself.")]
    async fn clear_cache(&self) -> Result<CallToolResult, McpError> {
        Ok(self.post("/cache-clear", &[]).await)
    }

    // ----------------------------- Methods -----------------------------

    #[tool(description = "Look up a method's source by name (case-insensitive). \
                          If `class_name` is provided, only that class is searched; otherwise the first match across all classes is returned.")]
    async fn get_method_by_name(
        &self,
        Parameters(req): Parameters<MethodByNameReq>,
    ) -> Result<CallToolResult, McpError> {
        let mut q: Vec<(&'static str, String)> = vec![("method_name", req.method_name)];
        if let Some(c) = req.class_name {
            q.push(("class_name", c));
        }
        Ok(self.get("/method-by-name", &q).await)
    }

    #[tool(description = "Find all classes containing any method whose name contains the given substring (case-insensitive).")]
    async fn search_method_by_name(
        &self,
        Parameters(req): Parameters<SearchMethodReq>,
    ) -> Result<CallToolResult, McpError> {
        let q = vec![("method_name", req.method_name)];
        Ok(self.get("/search-method", &q).await)
    }

    // ----------------------------- Resources / Manifest -----------------------------

    #[tool(description = "Return the full AndroidManifest.xml as text.")]
    async fn get_android_manifest(&self) -> Result<CallToolResult, McpError> {
        Ok(self.get("/manifest", &[]).await)
    }

    #[tool(description = "Return all `res/values*/strings.xml` files in the APK (paginated).")]
    async fn get_strings(
        &self,
        Parameters(req): Parameters<PaginationReq>,
    ) -> Result<CallToolResult, McpError> {
        Ok(self.get("/strings", &pagination_qs(&req.offset, &req.count)).await)
    }

    #[tool(description = "List every resource file name in the APK (paginated). Use this to discover what's available before fetching.")]
    async fn get_all_resource_file_names(
        &self,
        Parameters(req): Parameters<PaginationReq>,
    ) -> Result<CallToolResult, McpError> {
        Ok(self.get("/list-all-resource-files-names", &pagination_qs(&req.offset, &req.count)).await)
    }

    #[tool(description = "Fetch a single resource file's text content. Path is e.g. `res/xml/network_security_config.xml`.")]
    async fn get_resource_file(
        &self,
        Parameters(req): Parameters<ResourceFileReq>,
    ) -> Result<CallToolResult, McpError> {
        let q = vec![("resource_name", req.resource_name)];
        Ok(self.get("/get-resource-file", &q).await)
    }

    // ----------------------------- Xrefs -----------------------------

    #[tool(description = "Find all class-level and method-level references to a class. Paginated.")]
    async fn get_xrefs_to_class(
        &self,
        Parameters(req): Parameters<XrefsClassReq>,
    ) -> Result<CallToolResult, McpError> {
        let mut q: Vec<(&'static str, String)> = vec![("class_name", req.class_name)];
        q.extend(pagination_qs(&req.offset, &req.count));
        Ok(self.get("/xrefs-to-class", &q).await)
    }

    #[tool(description = "Find all call sites of a method (including override-related methods in the inheritance hierarchy). Paginated.")]
    async fn get_xrefs_to_method(
        &self,
        Parameters(req): Parameters<XrefsMethodReq>,
    ) -> Result<CallToolResult, McpError> {
        let mut q: Vec<(&'static str, String)> = vec![
            ("class_name", req.class_name),
            ("method_name", req.method_name),
        ];
        q.extend(pagination_qs(&req.offset, &req.count));
        Ok(self.get("/xrefs-to-method", &q).await)
    }

    #[tool(description = "Find all method-level read/write references to a field. Paginated.")]
    async fn get_xrefs_to_field(
        &self,
        Parameters(req): Parameters<XrefsFieldReq>,
    ) -> Result<CallToolResult, McpError> {
        let mut q: Vec<(&'static str, String)> = vec![
            ("class_name", req.class_name),
            ("field_name", req.field_name),
        ];
        q.extend(pagination_qs(&req.offset, &req.count));
        Ok(self.get("/xrefs-to-field", &q).await)
    }

    // ----------------------------- Renames -----------------------------

    #[tool(description = "Rename a class. The change is in-memory only — visible to subsequent tool calls in the same session, \
                          but not persisted to disk.")]
    async fn rename_class(
        &self,
        Parameters(req): Parameters<RenameClassReq>,
    ) -> Result<CallToolResult, McpError> {
        let q = vec![
            ("class_name", req.class_name),
            ("new_name", req.new_name),
        ];
        Ok(self.get("/rename-class", &q).await)
    }

    #[tool(description = "Rename a method. In-memory only. `method_name` accepts either bare name or `ClassName.methodName`.")]
    async fn rename_method(
        &self,
        Parameters(req): Parameters<RenameMethodReq>,
    ) -> Result<CallToolResult, McpError> {
        let mut q: Vec<(&'static str, String)> = vec![
            ("method_name", req.method_name),
            ("new_name", req.new_name),
        ];
        if let Some(c) = req.class_name {
            q.push(("class_name", c));
        }
        Ok(self.get("/rename-method", &q).await)
    }

    #[tool(description = "Rename a field on a class. In-memory only.")]
    async fn rename_field(
        &self,
        Parameters(req): Parameters<RenameFieldReq>,
    ) -> Result<CallToolResult, McpError> {
        let q = vec![
            ("class_name", req.class_name),
            ("field_name", req.field_name),
            ("new_name", req.new_name),
        ];
        Ok(self.get("/rename-field", &q).await)
    }

    #[tool(description = "Rename every class under a package. In-memory only. Returns count of renamed/total classes and per-class errors.")]
    async fn rename_package(
        &self,
        Parameters(req): Parameters<RenamePackageReq>,
    ) -> Result<CallToolResult, McpError> {
        let q = vec![
            ("old_package_name", req.old_package_name),
            ("new_package_name", req.new_package_name),
        ];
        Ok(self.get("/rename-package", &q).await)
    }
}

#[tool_handler]
impl ServerHandler for JadxMcpServer {
    fn get_info(&self) -> ServerInfo {
        ServerInfo::new(ServerCapabilities::builder().enable_tools().build())
            .with_instructions(
                "Headless JADX Android decompiler over MCP. \
                 Start with `get_package_tree` to understand the APK's structure, \
                 then `search_classes_by_keyword` or `get_main_application_classes_names` to narrow \
                 in on app code, and `get_class_source` / `get_method_by_name` / `get_xrefs_to_*` \
                 for detailed analysis. Renames are in-memory and do not persist across runs.",
            )
    }
}

/// Run the MCP server over stdio. Shuts the bridge down when the client disconnects.
pub async fn run_stdio(server: JadxMcpServer, bridge: Bridge) -> anyhow::Result<()> {
    let bridge = Arc::new(bridge);
    let bridge_for_shutdown = bridge.clone();

    let transport = rmcp::transport::stdio();
    let service = server.serve(transport).await?;
    tracing::info!("MCP server ready (stdio)");

    // Wait for either the client to disconnect or a Ctrl-C / SIGTERM.
    let serve_fut = service.waiting();
    tokio::select! {
        res = serve_fut => {
            if let Err(e) = res {
                tracing::warn!("server exited with error: {}", e);
            } else {
                tracing::info!("client disconnected");
            }
        }
        _ = shutdown_signal() => {
            tracing::info!("shutdown signal received");
        }
    }

    bridge_for_shutdown.shutdown().await;
    Ok(())
}

async fn shutdown_signal() {
    let ctrl_c = async { let _ = tokio::signal::ctrl_c().await; };
    #[cfg(unix)]
    {
        let term = async {
            use tokio::signal::unix::{signal, SignalKind};
            if let Ok(mut s) = signal(SignalKind::terminate()) {
                let _ = s.recv().await;
            }
        };
        tokio::select! { _ = ctrl_c => {}, _ = term => {} }
    }
    #[cfg(not(unix))]
    {
        ctrl_c.await;
    }
}
