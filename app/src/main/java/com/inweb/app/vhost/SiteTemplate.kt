package com.inweb.app.vhost

/**
 * Preset "starter site" that seeds the docroot with useful boilerplate
 * and creates a matching [VirtualHost].
 *
 * The user picks one from the "+ Add site" screen; INWEB writes the
 * boilerplate files inside the chosen docroot on save.
 */
enum class SiteTemplate(
    val id: String,
    val displayName: String,
    val emoji: String,
    val tagline: String,
    val defaultServerName: String,
    val defaultLabel: String,
    val phpMode: VirtualHost.PhpMode,
    val files: List<TemplateFile>
) {

    /* ---------------------------- Blank -------------------------- */
    BLANK(
        id = "blank", displayName = "Blank Site", emoji = "📄",
        tagline = "Empty docroot — bring your own files",
        defaultServerName = "site.local", defaultLabel = "",
        phpMode = VirtualHost.PhpMode.AUTO,
        files = emptyList()
    ),

    /* ---------------------- Static HTML/CSS ---------------------- */
    STATIC_HTML(
        id = "static", displayName = "Static HTML + CSS", emoji = "🎨",
        tagline = "Modern landing page · no PHP · pure HTML/CSS",
        defaultServerName = "landing.local", defaultLabel = "Landing Page",
        phpMode = VirtualHost.PhpMode.STATIC,
        files = listOf(
            TemplateFile("index.html", INDEX_HTML),
            TemplateFile("style.css",  STYLE_CSS)
        )
    ),

    /* ------------------------ PHP Playground --------------------- */
    PHP_PLAYGROUND(
        id = "php", displayName = "PHP Playground", emoji = "🐘",
        tagline = "Hello-world PHP + phpinfo() + a form demo",
        defaultServerName = "php.local", defaultLabel = "PHP Playground",
        phpMode = VirtualHost.PhpMode.AUTO,
        files = listOf(
            TemplateFile("index.php", PHP_INDEX),
            TemplateFile("info.php",  "<?php phpinfo(); ?>")
        )
    ),

    /* --------------------------- JSON API ------------------------ */
    JSON_API(
        id = "api", displayName = "JSON API Skeleton", emoji = "🔌",
        tagline = "PHP endpoint returning JSON — CORS-ready",
        defaultServerName = "api.local", defaultLabel = "JSON API",
        phpMode = VirtualHost.PhpMode.AUTO,
        files = listOf(
            TemplateFile("index.php", API_INDEX)
        )
    ),

    /* --------------- Islamic mini-site (INWEB niche) ------------- */
    ISLAMIC(
        id = "islamic", displayName = "Islamic Starter", emoji = "🕌",
        tagline = "Prayer times + Qibla + Hijri date landing page",
        defaultServerName = "islamic.local", defaultLabel = "Islamic App",
        phpMode = VirtualHost.PhpMode.AUTO,
        files = listOf(
            TemplateFile("index.php", ISLAMIC_INDEX)
        )
    );

    data class TemplateFile(val relPath: String, val content: String)

    companion object {
        fun byId(id: String?): SiteTemplate = entries.firstOrNull { it.id == id } ?: BLANK
    }
}

/* -------------------------------------------------------------------- */
/*  Template content — kept in the same file so translations don't      */
/*  need duplicating and the enum stays self-contained.                 */
/* -------------------------------------------------------------------- */

private const val INDEX_HTML = """<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Welcome</title>
<link rel="stylesheet" href="style.css">
</head>
<body>
<main>
    <h1>🎉 Hello, world</h1>
    <p>This static site was scaffolded by <strong>INWEB</strong>. Edit
    <code>index.html</code> and <code>style.css</code> to make it yours.</p>
    <p>With Live Reload on, saving a file auto-refreshes this page.</p>
</main>
</body></html>
"""

