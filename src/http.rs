//! Thin HTTP client to the jadx-bridge sidecar.
//!
//! All requests go to 127.0.0.1; we never talk to the network. Timeouts are
//! generous because code-level searches on a 100k-class APK can take minutes.

use anyhow::Result;
use reqwest::{Client, Url};
use serde::Serialize;
use std::time::Duration;

#[derive(Clone)]
pub struct HttpClient {
    base: Url,
    client: Client,
}

impl HttpClient {
    pub fn new(host: &str, port: u16) -> Result<Self> {
        let base = Url::parse(&format!("http://{}:{}", host, port))?;
        let client = Client::builder()
            .timeout(Duration::from_secs(3600))
            .connect_timeout(Duration::from_secs(5))
            .build()?;
        Ok(Self { base, client })
    }

    pub async fn get_raw<P: Serialize + ?Sized>(&self, path: &str, params: &P) -> Result<String> {
        let url = self.base.join(path)?;
        let resp = self.client.get(url).query(params).send().await?;
        decode_raw(path, resp).await
    }

    pub async fn post_raw<P: Serialize + ?Sized>(&self, path: &str, params: &P) -> Result<String> {
        let url = self.base.join(path)?;
        let resp = self.client.post(url).query(params).send().await?;
        decode_raw(path, resp).await
    }
}

/// Decode a bridge response into a JSON *string* (never a parsed `Value`). Success bodies that
/// are already JSON pass through untouched — no parse + re-serialize — and the only raw-text
/// routes (class-source, smali) get wrapped as `{"content": ...}`. On HTTP error, the bridge's
/// structured `{error,status}` envelope is passed through as the tool result so the LLM sees the
/// detail; an empty/odd error body bails with a pointer to the bridge log.
async fn decode_raw(path: &str, resp: reqwest::Response) -> Result<String> {
    let status = resp.status();
    let body = resp.text().await?;
    if !status.is_success() {
        if looks_like_json(&body) {
            return Ok(body);
        }
        if body.trim().is_empty() {
            anyhow::bail!(
                "bridge returned HTTP {} for {} with no body -- check the \
                 bridge stderr log for the underlying exception",
                status,
                path
            );
        }
        anyhow::bail!("bridge returned HTTP {} for {}: {}", status, path, body);
    }
    if looks_like_json(&body) {
        Ok(body)
    } else {
        // Raw text (class source / smali) -> wrap so the tool result stays valid JSON.
        Ok(serde_json::json!({ "content": body }).to_string())
    }
}

/// Cheap structural check: does the (trimmed) body start like a JSON object/array? Every bridge
/// JSON route returns an object; the only non-JSON bodies are decompiled source / smali, which
/// start with `package`, `.class`, a comment, or whitespace.
fn looks_like_json(body: &str) -> bool {
    let t = body.trim_start();
    t.starts_with('{') || t.starts_with('[')
}
