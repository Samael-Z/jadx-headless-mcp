// build.rs — locate the bridge JAR and expose its path to main.rs via BRIDGE_JAR_PATH.
//
// Search order:
//   1. $BRIDGE_JAR_PATH (CI may pre-build the jar and point us at it)
//   2. ./bridge/target/jadx-bridge.jar (default `mvn package` output)
//   3. ./vendor/jadx-bridge.jar (release artifact dropped during packaging)
//
// If none found, the build fails with a clear message.

use std::env;
use std::path::PathBuf;

fn main() {
    println!("cargo:rerun-if-env-changed=BRIDGE_JAR_PATH");
    println!("cargo:rerun-if-changed=bridge/target/jadx-bridge.jar");
    println!("cargo:rerun-if-changed=vendor/jadx-bridge.jar");

    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"));

    let candidates: Vec<PathBuf> = {
        let mut v = Vec::new();
        if let Ok(p) = env::var("BRIDGE_JAR_PATH") {
            v.push(PathBuf::from(p));
        }
        v.push(manifest_dir.join("bridge").join("target").join("jadx-bridge.jar"));
        v.push(manifest_dir.join("vendor").join("jadx-bridge.jar"));
        v
    };

    let found = candidates.iter().find(|p| p.is_file());
    match found {
        Some(path) => {
            let canon = path.canonicalize().unwrap_or_else(|_| path.clone());
            println!("cargo:rustc-env=BRIDGE_JAR_PATH={}", canon.display());
        }
        None => {
            panic!(
                "jadx-bridge.jar not found. Build it first:\n\
                 \n\
                 \tcd bridge && mvn -DskipTests package\n\
                 \n\
                 or set BRIDGE_JAR_PATH to a prebuilt jar. Searched:\n{}",
                candidates
                    .iter()
                    .map(|p| format!("  - {}", p.display()))
                    .collect::<Vec<_>>()
                    .join("\n")
            );
        }
    }
}
