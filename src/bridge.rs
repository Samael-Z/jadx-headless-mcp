//! Subprocess lifecycle for the Java sidecar.
//!
//! On `Bridge::spawn` we:
//!   1. Write the bundled (or supplied) `jadx-bridge.jar` to a temp file if needed.
//!   2. Run `java -jar jadx-bridge.jar --apk <path> --port <port>`.
//!   3. Read stdout line-by-line, expecting "PORT=<n>\nREADY\n" within
//!      `startup_timeout_secs`.
//!   4. Hand the resolved port to an `HttpClient` and keep the `Child` until drop.
//!
//! Drop kills the child (graceful — SIGTERM where available, .kill() on Windows).

use crate::http::HttpClient;
use anyhow::{anyhow, Context, Result};
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::process::{Child, Command};
use tokio::sync::Mutex;
use tokio::time::timeout;

pub struct SpawnConfig {
    pub java_bin: PathBuf,
    pub bridge_jar: PathBuf,
    pub apk_path: PathBuf,
    pub host: String,
    pub port: u16,
    pub jvm_args: Vec<String>,
    pub startup_timeout_secs: u64,
}

pub struct Bridge {
    child: Mutex<Option<Child>>,
    port: u16,
    client: HttpClient,
}

impl Bridge {
    pub async fn spawn(cfg: SpawnConfig) -> Result<Self> {
        // Args: -Xss2m and -Xmx2g default. JVM args override.
        let mut cmd = Command::new(&cfg.java_bin);
        if cfg.jvm_args.is_empty() {
            cmd.arg("-Xmx2g");
        } else {
            for a in &cfg.jvm_args {
                cmd.arg(a);
            }
        }
        cmd.arg("-jar")
            .arg(&cfg.bridge_jar)
            .arg("--apk")
            .arg(&cfg.apk_path)
            .arg("--host")
            .arg(&cfg.host)
            .arg("--port")
            .arg(cfg.port.to_string())
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);

        let mut child = cmd
            .spawn()
            .with_context(|| format!("failed to spawn `java -jar {}`", cfg.bridge_jar.display()))?;

        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| anyhow!("child has no stdout"))?;
        let stderr = child
            .stderr
            .take()
            .ok_or_else(|| anyhow!("child has no stderr"))?;

        // Forward stderr to our tracing pipeline in the background.
        tokio::spawn(forward_stderr(stderr));

        let mut reader = BufReader::new(stdout).lines();
        let mut port: Option<u16> = None;
        let mut ready = false;

        let deadline = Duration::from_secs(cfg.startup_timeout_secs);
        let result = timeout(deadline, async {
            while let Ok(Some(line)) = reader.next_line().await {
                tracing::debug!(target: "bridge.stdout", "{}", line);
                if let Some(rest) = line.strip_prefix("PORT=") {
                    port = rest.trim().parse().ok();
                }
                if line.trim() == "READY" {
                    ready = true;
                    break;
                }
            }
            Result::<()>::Ok(())
        })
        .await;

        match result {
            Err(_) => {
                let _ = child.kill().await;
                return Err(anyhow!(
                    "jadx-bridge did not become READY within {}s — check stderr above",
                    cfg.startup_timeout_secs
                ));
            }
            Ok(Err(e)) => {
                let _ = child.kill().await;
                return Err(e);
            }
            Ok(Ok(())) => {}
        }

        if !ready {
            let _ = child.kill().await;
            return Err(anyhow!("jadx-bridge exited before signaling READY"));
        }
        let port = port.ok_or_else(|| anyhow!("jadx-bridge did not announce a PORT line"))?;

        // Drain remaining stdout in the background so the pipe never fills.
        tokio::spawn(async move {
            while let Ok(Some(line)) = reader.next_line().await {
                tracing::debug!(target: "bridge.stdout", "{}", line);
            }
        });

        let client = HttpClient::new(&cfg.host, port).context("building HTTP client for bridge")?;

        Ok(Self {
            child: Mutex::new(Some(child)),
            port,
            client,
        })
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    pub fn client(&self) -> HttpClient {
        self.client.clone()
    }

    pub async fn shutdown(&self) {
        let mut guard = self.child.lock().await;
        if let Some(mut child) = guard.take() {
            // kill_on_drop is set; we explicitly kill here too so logging is deterministic.
            let _ = child.kill().await;
            let _ = child.wait().await;
            tracing::info!("jadx-bridge stopped");
        }
    }
}

async fn forward_stderr<R: tokio::io::AsyncRead + Unpin>(reader: R) {
    let mut lines = BufReader::new(reader).lines();
    while let Ok(Some(line)) = lines.next_line().await {
        tracing::info!(target: "bridge", "{}", line);
    }
}

/// Locate the `java` binary. Lookup order: explicit override → JAVA_HOME → PATH.
pub fn resolve_java(explicit: Option<&Path>) -> Result<PathBuf> {
    if let Some(p) = explicit {
        if !p.is_file() {
            return Err(anyhow!("--java points to non-file: {}", p.display()));
        }
        return Ok(p.to_path_buf());
    }
    if let Ok(home) = std::env::var("JAVA_HOME") {
        let exe = if cfg!(windows) { "java.exe" } else { "java" };
        let candidate = PathBuf::from(home).join("bin").join(exe);
        if candidate.is_file() {
            return Ok(candidate);
        }
    }
    which::which("java")
        .map_err(|e| anyhow!("java not found on PATH (and JAVA_HOME not set or invalid): {}", e))
}

/// Resolve the path to `jadx-bridge.jar`. If `override_path` is supplied, use it.
/// Otherwise, write the bundled bytes to a stable temp file and return that.
pub fn materialize_bridge_jar(override_path: Option<&Path>, bundled: &[u8]) -> Result<PathBuf> {
    if let Some(p) = override_path {
        if !p.is_file() {
            return Err(anyhow!("--bridge-jar not a file: {}", p.display()));
        }
        return Ok(p.to_path_buf());
    }

    // Stable location: <data-local>/jadx-handless-mcp/jadx-bridge-<size>.jar
    // The size suffix invalidates the cache when the binary is updated.
    let dir = dirs::data_local_dir()
        .map(|d| d.join("jadx-handless-mcp"))
        .unwrap_or_else(|| std::env::temp_dir().join("jadx-handless-mcp"));
    std::fs::create_dir_all(&dir).with_context(|| format!("mkdir {}", dir.display()))?;
    let target = dir.join(format!("jadx-bridge-{}.jar", bundled.len()));
    if !target.is_file() {
        tracing::info!(path = %target.display(), bytes = bundled.len(), "extracting bundled bridge jar");
        std::fs::write(&target, bundled).with_context(|| format!("writing {}", target.display()))?;
    }
    Ok(target)
}
