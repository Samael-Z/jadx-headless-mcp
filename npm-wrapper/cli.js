#!/usr/bin/env node
/*
 * jadx-headless-mcp npm wrapper.
 *
 * On first invocation: downloads the platform-specific binary from the matching
 * GitHub Release into a user cache directory, then exec()s it with the same argv.
 * Subsequent invocations skip the download. No npm dependencies, no postinstall.
 *
 * Cache layout:
 *   <local-cache>/jadx-headless-mcp-npm/v<version>/<stem>/<binary>
 * where <stem> is "jadx-headless-mcp-<os>-<arch>" — the directory the release
 * archive extracts to.
 */

'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const https = require('https');
const { spawn, execFileSync } = require('child_process');

const pkg = require('./package.json');
const VERSION = pkg.version;

// Maps Node's `${platform}-${arch}` to the release artifact name.
// Keep in lockstep with the matrix in .github/workflows/build.yml.
const PLATFORMS = {
    'linux-x64':    { archive: `jadx-headless-mcp-linux-x86_64.tar.gz`, exe: 'jadx-mcp' },
    'linux-arm64':  { archive: `jadx-headless-mcp-linux-arm64.tar.gz`,  exe: 'jadx-mcp' },
    'darwin-x64':   { archive: `jadx-headless-mcp-macos-x86_64.tar.gz`, exe: 'jadx-mcp' },
    'darwin-arm64': { archive: `jadx-headless-mcp-macos-arm64.tar.gz`,  exe: 'jadx-mcp' },
    'win32-x64':    { archive: `jadx-headless-mcp-windows-x86_64.zip`,  exe: 'jadx-mcp.exe' },
};

const RELEASE_BASE = `https://github.com/Samael-Z/jadx-headless-mcp/releases/download/v${VERSION}`;

function platformKey() {
    return `${process.platform}-${process.arch}`;
}

function cacheRoot() {
    // Win: %LOCALAPPDATA%, others: ~/.cache (XDG convention; macOS users typically have it too).
    if (process.platform === 'win32') {
        return process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local');
    }
    return process.env.XDG_CACHE_HOME || path.join(os.homedir(), '.cache');
}

function versionedDir() {
    return path.join(cacheRoot(), 'jadx-headless-mcp-npm', `v${VERSION}`);
}

function archiveStem(archive) {
    return archive.replace(/\.(tar\.gz|zip)$/, '');
}

function log(msg) {
    // Logs MUST go to stderr — when this shim is launched from an MCP client over
    // stdio, stdout is reserved for JSON-RPC frames.
    process.stderr.write(`[jadx-headless-mcp] ${msg}\n`);
}

function downloadFile(url, destPath, redirectsLeft = 5) {
    return new Promise((resolve, reject) => {
        if (redirectsLeft < 0) {
            return reject(new Error('too many redirects'));
        }
        const out = fs.createWriteStream(destPath);
        const req = https.get(
            url,
            { headers: { 'User-Agent': `jadx-headless-mcp-npm/${VERSION}` } },
            (res) => {
                if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                    out.close();
                    try { fs.unlinkSync(destPath); } catch (_) {}
                    return resolve(downloadFile(res.headers.location, destPath, redirectsLeft - 1));
                }
                if (res.statusCode !== 200) {
                    out.close();
                    try { fs.unlinkSync(destPath); } catch (_) {}
                    return reject(new Error(`HTTP ${res.statusCode} from ${url}`));
                }
                res.pipe(out);
                out.on('finish', () => out.close(() => resolve()));
            }
        );
        req.on('error', (err) => {
            out.close();
            try { fs.unlinkSync(destPath); } catch (_) {}
            reject(err);
        });
        out.on('error', (err) => {
            out.close();
            try { fs.unlinkSync(destPath); } catch (_) {}
            reject(err);
        });
    });
}

