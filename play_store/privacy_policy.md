# Privacy Policy — INWEB

**Effective date:** 2026-07-12

INWEB ("we", "our", or "the app") is developed with a **privacy-first**
approach. This policy explains — in plain language — what data INWEB
does and does not handle.

## 🚫 What we DO NOT collect

INWEB **does not collect, transmit, sell, or share any personal data**.
Specifically, INWEB does NOT:

- Collect analytics of any kind
- Send crash reports to third-party services
- Show advertisements
- Track your usage
- Access your contacts, SMS, camera, microphone, or location
- Upload your files, code, or database contents anywhere

## 💾 What stays on your device

Everything INWEB does happens **locally on your Android device**:

- Web server configurations (Nginx, PHP-FPM, MariaDB)
- Your PHP, HTML, CSS, and other web-root files
- Database contents
- Editor preferences (theme, font size)
- MySQL root password (auto-generated, stored in app-private storage)
- SSL certificates (self-signed, generated on your device)

These files live in Android's app-private storage
(`/data/data/com.inweb.app/`) and app-specific external storage
(`Android/data/com.inweb.app/files/`).

## 🌐 Network activity

INWEB makes outbound network requests **only** when you explicitly ask:

- **Framework installers** — downloads WordPress, Laravel, or Bootstrap
  ZIP files from their official servers (wordpress.org, github.com, etc.)
- **Public tunnel** — starts a Cloudflare or ngrok process that creates
  a public HTTPS URL for your server. These are third-party services;
  see their privacy policies at cloudflare.com/privacypolicy/ and
  ngrok.com/legal/privacy-policy.

You control both. Neither runs unless you tap the corresponding button.

## 🔐 Permissions we request and why

| Permission | Purpose |
|---|---|
| INTERNET | Nginx binds sockets; framework installers download files |
| ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE | Detect LAN IP for QR sharing |
| FOREGROUND_SERVICE (+ SPECIAL_USE) | Keep server running when app is backgrounded |
| POST_NOTIFICATIONS | Show silent ongoing notification while server runs |
| WAKE_LOCK | Prevent CPU throttling during requests |
| RECEIVE_BOOT_COMPLETED | Optional auto-start on boot (opt-in only) |

## 👶 Children's privacy

INWEB is a developer tool intended for adult use. We do not knowingly
collect information from children, and we don't collect anything anyway.

## 📬 Contact

Questions about this policy?  Email: **(your email here)**

## 🔄 Changes to this policy

If we ever change how INWEB handles data, this document will be updated
with a new effective date, and the change will be summarised in the
app's release notes.

---

*INWEB is developed with care in Bangladesh. 💚*
