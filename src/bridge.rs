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
use std::collections::VecDeque;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::Arc;
use std::sync::Mutex as StdMutex;
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::process::{Child, Command};
use tokio::sync::Mutex;
use tokio::time::timeout;

/// Capacity of the ring buffer that captures bridge stderr during startup,
/// so spawn failures can include the underlying JVM error (OOM, ClassNotFound,
/// etc.) in their error message instead of a bare "exited before READY".
const STDERR_TAIL_LINES: usize = 60;

pub struct SpawnConfig {
    pub java_bin: PathBuf,
    pub bridge_jar: PathBuf,
    pub apk_path: PathBuf,
    pub host: String,
    pub port: u16,
    pub jvm_args: Vec<String>,
    pub startup_timeout_secs: u64,
}

/// Re-usable subset of `SpawnConfig` without the APK path. Held by the server
/// so it can spawn a fresh bridge each time `load_apk` is called for a
/// different file, without re-resolving java / bridge.jar / jvm args.
#[derive(Clone)]
pub struct SpawnTemplate {
    pub java_bin: PathBuf,
    pub bridge_jar: PathBuf,
    pub host: String,
    pub port: u16,
    pub jvm_args: Vec<String>,
    pub startup_timeout_secs: u64,
}

impl SpawnTemplate {
    pub fn with_apk(&self, apk_path: PathBuf) -> SpawnConfig {
        SpawnConfig {
            java_bin: self.java_bin.clone(),
            bridge_jar: self.bridge_jar.clone(),
            apk_path,
            host: self.host.clone(),
            port: self.port,
            jvm_args: self.jvm_args.clone(),
            startup_timeout_secs: self.startup_timeout_secs,
        }
    }
}

pub struct Bridge {
    child: Mutex<Option<Child>>,
    port: u16,
    client: HttpClient,
    /// Held open for the bridge's lifetime. We never write to it; its sole job
    /// is to keep the child's stdin pipe open so the bridge's stdin-EOF watchdog
    /// (`--exit-on-stdin-close`) only fires when THIS process actually dies.
    /// Dropping this closes the pipe and asks the bridge to exit.
    _child_stdin: StdMutex<Option<tokio::process::ChildStdin>>,
}

impl Bridge {
    pub async fn spawn(cfg: SpawnConfig) -> Result<Self> {
        // Default max heap of 2g unless the user supplies their own -Xmx via
        // --jvm-arg / JADX_MCP_JVM_ARGS. The default is a *baseline*: user args
        // are always passed through, so `--jvm-arg -Xss2m` keeps -Xmx2g, while
        // `--jvm-arg -Xmx4g` overrides the heap. Without an explicit -Xmx we
        // never want to fall back to the JVM's tiny default.
        let mut cmd = Command::new(&cfg.java_bin);
        if !cfg.jvm_args.iter().any(|a| a.starts_with("-Xmx")) {
            cmd.arg("-Xmx2g");
        }
        for a in &cfg.jvm_args {
            cmd.arg(a);
        }
        cmd.arg("-jar")
            .arg(&cfg.bridge_jar)
            .arg("--apk")
            .arg(&cfg.apk_path)
            .arg("--host")
            .arg(&cfg.host)
            .arg("--port")
            .arg(cfg.port.to_string())
            // Orphan guard: the bridge exits when this stdin pipe reaches EOF.
            // We pipe (and hold open) its stdin below; if THIS process dies —
            // even via an ungraceful kill, where kill_on_drop never runs and
            // Windows won't tear down the child — the OS closes the pipe and the
            // bridge self-terminates instead of lingering as an orphan JVM.
            .arg("--exit-on-stdin-close")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);

        let mut child = cmd
            .spawn()
            .with_context(|| format!("failed to spawn `java -jar {}`", cfg.bridge_jar.display()))?;

