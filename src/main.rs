use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use std::path::PathBuf;
use tracing_subscriber::EnvFilter;

mod bridge;
mod error;
mod http;
mod server;
mod tools;

use crate::server::JadxMcpServer;

const BUNDLED_BRIDGE_JAR: &[u8] = include_bytes!(env!("BRIDGE_JAR_PATH"));

#[derive(Parser, Debug)]
#[command(
    name = "jadx-mcp",
    version,
    about = "Headless JADX Android decompiler exposed over the Model Context Protocol.",
    long_about = "Spawns a bundled Java sidecar (jadx-bridge) for each APK, then proxies MCP tool \
                  calls into the running JADX decompiler. Java 11+ must be on PATH (or set via --java)."
)]
struct Cli {
    #[command(subcommand)]
    command: Option<Command>,

    /// Path to the APK / DEX / AAB / XAPK / JAR to analyze. Required for `serve`.
    #[arg(long, env = "JADX_MCP_APK", global = true)]
    apk: Option<PathBuf>,

    /// Path to the `java` executable. If omitted, looked up via JAVA_HOME or PATH.
    #[arg(long, env = "JADX_MCP_JAVA", global = true)]
    java: Option<PathBuf>,

    /// Path to a custom bridge JAR. Defaults to the bundled one.
    #[arg(long, env = "JADX_MCP_BRIDGE_JAR", global = true)]
    bridge_jar: Option<PathBuf>,

    /// Extra JVM flags (e.g. "-Xmx4g"). May be passed multiple times.
    #[arg(long = "jvm-arg", env = "JADX_MCP_JVM_ARGS", value_delimiter = ' ', global = true)]
    jvm_args: Vec<String>,

    /// Bind address for the bridge's local HTTP listener.
    #[arg(long, default_value = "127.0.0.1", global = true)]
    bridge_host: String,

    /// Bridge port; 0 = OS-assigned (default).
    #[arg(long, default_value_t = 0, global = true)]
    bridge_port: u16,

    /// Seconds to wait for the bridge to print READY after spawning.
    #[arg(long, default_value_t = 600, global = true)]
    bridge_startup_secs: u64,
}

#[derive(Subcommand, Debug, Clone)]
enum Command {
    /// Run the MCP server over stdio (default).
    Serve,
    /// Print the bundled bridge JAR path and version then exit. Used by CI/debugging.
    Probe,
    /// Extract the bundled bridge JAR to a path on disk.
    ExtractBridge {
        /// Output file. Defaults to "./jadx-bridge.jar".
        #[arg(short, long, default_value = "jadx-bridge.jar")]
        out: PathBuf,
    },
}

#[tokio::main]
async fn main() -> Result<()> {
    init_tracing();

    let cli = Cli::parse();
    let command = cli.command.clone().unwrap_or(Command::Serve);

    match command {
        Command::Serve => serve(cli).await,
        Command::Probe => probe(),
        Command::ExtractBridge { out } => extract_bridge(&out),
    }
}

fn init_tracing() {
    // Default: log INFO to stderr. Stdio MCP transport reserves stdout for JSON-RPC.
    let filter = EnvFilter::try_from_env("JADX_MCP_LOG").unwrap_or_else(|_| EnvFilter::new("info"));
    tracing_subscriber::fmt()
        .with_env_filter(filter)
        .with_writer(std::io::stderr)
        .with_ansi(false)
        .init();
}

async fn serve(cli: Cli) -> Result<()> {
    let apk = cli
        .apk
        .clone()
        .context("--apk is required for `serve` (or set JADX_MCP_APK)")?;
    if !apk.is_file() {
        anyhow::bail!("APK not found: {}", apk.display());
    }

    // Resolve/materialize the bridge JAR
    let bridge_jar_path = bridge::materialize_bridge_jar(cli.bridge_jar.as_deref(), BUNDLED_BRIDGE_JAR)
        .context("failed to materialize bridge jar")?;
    let java_bin = bridge::resolve_java(cli.java.as_deref()).context("failed to find a Java binary")?;

    tracing::info!(?java_bin, jar = %bridge_jar_path.display(), "using bridge");

    let bridge_handle = bridge::Bridge::spawn(bridge::SpawnConfig {
        java_bin,
        bridge_jar: bridge_jar_path,
        apk_path: apk.clone(),
        host: cli.bridge_host.clone(),
        port: cli.bridge_port,
        jvm_args: cli.jvm_args.clone(),
        startup_timeout_secs: cli.bridge_startup_secs,
    })
    .await
    .context("failed to start jadx-bridge sidecar")?;

    tracing::info!(
        port = bridge_handle.port(),
        apk = %apk.display(),
        "jadx-bridge ready"
    );

    let server = JadxMcpServer::new(bridge_handle.client());
    server::run_stdio(server, bridge_handle).await
}

fn probe() -> Result<()> {
    println!("jadx-mcp {}", env!("CARGO_PKG_VERSION"));
    println!("bundled-bridge-jar-bytes: {}", BUNDLED_BRIDGE_JAR.len());
    Ok(())
}

fn extract_bridge(out: &std::path::Path) -> Result<()> {
    std::fs::write(out, BUNDLED_BRIDGE_JAR)
        .with_context(|| format!("writing bridge JAR to {}", out.display()))?;
    println!("wrote {} bytes to {}", BUNDLED_BRIDGE_JAR.len(), out.display());
    Ok(())
}
