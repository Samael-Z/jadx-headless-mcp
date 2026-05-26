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

    pub async fn get_json<P: Serialize + ?Sized>(
        &self,
        path: &str,
        params: &P,
    ) -> Result<serde_json::Value> {
        let url = self.base.join(path)?;
        let resp = self.client.get(url).query(params).send().await?;
        let status = resp.status();
        let body = resp.text().await?;
        if !status.is_success() {
            // Try to parse as JSON error envelope; fall back to raw text.
            if let Ok(v) = serde_json::from_str::<serde_json::Value>(&body) {
                return Ok(v);
            }
            anyhow::bail!("bridge returned HTTP {}: {}", status, body);
        }
        // Some bridge routes return raw text (e.g. class source). Wrap it.
        match serde_json::from_str::<serde_json::Value>(&body) {
            Ok(v) => Ok(v),
            Err(_) => Ok(serde_json::json!({ "content": body })),
        }
    }

    pub async fn post_json<P: Serialize + ?Sized>(
        &self,
        path: &str,
        params: &P,
    ) -> Result<serde_json::Value> {
        let url = self.base.join(path)?;
        let resp = self.client.post(url).query(params).send().await?;
        let status = resp.status();
        let body = resp.text().await?;
        if !status.is_success() {
            if let Ok(v) = serde_json::from_str::<serde_json::Value>(&body) {
                return Ok(v);
            }
            anyhow::bail!("bridge returned HTTP {}: {}", status, body);
        }
        match serde_json::from_str::<serde_json::Value>(&body) {
            Ok(v) => Ok(v),
            Err(_) => Ok(serde_json::json!({ "content": body })),
        }
    }
}