        // Keep the child's stdin write-end alive for the bridge's lifetime (see
        // `_child_stdin`). We never write to it — holding it open is what makes
        // the orphan-guard fire only on parent death.
        let child_stdin = child.stdin.take();

        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| anyhow!("child has no stdout"))?;
        let stderr = child
            .stderr
            .take()
            .ok_or_else(|| anyhow!("child has no stderr"))?;

        // Ring-buffer the last N stderr lines so a spawn failure can echo the
        // real JVM error (OOM, ClassNotFound, JADX exception) back to the
        // caller. Without this, the user sees only "exited before signaling
        // READY" and has no signal for what's actually wrong.
        let stderr_tail = Arc::new(StdMutex::new(VecDeque::<String>::with_capacity(
            STDERR_TAIL_LINES,
        )));
        tokio::spawn(forward_stderr(stderr, Arc::clone(&stderr_tail)));

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
                    "jadx-bridge did not become READY within {}s.{}",
                    cfg.startup_timeout_secs,
                    format_stderr_tail(&stderr_tail)
                ));
            }
            Ok(Err(e)) => {
                let _ = child.kill().await;
                return Err(e);
            }
            Ok(Ok(())) => {}
        }

        if !ready {
            // Reap the exit status (the child has already exited; this is
            // primarily to surface it in the error). Then give the stderr
            // forwarder one tick to flush its last lines so OOM/exception
            // messages don't race the EOF on stdout out of the tail buffer.
            let exit_status = child.wait().await.ok();
            tokio::time::sleep(Duration::from_millis(100)).await;
            let exit_hint = exit_status
                .map(|s| format!(" (exit status: {s})"))
                .unwrap_or_default();
            return Err(anyhow!(
                "jadx-bridge exited before signaling READY{exit_hint}.{}",
                format_stderr_tail(&stderr_tail)
            ));
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
            _child_stdin: StdMutex::new(child_stdin),
        })
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    pub fn client(&self) -> HttpClient {
        self.client.clone()
    }

    pub async fn shutdown(&self) {
        // Drop the stdin write-end first: this trips the bridge's stdin-EOF
        // watchdog as a belt-and-suspenders companion to the explicit kill below.
        if let Ok(mut s) = self._child_stdin.lock() {
            s.take();
        }
        let mut guard = self.child.lock().await;
        if let Some(mut child) = guard.take() {
            // kill_on_drop is set; we explicitly kill here too so logging is deterministic.
            let _ = child.kill().await;
            let _ = child.wait().await;
            tracing::info!("jadx-bridge stopped");
        }
    }
}

async fn forward_stderr<R: tokio::io::AsyncRead + Unpin>(
    reader: R,
    tail: Arc<StdMutex<VecDeque<String>>>,
) {
    let mut lines = BufReader::new(reader).lines();
    while let Ok(Some(line)) = lines.next_line().await {
        tracing::info!(target: "bridge", "{}", line);
        if let Ok(mut q) = tail.lock() {
            if q.len() == STDERR_TAIL_LINES {
                q.pop_front();
            }
            q.push_back(line);
        }
    }
}

/// Render the captured stderr tail as a multi-line suffix for spawn-failure
/// error messages. Returns an empty string when no stderr was captured, so
/// the caller can unconditionally concatenate it.
fn format_stderr_tail(tail: &Arc<StdMutex<VecDeque<String>>>) -> String {
    let Ok(q) = tail.lock() else { return String::new() };
    if q.is_empty() {
        return " (no stderr captured)".to_string();
    }
    // Filter out the slf4j-simple INFO/DEBUG noise — the signal lives in
    // lines that look like Java stack traces ("Exception", "Error", "at ").
    // Keep the last 30 raw lines as well so OOM messages (which often
    // arrive on plain "java.lang.OutOfMemoryError" lines) survive.
    let mut out = String::from(" Last bridge stderr:\n");
    for line in q.iter() {
        out.push_str("  ");
        out.push_str(line);
        out.push('\n');
    }
    out
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

    // Stable location: <data-local>/jadx-headless-mcp/jadx-bridge-<size>.jar
    // The size suffix invalidates the cache when the binary is updated.
    let dir = dirs::data_local_dir()
        .map(|d| d.join("jadx-headless-mcp"))
        .unwrap_or_else(|| std::env::temp_dir().join("jadx-headless-mcp"));
    std::fs::create_dir_all(&dir).with_context(|| format!("mkdir {}", dir.display()))?;
    let target = dir.join(format!("jadx-bridge-{}.jar", bundled.len()));
    if !target.is_file() {
        tracing::info!(path = %target.display(), bytes = bundled.len(), "extracting bundled bridge jar");
        std::fs::write(&target, bundled).with_context(|| format!("writing {}", target.display()))?;
    }
    Ok(target)
}
