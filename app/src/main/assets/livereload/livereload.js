/*
 * INWEB — LiveReload client
 *
 * Injected into every HTML response by Nginx `sub_filter`. Connects to the
 * LiveReload WebSocket (default port 35729) and reloads the page — or
 * hot-swaps CSS — whenever the server broadcasts a change.
 *
 * Zero dependencies. Auto-reconnects with exponential backoff.
 */
(function () {
    'use strict';

    // Skip if a real LiveReload extension is already present, or if we're
    // inside an iframe (avoid double-loads).
    if (window.__inwebLiveReloadLoaded) return;
    window.__inwebLiveReloadLoaded = true;

    var host  = location.hostname || 'localhost';
    var port  = 35729;
    var scheme= (location.protocol === 'https:') ? 'wss:' : 'ws:';
    var url   = scheme + '//' + host + ':' + port + '/livereload';
    var backoffMs = 1000;

    function log(msg) {
        try { console.log('%c[INWEB LiveReload] ' + msg,
                          'color:#14B8A6;font-weight:bold'); } catch (_) {}
    }

    function reloadCss(pathHint) {
        // Hot-swap every stylesheet by appending a cache-buster.
        var links = document.querySelectorAll('link[rel="stylesheet"]');
        var reloaded = 0;
        for (var i = 0; i < links.length; i++) {
            var link = links[i];
            var href = link.getAttribute('href');
            if (!href) continue;
            var clean = href.split('?')[0].split('#')[0];
            link.setAttribute('href', clean + '?_lr=' + Date.now());
            reloaded++;
        }
        log('CSS hot-swap · ' + reloaded + ' stylesheet(s) reloaded  (' + pathHint + ')');
    }

    function fullReload() {
        log('Full page reload');
        location.reload();
    }

    function connect() {
        var ws;
        try { ws = new WebSocket(url); }
        catch (e) { log('cannot connect: ' + e); scheduleReconnect(); return; }

        ws.onopen = function () {
            log('connected → ' + url);
            backoffMs = 1000;
            ws.send(JSON.stringify({
                command:   'hello',
                protocols: ['http://livereload.com/protocols/official-7']
            }));
        };

        ws.onmessage = function (ev) {
            var msg = null;
            try { msg = JSON.parse(ev.data); } catch (_) { return; }
            if (!msg || !msg.command) return;
            if (msg.command === 'reload') {
                if (msg.liveCSS && (msg.path || '').match(/\.css$/i)) {
                    reloadCss(msg.path);
                } else {
                    fullReload();
                }
            }
        };

        ws.onclose = function () { log('disconnected'); scheduleReconnect(); };
        ws.onerror = function ()  { try { ws.close(); } catch(_) {} };
    }

    function scheduleReconnect() {
        setTimeout(connect, backoffMs);
        backoffMs = Math.min(backoffMs * 2, 15000);
    }

    // Give the page a beat to finish loading before we connect.
    if (document.readyState === 'complete') connect();
    else window.addEventListener('load', connect);
})();
