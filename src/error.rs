use rmcp::model::{CallToolResult, Content};
use thiserror::Error;

#[derive(Debug, Error)]
pub enum ToolError {
    #[error("bridge unreachable: {0}")]
    Bridge(String),

    #[error(
        "No APK is loaded yet. Call `load_apk` with the absolute path to your \
         .apk/.dex/.aab/.xapk/.apkm/.jar file first. Example: \
         load_apk(path=\"/abs/path/to/app.apk\")"
    )]
    NoApkLoaded,

    #[error("invalid argument: {0}")]
    Invalid(String),

    #[error("internal: {0}")]
    Internal(String),
}

impl ToolError {
    pub fn to_tool_result(&self) -> CallToolResult {
        CallToolResult::error(vec![Content::text(self.to_string())])
    }
}

impl From<anyhow::Error> for ToolError {
    fn from(e: anyhow::Error) -> Self {
        ToolError::Bridge(format!("{e:#}"))
    }
}

impl From<serde_json::Error> for ToolError {
    fn from(e: serde_json::Error) -> Self {
        ToolError::Internal(format!("serde: {e}"))
    }
}
