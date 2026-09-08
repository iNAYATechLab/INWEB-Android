#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════
#  kotlin_sanity.sh — লোকালে (Android SDK/Gradle ছাড়াই) ধরা পড়ার-যোগ্য ভুল
#
#  ইতিহাস: এই প্রজেক্টে দুইবার একই ক্লাসের ভুল শুধু CI-তেই ধরেছিল —
#    1) "…\."  → Kotlin-এ অবৈধ escape → Unsupported escape sequence
#    2) val as = … → `as` হার্ড কীওয়ার্ড → Syntax error
#  দুটোর জন্যই কম্পাইল দরকার নেই, টেক্সট চেকই যথেষ্ট। তাই এই স্ক্রিপ্ট।
#
#  Usage:  bash scripts/kotlin_sanity.sh [src-dir]
#  Exit:   0 = পরিষ্কার · 1 = সমস্যা
# ════════════════════════════════════════════════════════════════
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${1:-$ROOT/app/src/main}"

python3 - "$SRC" <<'PY'
import os, re, sys

src = sys.argv[1]
kt = []
for dp, _, fs in os.walk(src):
    kt += [os.path.join(dp, f) for f in fs if f.endswith('.kt')]

# Kotlin-এর আসল হার্ড কীওয়ার্ড (identifier হতে পারে না)
HARD = {'as','is','in','if','else','when','for','while','do','try','catch','finally',
        'return','throw','break','continue','class','object','val','var','fun','package',
        'import','typealias','this','super','null','true','false','interface','enum',
        'fun','when','not','file','field','it'}
HARD -= {'field','it','not','file'}          # সফ্ট — identifier হিসেবে চলে
VALID_ESC = set('\\bntrf"\'$u')
problems = []

def add(path, ln, msg, snippet):
    problems.append(f"{path}:{ln}: {msg}  ::  {snippet[:74]}")

for p in kt:
    rel = os.path.relpath(p, src)
    raw = open(p, errors='ignore').read()

    # ── অ্যানালাইসিস-সেফ কপি: raw string ("""…""") আর char literal সরিয়ে দিই ──
    work = re.sub(r'"""[\s\S]*?"""', '""', raw)
    work = re.sub(r"'(?:\\.|[^'\\])'", "'x'", work)          # 'a' , '\n' → প্লেসহোল্ডার
    # একই লাইন-নম্বর ধরে রাখতে \n গণনা করে প্রতিস্থাপন
    def keep_len(m):
        return 'RAWSTR' + '\n' * m.group(0).count('\n')
    work = re.sub(r'"""[\s\S]*?"""', keep_len, raw)
    work = work.replace('\"\"\"', 'RAWSTR')          # (নিরাপদে) — raw string টোকেনাইজ
    work = re.sub(r'"""[\s\S]*?"""', lambda m: 'RAWSTR' + '\n' * m.group(0).count('\n'), raw)
    work = re.sub(r"'(?:\\.|[^'\\\n])'", "'c'", work)

    for i, (line, wline) in enumerate(zip(raw.splitlines(), work.splitlines()), 1):
        st = wline.strip()
        if st.startswith('//') or st.startswith('*'):
            continue

        # ১) অবৈধ escape
        for m in re.finditer(r'"(?:[^"\\\n]|\\.)*"', wline):
            for e in re.finditer(r'\\(.)', m.group(0)):
                if e.group(1) not in VALID_ESC:
                    add(rel, i, f"অবৈধ escape '\\{e.group(1)}' (র/স্ট্রিং হলে \"\"\"…\"\"\" ব্যবহার করো)", line)

        # ২) হার্ড কীওয়ার্ড → identifier
        for m in re.finditer(r'\b(?:val|var|fun|class)\s+([A-Za-z_][A-Za-z0-9_]*)', wline):
            if m.group(1) in HARD and not (m.group(1) == 'interface' and 'fun interface' in wline):
                add(rel, i, f"'{m.group(1)}' হার্ড কীওয়ার্ড — নাম হিসেবে ব্যবহার করা যাবে না", line)

        # ৩) ৬+ কম্পোনেন্ট destructuring — Kotlin List শুধু component1..5 দেয়
        md = re.match(r'\s*val\s*\(([^)]*)\)\s*=', wline)
        if md and len([x for x in md.group(1).split(',') if x.strip()]) >= 6:
            add(rel, i, "destructuring-এ ৬+ ভ্যারিয়েবল (List হলে component6() নেই → কম্পাইল ফেল)", line)

        # (কোট-ব্যালান্স চেক এখানে নেই: regex/raw-স্ট্রিংভরা ফাইলে এটা প্রায়ই
        #  ফলস পজিটিভ দেয় — ভুল ধরার চেয়ে ভুল না বলা জরুরি, তাই বাদ)

# ── res রেফারেন্স ───────────────────────────────────────────────
res = os.path.join(src, 'res')
def res_names(sub, pat, ext='.xml', allfiles=True):
    d = os.path.join(res, sub)
    out = set()
    if not os.path.isdir(d): return out
    for f in os.listdir(d):
        if f.endswith(ext):
            out |= set(re.findall(pat, open(os.path.join(d, f), errors='ignore').read()))
    return out

strings = set()
for sub in ('values','values-bn','values-ar','values-hi','values-night'):
    fp = os.path.join(res, sub, 'strings.xml')
    if os.path.exists(fp):
        strings |= set(re.findall(r'<string name="([A-Za-z0-9_]+)"', open(fp, errors='ignore').read()))
ids = res_names('layout', r'android:id="@\+id/([A-Za-z0-9_]+)"') | \
      res_names('menu',   r'android:id="@\+id/([A-Za-z0-9_]+)"')
drawable = set(f[:-4] for f in os.listdir(os.path.join(res,'drawable')) if f.endswith('.xml')) if os.path.isdir(os.path.join(res,'drawable')) else set()
layout   = set(f[:-4] for f in os.listdir(os.path.join(res,'layout')) if f.endswith('.xml')) if os.path.isdir(os.path.join(res,'layout')) else set()
colors   = res_names('values', r'name="([A-Za-z0-9_]+)"')
pools = {'R.string': strings, 'R.id': ids, 'R.drawable': drawable, 'R.layout': layout}
FRAME_OK = {'ok','cancel','yes','no'}      # android.R.string.*

for p in kt:
    rel = os.path.relpath(p, src)
    txt = open(p, errors='ignore').read()
    # android.R.* (ফ্রেমওয়ার্ক রিসোর্স) বাদ দিই
    txt = re.sub(r'android\.R\.(string|layout|id|drawable|color|dimen)\.([A-Za-z0-9_]+)',
                 lambda m: ('FRAME_str_' + m.group(2)) if m.group(1) == 'string' else ('FRAME_' + m.group(1) + '_' + m.group(2)), txt)
    for i, line in enumerate(txt.splitlines(), 1):
        for kind, pool in pools.items():
            for m in re.finditer(re.escape(kind) + r'\.([A-Za-z0-9_]+)', line):
                if m.group(1) not in pool and not m.group(1).startswith(('FRAME_',)):
                    add(rel, i, f"{kind}.{m.group(1)} res-এ নেই", line)

print(f"  স্ক্যান: {len(kt)}টা .kt · পুল: {len(strings)} string · {len(ids)} id · {len(drawable)} drawable · {len(layout)} layout")
if problems:
    print(f"❌ {len(problems)}টা সমস্যা:")
    for x in problems[:35]: print("   " + x)
    sys.exit(1)
print("✅ kotlin_sanity পরিষ্কার")
PY