private const val STYLE_CSS = """:root {
    --bg: #0B1410;
    --card: #132821;
    --accent: #14B8A6;
    --text: #F5F7FA;
    --muted: #9AB5AA;
}
* { box-sizing: border-box; margin: 0; padding: 0; }
body {
    font-family: system-ui, -apple-system, sans-serif;
    background: var(--bg);
    color: var(--text);
    min-height: 100vh;
    display: grid;
    place-items: center;
    padding: 2rem;
}
main {
    max-width: 640px;
    background: var(--card);
    padding: 2.5rem;
    border-radius: 20px;
    text-align: center;
    line-height: 1.6;
}
h1 { color: var(--accent); font-size: 2rem; margin-bottom: 1rem; }
code { background: rgba(20,184,166,.15); padding: 2px 6px; border-radius: 4px; }
"""

private const val PHP_INDEX = """<?php
${'$'}greeting = "Hello, INWEB!";
${'$'}now      = date('l, F j Y · H:i:s');
${'$'}serverIp = ${'$'}_SERVER['SERVER_ADDR'] ?? '127.0.0.1';
?>
<!doctype html>
<html><head><meta charset="utf-8">
<title>PHP Playground</title>
<style>
body{font-family:system-ui;padding:2rem;background:#0B1410;color:#F5F7FA;max-width:640px;margin:auto}
h1{color:#14B8A6}code{background:#132821;padding:2px 6px;border-radius:4px}
a{color:#14B8A6}
</style></head>
<body>
<h1><?= htmlspecialchars(${'$'}greeting) ?></h1>
<p>PHP version: <code><?= phpversion() ?></code></p>
<p>Server time:  <code><?= ${'$'}now ?></code></p>
<p>Server IP:    <code><?= ${'$'}serverIp ?></code></p>
<hr style="margin:1.5rem 0;border-color:#132821">
<p>Try these:</p>
<ul>
    <li><a href="info.php">phpinfo()</a> — full PHP configuration</li>
    <li>Edit <code>index.php</code> and reload to see the change</li>
</ul>
</body></html>
"""

private const val API_INDEX = """<?php
header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');

${'$'}payload = [
    'ok'        => true,
    'server'    => 'INWEB',
    'php'       => phpversion(),
    'timestamp' => date(DATE_ISO8601),
    'request'   => [
        'method' => ${'$'}_SERVER['REQUEST_METHOD'],
        'path'   => ${'$'}_SERVER['REQUEST_URI'],
        'ip'     => ${'$'}_SERVER['REMOTE_ADDR'],
    ],
    'message'   => 'Hello from your JSON API scaffold.'
];

echo json_encode(${'$'}payload, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE);
"""

private const val ISLAMIC_INDEX = """<?php
/**
 * Islamic starter site — bundled with INWEB.
 * Uses INWEB's built-in /api/ endpoints (install from the Islamic APIs tab).
 */
?>
<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Islamic App · INWEB</title>
<style>
body{font-family:system-ui;background:#0B1410;color:#F5F7FA;margin:0;padding:2rem;max-width:720px;margin:auto}
h1{color:#14B8A6}
.card{background:#132821;padding:1.25rem 1.5rem;border-radius:14px;margin:1rem 0}
.card h2{color:#14B8A6;font-size:1rem;margin:0 0 .5rem}
.card p{color:#9AB5AA;margin:0}
a{color:#14B8A6}
code{background:rgba(20,184,166,.15);padding:2px 6px;border-radius:4px}
</style></head>
<body>
<h1>🕌 Islamic App</h1>
<p>Scaffolded by INWEB. Uses the built-in Islamic APIs.</p>

<div class="card">
    <h2>🕐 Prayer Times</h2>
    <p><a href="/api/prayer-times.php?lat=23.8103&amp;lng=90.4125">Dhaka today</a></p>
</div>

<div class="card">
    <h2>🧭 Qibla</h2>
    <p><a href="/api/qibla.php?lat=23.8103&amp;lng=90.4125">Direction from Dhaka</a></p>
</div>

<div class="card">
    <h2>📅 Hijri</h2>
    <p><a href="/api/hijri-date.php">Today in Hijri</a></p>
</div>

<div class="card">
    <h2>💰 Zakat</h2>
    <p><a href="/api/zakat.php?cash=100000&amp;gold_g=50">Sample calculation</a></p>
</div>

<p style="color:#9AB5AA;font-size:.85rem;margin-top:2rem">
Install the Islamic APIs first from the INWEB app (Menu → Islamic Developer Kit).
</p>
</body></html>
"""
