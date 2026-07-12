# 🎬 INWEB Promo Video (বাংলা)

একটি self-contained interactive HTML "video" — browser-এ open করলেই auto-play হবে। CapCut, DaVinci, Premiere কোনো software লাগবে না।

## 🚀 কীভাবে চালাবেন?

শুধু ব্রাউজারে `inweb_promo.html` ওপেন করুন — Chrome, Safari, Firefox যেকোনো টাতে কাজ করবে। মাঝখানের ▶ **Play** বাটনে ক্লিক করুন → ~32 সেকেন্ডে পুরো ভিডিও চলবে।

```bash
# Local browser
open inweb_promo.html          # macOS
xdg-open inweb_promo.html      # Linux
start inweb_promo.html         # Windows

# অথবা কোনো static host (GitHub Pages, Vercel, Netlify) এ deploy করুন
```

## 📦 File structure

```
promo_video/
├── inweb_promo.html          ← main video (auto-play HTML)
├── README.md                 ← আপনি এখানে
├── frames/                   ← image assets (branding থেকে copy)
│   ├── logo.png              (app icon 1024×1024)
│   ├── splash.png            (splash mockup)
│   ├── social.png            (social announcement)
│   └── feature.png           (Play Store feature graphic)
└── audio/                    ← বাংলা narration (4 clips)
    ├── scene_1_2.mp3
    ├── scene_3_4.mp3
    ├── scene_5_6.mp3
    └── scene_7_8.mp3
```

## 🎞 ৮টা দৃশ্য (Scenes)

| # | দৃশ্য | সময়কাল | বিষয় |
|:---:|---|:---:|---|
| ১ | Hook | 4s | "পকেটে যদি একটা ওয়েব সার্ভার থাকতো?" |
| ২ | Problem | 4.5s | ল্যাপটপ/ক্লাউড/ইন্টারনেট cross-out |
| ৩ | Solution | 4s | INWEB logo pop-in reveal |
| ৪ | Engines | 4.5s | ৫টা web engine grid |
| ৫ | Features | 4.5s | PHP · MariaDB · HTTPS · DNS · Live Reload · DDNS |
| ৬ | Ecosystem | 4.5s | Android → Web PWA → iOS flow |
| ৭ | Islamic | 4s | নামাজ · কিবলা · হিজরি · যাকাত |
| ৮ | CTA | 5s | "এখনই Play Store থেকে ডাউনলোড" |
| — | **মোট** | **~35s** | |

## 🎨 Design specs

- **Palette:** deep forest `#0B1410` bg, teal `#14B8A6` accent, cream `#F5F7FA` text
- **Font:** Hind Siliguri (Google Fonts, বাংলা optimized)
- **Animations:** pure CSS keyframes (fade, pop, slide, pulse, strikethrough)
- **Audio:** 4টা mp3 clip pre-loaded, scene transition এ auto-play
- **Responsive:** mobile phone-এও কাজ করে (grid layout adapts)

## ⌨️ Keyboard shortcuts

| Key | Action |
|:---:|---|
| **Space** | ভিডিও replay করুন |
| **M** | Mute/unmute |
| **Click ▶** | Start playing |

## 🔄 Share / Deploy

### GitHub Pages
```bash
# INWEB-Android repo-এর /promo_video folder-কে static publish করুন
# তারপর: https://your-username.github.io/INWEB-Android/promo_video/inweb_promo.html
```

### Netlify Drop (drag-and-drop)
1. https://app.netlify.com/drop এ যান
2. পুরো `promo_video/` folder drag করুন
3. Instant public URL পাবেন

### Vercel
```bash
cd promo_video
npx vercel --prod
```

## 🎥 আসল mp4 video চাই?

Browser-এ HTML ভিডিও চালিয়ে **screen recording** করুন:

| OS | Tool |
|---|---|
| **Windows** | Xbox Game Bar (Win+G) |
| **macOS** | QuickTime Player → File → New Screen Recording |
| **Linux** | OBS Studio / SimpleScreenRecorder |
| **Chrome extension** | Loom, Screencastify, Awesome Screenshot |

Recording tips:
- Browser fullscreen mode (F11) এ চালান
- 1920×1080 window size রাখুন
- Recording start → ▶ Play button ক্লিক → শেষ হওয়া পর্যন্ত অপেক্ষা
- Trim করুন CapCut / DaVinci Resolve / iMovie দিয়ে (optional)

## 🎯 Alternative usage

- 📱 **App presentation** — client meeting-এ browser-এ live show করুন
- 🌐 **Landing page hero** — `<iframe>` দিয়ে embed করুন
- 📢 **Social media** — screen record করে reels/shorts বানান
- 🎓 **Documentation** — README-এ link দিন

---

Made with 💚 in Bangladesh · **INWEB**
