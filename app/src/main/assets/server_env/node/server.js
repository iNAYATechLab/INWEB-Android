#!/usr/bin/env node
/**
 * INWEB — bundled Node.js http-server
 *
 * Zero-dependency static file server for the JS-first crowd. Compatible
 * with `http-server` semantics: serves files from --root on --port, with
 * a friendly directory listing and correct MIME detection.
 *
 * Invoked by ServerManager with env vars:
 *   INWEB_ROOT   — absolute path to the docroot (www/)
 *   INWEB_PORT   — port to listen on
 *   INWEB_BIND   — bind address (127.0.0.1 or 0.0.0.0)
 *
 * We use ONLY the Node standard library so users don't need to run
 * `npm install` before starting the server.
 */
'use strict';

const http = require('http');
const fs   = require('fs');
const path = require('path');
const url  = require('url');

const ROOT = process.env.INWEB_ROOT || process.cwd();
const PORT = parseInt(process.env.INWEB_PORT || '8080', 10);
const BIND = process.env.INWEB_BIND || '127.0.0.1';

const MIME = {
    '.html': 'text/html; charset=utf-8',
    '.htm':  'text/html; charset=utf-8',
    '.css':  'text/css; charset=utf-8',
    '.js':   'application/javascript; charset=utf-8',
    '.mjs':  'application/javascript; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.svg':  'image/svg+xml',
    '.png':  'image/png',
    '.jpg':  'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.gif':  'image/gif',
    '.webp': 'image/webp',
    '.ico':  'image/x-icon',
    '.txt':  'text/plain; charset=utf-8',
    '.md':   'text/plain; charset=utf-8',
    '.pdf':  'application/pdf',
    '.wasm': 'application/wasm',
    '.mp4':  'video/mp4',
    '.woff': 'font/woff',
    '.woff2':'font/woff2',
};

function log(msg) {
    const ts = new Date().toISOString();
    console.log(`[${ts}] ${msg}`);
}

function safeJoin(root, requestPath) {
    // Decode + normalise, then re-check we're still inside `root`.
    const decoded = decodeURIComponent(requestPath.split('?')[0]);
    const resolved = path.normalize(path.join(root, decoded));
    if (!resolved.startsWith(path.resolve(root))) return null;
    return resolved;
}

function directoryListing(dir, urlPath) {
    const entries = fs.readdirSync(dir, { withFileTypes: true })
        .sort((a, b) => (b.isDirectory() - a.isDirectory()) || a.name.localeCompare(b.name));

    const rows = entries.map(e => {
        const suffix = e.isDirectory() ? '/' : '';
        const href   = encodeURIComponent(e.name) + suffix;
        const icon   = e.isDirectory() ? '📁' : '📄';
        return `<li>${icon} <a href="${href}">${e.name}${suffix}</a></li>`;
    }).join('\n');

    const parent = urlPath === '/' ? '' :
        `<li>⬆ <a href="../">Parent directory</a></li>`;

    return `<!doctype html><html><head>
<meta charset="utf-8"><title>Index of ${urlPath}</title>
<style>
body{font-family:system-ui;padding:2rem;max-width:720px;margin:auto;
     background:#0B1410;color:#F5F7FA}
h1{color:#14B8A6}a{color:#14B8A6;text-decoration:none}
a:hover{text-decoration:underline}li{list-style:none;padding:.35rem 0;
       border-bottom:1px solid #132821;font-family:monospace}
ul{padding:0}small{color:#9AB5AA}
</style></head><body>
<h1>Index of ${urlPath}</h1>
<small>Served by INWEB · Node.js http-server</small>
<ul>${parent}${rows}</ul>
</body></html>`;
}

function serve404(res, path) {
    res.writeHead(404, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(`<h1>404 — Not Found</h1><p>${path}</p>`);
}

const server = http.createServer((req, res) => {
    log(`${req.method} ${req.url}`);
    const parsed = url.parse(req.url);
    const full   = safeJoin(ROOT, parsed.pathname);
    if (!full) return serve404(res, parsed.pathname);

    fs.stat(full, (err, stat) => {
        if (err) return serve404(res, parsed.pathname);

        if (stat.isDirectory()) {
            // Look for an index file first.
            for (const idx of ['index.html', 'index.htm']) {
                const c = path.join(full, idx);
                if (fs.existsSync(c)) {
                    res.writeHead(200, { 'Content-Type': MIME['.html'] });
                    return fs.createReadStream(c).pipe(res);
                }
            }
            res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
            return res.end(directoryListing(full, parsed.pathname));
        }

        const ext = path.extname(full).toLowerCase();
        const type = MIME[ext] || 'application/octet-stream';
        res.writeHead(200, {
            'Content-Type':   type,
            'Content-Length': stat.size,
            'Cache-Control':  'no-cache',
        });
        fs.createReadStream(full).pipe(res);
    });
});

server.listen(PORT, BIND, () => {
    log(`INWEB Node.js http-server listening on http://${BIND}:${PORT}`);
    log(`Docroot: ${ROOT}`);
});
