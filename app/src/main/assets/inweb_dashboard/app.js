/*
 * INWEB Web Dashboard — vanilla JS (zero-dep).
 *
 * Persists { host, token } in localStorage; on load, tests /ping and
 * either lands on the dashboard or shows the login gate.
 *
 * Polls /status every 3 s while on the Overview tab; polls /logs every
 * 2 s while on the Logs tab. All other tabs refresh only on entry.
 */
'use strict';

const LS_KEY = 'inweb.session';

/* ---------------- Session + API client ---------------- */

let session = null;   // { host, token }
let pollTimer = null;

function loadSession() {
    try { return JSON.parse(localStorage.getItem(LS_KEY) || 'null'); }
    catch { return null; }
}
function saveSession(s) { localStorage.setItem(LS_KEY, JSON.stringify(s)); session = s; }
function clearSession()  { localStorage.removeItem(LS_KEY); session = null; }

async function api(path, opts = {}) {
    if (!session) throw new Error('no session');
    const url = session.host.replace(/\/$/, '') + path;
    const res = await fetch(url, {
        ...opts,
        headers: {
            'Authorization': 'Bearer ' + session.token,
            'Content-Type':  'application/json',
            ...(opts.headers || {})
        }
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const ct = res.headers.get('content-type') || '';
    return ct.includes('application/json') ? res.json() : res.text();
}

/* ---------------- Boot ---------------- */

window.addEventListener('DOMContentLoaded', () => {
    session = loadSession();
    if (session) tryReconnect();
    else         showLogin();

    // Wire login form
    document.getElementById('loginBtn').onclick = doLogin;
    document.getElementById('logoutBtn').onclick = () => {
        clearSession(); location.reload();
    };

    // Tabs
    document.querySelectorAll('.tab').forEach(t => {
        t.onclick = () => activateTab(t.dataset.tab);
    });

    // Quick-action buttons
    document.querySelectorAll('[data-svc]').forEach(btn => {
        btn.onclick = () => api(
            '/api/inweb/service/' + btn.dataset.op,
            { method: 'POST', body: JSON.stringify({ service: btn.dataset.svc }) }
        ).then(refreshOverview).catch(alert);
    });

    document.getElementById('addSiteBtn').onclick = () => siteDialog();
    document.getElementById('addHostBtn').onclick = () => hostDialog();
    document.getElementById('logFile').onchange   = refreshLogs;
});

async function tryReconnect() {
    try {
        await api('/api/inweb/ping');
        showDashboard();
    } catch (e) {
        showLogin('Session expired — please log in again.');
    }
}

async function doLogin() {
    const host  = (document.getElementById('hostInput').value  || '').trim();
    const token = (document.getElementById('tokenInput').value || '').trim();
    if (!host || !token) return showError('Host and token required');
    saveSession({ host, token });
    try {
        await api('/api/inweb/ping');
        showDashboard();
    } catch (e) {
        showError('Could not connect: ' + e.message);
        clearSession();
    }
}

function showLogin(errorMsg) {
    document.getElementById('loginGate').classList.remove('hidden');
    document.getElementById('dashboard').classList.add('hidden');
    if (errorMsg) showError(errorMsg);
    const s = loadSession();
    if (s) document.getElementById('hostInput').value = s.host;
}
function showError(msg) {
    const el = document.getElementById('loginError');
    el.textContent = msg; el.classList.remove('hidden');
}
function showDashboard() {
    document.getElementById('loginGate').classList.add('hidden');
    document.getElementById('dashboard').classList.remove('hidden');
    activateTab('overview');
}

/* ---------------- Tab routing ---------------- */

function activateTab(id) {
    document.querySelectorAll('.tab').forEach(t =>
        t.classList.toggle('active', t.dataset.tab === id));
    document.querySelectorAll('.panel').forEach(p =>
        p.classList.toggle('active', p.dataset.panel === id));
    stopPoll();
    ({ overview: initOverview, sites: initSites, hosts: initHosts,
       logs: initLogs, settings: initSettings }[id] || (() => {}))();
}

function startPoll(fn, ms) {
    stopPoll(); fn(); pollTimer = setInterval(fn, ms);
}
function stopPoll() { if (pollTimer) { clearInterval(pollTimer); pollTimer = null; } }

/* ---------------- Overview ---------------- */

function initOverview() { startPoll(refreshOverview, 3000); }

async function refreshOverview() {
    try {
        const s = await api('/api/inweb/status');
        setPill(true);
        const svc = s.services, dev = s.device;
        document.getElementById('engineName').textContent = svc.engine.toUpperCase();
        document.getElementById('engineUrl').textContent  = 'localhost:' + svc.httpPort;
        document.getElementById('localIp').textContent    = dev.localIp || 'no LAN';
        document.getElementById('ssid').textContent       = dev.ssid || '—';

        const cpu = dev.cpu < 0 ? 0 : dev.cpu;
        document.getElementById('cpuVal').textContent = cpu;
        document.getElementById('cpuBar').style.width = cpu + '%';

        const ramPct = Math.round(dev.ramUsed / dev.ramTotal * 100);
        document.getElementById('ramVal').textContent =
            fmtBytes(dev.ramUsed) + ' / ' + fmtBytes(dev.ramTotal);
        document.getElementById('ramBar').style.width = ramPct + '%';
    } catch (e) { setPill(false); }
}

function setPill(ok) {
    const p = document.getElementById('statusPill');
    p.textContent = ok ? 'Connected' : 'Offline';
    p.classList.toggle('offline', !ok);
}

function fmtBytes(n) {
    const u = ['B','KB','MB','GB','TB']; let i = 0;
    while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
    return (n >= 100 ? n.toFixed(0) : n.toFixed(1)) + ' ' + u[i];
}

/* ---------------- Sites ---------------- */

async function initSites() {
    const box = document.getElementById('sitesList');
    box.innerHTML = 'Loading…';
    const { vhosts } = await api('/api/inweb/vhosts');
    box.innerHTML = '';
    if (!vhosts.length) box.innerHTML = '<p class="muted">No sites yet.</p>';
    for (const vh of vhosts) box.appendChild(renderVhost(vh));
}

function renderVhost(vh) {
    const row = document.createElement('div');
    row.className = 'list-item';
    row.innerHTML = `
        <div class="dot ${vh.enabled ? '' : 'off'}"></div>
        <div class="info">
            <div class="name">${escape(vh.serverName)} <span class="muted">${vh.phpMode.toLowerCase()}</span></div>
            <div class="sub">${escape(vh.documentRoot)}</div>
        </div>
        <div class="actions">
            <button title="Edit">✎</button>
            <button class="del" title="Delete">✕</button>
        </div>`;
    row.querySelector('.actions button:first-child').onclick = () => siteDialog(vh);
    row.querySelector('.actions .del').onclick = async () => {
        if (!confirm(`Delete ${vh.serverName}?`)) return;
        await api('/api/inweb/vhosts/' + vh.id, { method: 'DELETE' });
        initSites();
    };
    return row;
}

async function siteDialog(existing) {
    const name = prompt('Server name (e.g. wordpress.local):', existing?.serverName || '');
    if (!name) return;
    const root = prompt('Document root (absolute path):',
        existing?.documentRoot || '/sdcard/Android/data/com.inweb.app/files/www/' + name.replace(/[^a-z0-9]/gi, '-'));
    if (!root) return;
    await api('/api/inweb/vhosts', {
        method: 'POST',
        body: JSON.stringify({
            id: existing?.id,
            serverName: name, documentRoot: root,
            phpMode: 'AUTO', enabled: true, label: ''
        })
    });
    initSites();
}

/* ---------------- Hosts ---------------- */

async function initHosts() {
    const box = document.getElementById('hostsList');
    box.innerHTML = 'Loading…';
    const { hosts } = await api('/api/inweb/hosts');
    box.innerHTML = '';
    if (!hosts.length) box.innerHTML = '<p class="muted">No custom DNS mappings yet.</p>';
    for (const h of hosts) box.appendChild(renderHost(h));
}

function renderHost(h) {
    const row = document.createElement('div');
    row.className = 'list-item';
    row.innerHTML = `
        <div class="dot ${h.enabled ? '' : 'off'}"></div>
        <div class="info">
            <div class="name">${escape(h.hostname)} <span class="muted">→ ${escape(h.ip)}</span></div>
            <div class="sub">${escape(h.note || '(no note)')}</div>
        </div>
        <div class="actions">
            <button class="del" title="Delete">✕</button>
        </div>`;
    row.querySelector('.del').onclick = async () => {
        if (!confirm(`Delete ${h.hostname}?`)) return;
        await api('/api/inweb/hosts/' + h.id, { method: 'DELETE' });
        initHosts();
    };
    return row;
}

async function hostDialog() {
    const hostname = prompt('Hostname (e.g. wordpress.local):');
    if (!hostname) return;
    const ip = prompt('IPv4 (e.g. 127.0.0.1):', '127.0.0.1');
    if (!ip) return;
    await api('/api/inweb/hosts', {
        method: 'POST',
        body: JSON.stringify({ hostname, ip, enabled: true })
    });
    initHosts();
}

/* ---------------- Logs ---------------- */

function initLogs() { startPoll(refreshLogs, 2000); }

async function refreshLogs() {
    try {
        const file = document.getElementById('logFile').value;
        const r = await api('/api/inweb/logs?file=' + encodeURIComponent(file) + '&bytes=16384');
        const c = document.getElementById('logConsole');
        const wasAtBottom = c.scrollTop + c.clientHeight >= c.scrollHeight - 40;
        c.textContent = r.content || '(empty)';
        if (wasAtBottom) c.scrollTop = c.scrollHeight;
    } catch (e) { /* silently swallow — probably logs not yet created */ }
}

/* ---------------- Settings ---------------- */

async function initSettings() {
    const container = document.getElementById('settingsForm');
    container.innerHTML = 'Loading…';
    const p = await api('/api/inweb/prefs');
    container.innerHTML = '';

    const fields = [
        { key: 'bindLan',           label: 'Allow LAN access',       type: 'checkbox' },
        { key: 'httpPort',          label: 'HTTP port',              type: 'number'   },
        { key: 'mysqlEnabled',      label: 'Enable MariaDB',         type: 'checkbox' },
        { key: 'mysqlPort',         label: 'MariaDB port',           type: 'number'   },
        { key: 'httpsEnabled',      label: 'Enable HTTPS',           type: 'checkbox' },
        { key: 'httpsPort',         label: 'HTTPS port',             type: 'number'   },
        { key: 'liveReloadEnabled', label: 'Live Reload',            type: 'checkbox' },
        { key: 'dnsServerEnabled',  label: 'LAN DNS server',         type: 'checkbox' },
    ];
    for (const f of fields) container.appendChild(renderPref(f, p));
}

function renderPref(f, current) {
    const row = document.createElement('div');
    row.className = 'setting-row';
    const label = document.createElement('label');
    label.textContent = f.label;
    row.appendChild(label);

    const input = document.createElement('input');
    input.type = f.type;
    if (f.type === 'checkbox') input.checked = current[f.key];
    else                       input.value   = current[f.key];
    input.onchange = () => {
        const val = f.type === 'checkbox' ? input.checked : Number(input.value);
        api('/api/inweb/prefs', {
            method: 'PUT', body: JSON.stringify({ [f.key]: val })
        }).catch(alert);
    };
    row.appendChild(input);
    return row;
}

/* ---------------- Utilities ---------------- */

function escape(s) {
    return String(s || '').replace(/[&<>"']/g, c => ({
        '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
    }[c]));
}