function resolveTar() {
    // On Windows, prefer the system bsdtar at System32\tar.exe (Win10 1803+).
    // Reasons it must NOT come from $PATH on Windows:
    //   1. Git Bash bundles GNU tar, which doesn't read .zip files.
    //   2. GNU tar misinterprets `C:\...` as an SSH `host:path` and tries to
    //      exec a remote shell — even for a local extract. bsdtar doesn't.
    // bsdtar (libarchive) handles both .tar.gz and .zip uniformly.
    if (process.platform === 'win32') {
        const sys32 = path.join(process.env.WINDIR || 'C:\\Windows', 'System32', 'tar.exe');
        if (fs.existsSync(sys32)) return sys32;
    }
    return 'tar';
}

function extractArchive(archivePath, destDir) {
    // Pass archive as a basename + use cwd, never as an absolute path with a
    // drive letter — see GNU-tar caveat in resolveTar() above.
    const archiveName = path.basename(archivePath);
    const isTar = archiveName.endsWith('.tar.gz') || archiveName.endsWith('.tgz');
    const args = isTar ? ['-xzf', archiveName] : ['-xf', archiveName];
    const tarBin = resolveTar();
    try {
        execFileSync(tarBin, args, { cwd: destDir, stdio: 'inherit' });
    } catch (err) {
        throw new Error(
            `failed to extract ${archiveName} via ${tarBin} — make sure 'tar' is on PATH. ` +
            `(Win10 1803+ ships bsdtar at C:\\Windows\\System32\\tar.exe; ` +
            `macOS and Linux have it built in.) ` +
            `Underlying error: ${err.message}`
        );
    }
}

async function ensureBinary() {
    const key = platformKey();
    const info = PLATFORMS[key];
    if (!info) {
        log(`unsupported platform: ${key}`);
        log(`supported platforms: ${Object.keys(PLATFORMS).join(', ')}`);
        log(`if you'd like support added, please file an issue at ${pkg.bugs.url}`);
        process.exit(2);
    }

    const dir = versionedDir();
    const stem = archiveStem(info.archive);
    const exePath = path.join(dir, stem, info.exe);

    if (fs.existsSync(exePath)) {
        return exePath;
    }

    fs.mkdirSync(dir, { recursive: true });
    const archivePath = path.join(dir, info.archive);
    const url = `${RELEASE_BASE}/${info.archive}`;

    log(`downloading ${url}`);
    try {
        await downloadFile(url, archivePath);
    } catch (err) {
        log(`download failed: ${err.message}`);
        log(`hint: check that release v${VERSION} exists at https://github.com/Samael-Z/jadx-headless-mcp/releases`);
        process.exit(3);
    }

    log(`extracting to ${dir}`);
    try {
        extractArchive(archivePath, dir);
    } catch (err) {
        log(err.message);
        process.exit(4);
    }

    try { fs.unlinkSync(archivePath); } catch (_) {}

    if (!fs.existsSync(exePath)) {
        log(`binary not at expected path after extract: ${exePath}`);
        log(`archive contents may have an unexpected layout — please file an issue.`);
        process.exit(5);
    }

    try {
        if (process.platform !== 'win32') {
            fs.chmodSync(exePath, 0o755);
        }
    } catch (_) {}

    log(`installed to ${exePath}`);
    return exePath;
}

(async () => {
    const exe = await ensureBinary();

    // Forward signals so e.g. SIGINT from the MCP client cleanly stops the bridge.
    const child = spawn(exe, process.argv.slice(2), {
        stdio: 'inherit',
        windowsHide: true,
    });

    let forwardedSignal = null;
    const forward = (sig) => {
        if (forwardedSignal) return;
        forwardedSignal = sig;
        try { child.kill(sig); } catch (_) {}
    };
    process.on('SIGINT', () => forward('SIGINT'));
    process.on('SIGTERM', () => forward('SIGTERM'));

    child.on('error', (err) => {
        log(`failed to spawn binary: ${err.message}`);
        process.exit(127);
    });
    child.on('exit', (code, signal) => {
        if (signal) {
            // Re-raise on Unix; on Windows just propagate as non-zero exit.
            if (process.platform !== 'win32') {
                try { process.kill(process.pid, signal); return; } catch (_) {}
            }
            process.exit(128 + (require('os').constants.signals[signal] || 1));
        } else {
            process.exit(code === null ? 0 : code);
        }
    });
})().catch((err) => {
    log(`fatal: ${err && err.stack || err}`);
    process.exit(1);
});
