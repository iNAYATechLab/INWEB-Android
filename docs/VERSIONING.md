# 🏷 INWEB Versioning Policy

> INWEB বর্তমানে **Public Beta** পর্যায়ে আছে। Play Store production release-এর আগ পর্যন্ত সব release এই scheme অনুসরণ করবে।

---

## 📐 Version Format

```
MAJOR.MINOR.PATCH[-stage.N]
```

| Stage | Format | উদাহরণ | কখন |
|-------|--------|---------|------|
| 🧪 **Beta** | `X.Y.Z-beta.N` | `1.0.0-beta.1` | Feature সম্পূর্ণ, কিন্তু testing চলছে — public beta testers-এর জন্য |
| 🚦 **Release Candidate** | `X.Y.Z-rc.N` | `1.0.0-rc.1` | সব beta bug fix হয়ে গেছে, final stability check |
| 🟢 **Stable** | `X.Y.Z` | `1.0.0` | Production-ready, Play Store-worthy |

### সংখ্যা বাড়ার নিয়ম (SemVer)

| পরিবর্তন | কোন অংশ বাড়ে | উদাহরণ |
|----------|:---:|---------|
| নতুন feature | **MINOR** | `1.0.0-beta.1` → `1.1.0-beta.1` |
| Bug fix | **PATCH** | `1.0.0-beta.1` → `1.0.1-beta.1` |
| Breaking change | **MAJOR** | `1.x.x` → `2.0.0-beta.1` |
| একই feature-set-এ নতুন beta build | **N** | `1.0.0-beta.1` → `1.0.0-beta.2` |

---

## 🔢 versionCode Formula

```kotlin
versionCode = MAJOR * 10000 + MINOR * 100 + PATCH + betaIndex
```

| versionName | versionCode |
|-------------|:---:|
| `1.0.0-beta.1` | 10000 |
| `1.0.0-beta.2` | 10001 |
| `1.0.0-beta.9` | 10008 |
| `1.0.0-rc.1` | 10090 |
| `1.0.0` (stable) | 10100 |
| `1.1.0-beta.1` | 10100 → 11000 |

> Beta range: `XX000–XX089` · RC range: `XX090–XX099` · Stable: `XX100`
> এভাবে প্রতিটা minor version-এ 90 টা beta slot + 10 টা RC slot থাকে। 🎯

---

## 🏷 Git Tag Convention

| Release | Tag | GitHub Release flag |
|---------|-----|---------------------|
| Beta | `v1.0.0-beta.1` | `--prerelease` ✅ (auto) |
| RC | `v1.0.0-rc.1` | `--prerelease` ✅ (auto) |
| Stable | `v1.0.0` | Full release 🟢 |

CI workflow tag-এ `-beta` বা `-rc` দেখলে **automatic pre-release** mark করে — কিছু manually করতে হয় না।

---

## 🚀 Beta Release করার Steps

```bash
# 1. versionName + versionCode বাড়াও (app/build.gradle.kts)
versionName = "1.0.0-beta.2"
versionCode = 10001

# 2. CHANGELOG/BUILD_NOTES-এ কী বদলেছে লেখো

# 3. Commit + tag + push
git add -A
git commit -m "release: v1.0.0-beta.2"
git tag -a v1.0.0-beta.2 -m "INWEB 1.0.0 Beta 2"
git push origin main --tags

# 4. GitHub Actions নিজে build + prerelease publish করবে (auto) 🤖
```

---

## 🗺 Road to Stable

```
1.0.0-beta.1 ──► 1.0.0-beta.2 ──► ... ──► 1.0.0-rc.1 ──► 1.0.0 🟢
   (now!)          bug fixes        feature complete   production
```

### ✅ Beta → Stable checklist

- [ ] কমপক্ষে ২-৩ জন beta tester device-এ চালিয়েছে
- [ ] Signing setup complete (`docs/SIGNING.md` অনুযায়ী keystore + GitHub Secrets)
- [ ] Nginx + PHP + MariaDB — ৩টা engine-ই real device-এ runtime-verified
- [ ] কোনো P0/crash-level bug open নেই
- [ ] Play Store listing assets final ([play_store/listing.md](../play_store/listing.md))
