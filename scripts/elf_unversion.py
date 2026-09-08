#!/usr/bin/env python3
"""
elf_unversion.py — Android-এ bundle করার জন্য ELF-এর versioned library নাম
**in-place** আনভার্সন করে (libssl.so.3 → libssl.so), ফাইলের সাইজ বা
segment layout একেবারেই না বদলে।

কেন এটা দরকার (মাপা, beta.12):
  patchelf --replace-needed = dynstr/dynamic রিলোকেট করে → php-র ৩টা PT_LOAD-এর
  জায়গায় ৫টা হয়ে গেছে, নতুন RW সেগমেন্টের (p_vaddr - p_offset) % p_align ≠ 0 →
  Android linker-এ ম্যাপিং ভাঙে → `exit=139` (SIGSEGV)।
  শুধু ছোট করা হলে (versioned → unversioned সবসময় ছোট) strtab-এর ভেতরেই
  জায়গা থাকে → কোনো নতুন সেকশন/সেগমেন্ট লাগে না → layout অক্ষত থাকে।

কী ক্যাম্প করে:
  • DT_NEEDED   : map অনুযায়ী নাম বদলায় (shorten হলে)
  • DT_SONAME   : provider ফাইলটার SONAME == ফাইলনাম করার চেষ্টা (prefix হলে)
  • DT_VERNEED  : vn_file (lib-এর নাম) DT_NEEDED-এর সঙ্গে আলাদা করে থাকে,
                  তাই এটাও ঠিক করা বাধ্যতামূলক
  • alias ফাইল  : যেগুলোর SONAME ফাইলনামের prefix না (libcurses.so → libncursesw.so.6)
                  সেগুলো মুছে ফেলা হয়, আর কনজিউমারদের canonical নামে ম্যাপ করা হয়

Usage:
  python3 scripts/elf_unversion.py <libdir> [<libdir> …] [--dry-run]
Exit: 0 = ঠিক · 1 = কিছু shortenable না / ভুল
"""
import argparse, os, struct, sys, glob

DT_NULL, DT_NEEDED, DT_STRTAB, DT_STRSZ, DT_SONAME = 0, 1, 5, 10, 14
DT_VERNEED, DT_VERNEEDNUM = 0x6FFFFFFE, 0x6FFFFFFF
SHT_DYNAMIC = 6

class Elf:
    """এলিমেন্টারি ELF64 LE পার্সার (শুই .dynamic/.dynstr/.gnu.version_r পড়ার জন্যই)"""
    def __init__(self, path):
        self.path = path
        self.buf = bytearray(open(path, 'rb').read())
        if self.buf[:4] != b'\x7fELF' or self.buf[4] != 2 or self.buf[5] != 1:
            raise ValueError("শুধু ELF64 little-endian সাপোর্টেড")
        e_shoff = struct.unpack_from('<Q', self.buf, 0x28)[0]
        e_shentsize, e_shnum = struct.unpack_from('<HH', self.buf, 0x3A)
        self.secs = []
        for i in range(e_shnum):
            o = e_shoff + i * e_shentsize
            sh_type, = struct.unpack_from('<I', self.buf, o + 4)
            sh_addr, sh_offset, sh_size = struct.unpack_from('<QQQ', self.buf, o + 16)
            sh_link, = struct.unpack_from('<I', self.buf, o + 40)
            self.secs.append(dict(type=sh_type, addr=sh_addr, off=sh_offset, size=sh_size, link=sh_link))
        self.dyn = next((s for s in self.secs if s['type'] == SHT_DYNAMIC), None)
        if not self.dyn:
            raise ValueError(".dynamic নেই (shared object না)")
        self.strtab = self.secs[self.dyn['link']]
        self.entries = []          # (tag, val, dyn_file_off)
        p = self.dyn['off']
        while True:
            tag, val = struct.unpack_from('<qQ', self.buf, p)
            if tag == DT_NULL:
                break
            self.entries.append((tag, val, p))
            p += 16

    def str_at(self, off):
        s = self.strtab['off'] + off
        end = self.buf.index(b'\0', s)
        return bytes(self.buf[s:end]).decode('utf-8', 'replace')

    def sect_by_vaddr(self, va):
        for s in self.secs:
            if s['addr'] and s['addr'] <= va < s['addr'] + s['size']:
                return s
        return None

    def patch_str(self, dyn_off_of_str, new):
        """strtab-এর সেই অফসেটে নতুন নাম লিখে বাকিটা NUL — সাইজ কখনো বাড়ে না"""
        s = self.strtab['off'] + dyn_off_of_str
        old_end = self.buf.index(b'\0', s)
        old_len = old_end - s
        nb = new.encode()
        if len(nb) > old_len:
            return False
        self.buf[s:s + len(nb)] = nb
        for i in range(s + len(nb), s + old_len + 1):
            self.buf[i] = 0
        return True

    def save(self):
        open(self.path, 'wb').write(bytes(self.buf))


def strip_version(name):
    """libssl.so.3 → libssl.so ; libsqlite3.53.4.so → libsqlite3.so"""
    base = name.split('.so')[0]
    return base + '.so' if base else name


def verneed_records(e):
    """(vn_file_strtab_off, file_off_of_vn_file) লিস্ট"""
    out = []
    va = next((v for t, v, _ in e.entries if t == DT_VERNEED), None)
    if va is None:
        return out
    sec = e.sect_by_vaddr(va)
    if not sec:
        return out
    p = sec['off'] + (va - sec['addr'])
    cnt = struct.unpack_from('<H', e.buf, p + 2)[0]
    for _ in range(cnt):
        vn_file, vn_aux, vn_next = struct.unpack_from('<III', e.buf, p + 4)
        out.append((vn_file, p + 4))
        # Verneed->Verdaux chain (আমরা শুধু file name টা চাই)
        if vn_next == 0:
            break
        p += vn_next
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('dirs', nargs='+')
    ap.add_argument('--dry-run', action='store_true')
    ap.add_argument('--verbose', action='store_true')
    a = ap.parse_args()

    libs = []
    for d in a.dirs:
        libs += [f for f in sorted(glob.glob(os.path.join(d, '*.so'))) if os.path.isfile(f)]
    by_name = {os.path.basename(p): p for p in libs}

    # ── provider SONAME টেবিল + alias/ডিলিট প্ল্যান ─────────────────
    soname = {}
    for p in libs:
        try:
            e = Elf(p)
            sn = next((e.str_at(v) for t, v, _ in e.entries if t == DT_SONAME), None)
            soname[os.path.basename(p)] = (sn, p)
        except Exception:
            continue
    drop, namemap = set(), {}
    for fn, (sn, path) in soname.items():
        if sn is None:
            continue
        if sn == fn:
            continue
        if sn.startswith(fn):        # libncursesw.so.6 → libncursesw.so : prefix, in-place ঠিক করা যাবে
            continue
        # alias ফাইল (libtinfo.so → SONAME libncursesw.so.6) : মুছে canonical-এ ম্যাপ করি
        canon = strip_version(sn)
        if canon in by_name and canon != fn:
            drop.add(fn)
            namemap[sn] = canon
            namemap[fn] = canon

    # Android-এর নিজস্ব লাইব্রেরি — jniLibs-এ থাকার দরকার নেই, হাত দেওয়া যাবে না
    SYSTEM = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so', 'ld.so',
              'libstdc++.so', 'libc++.so'}

    def target_for(name):
        """consumer-এর DT_NEEDED → কোন নামে বসাবে"""
        if name in SYSTEM:
            return name
        if name in namemap:
            return namemap[name]
        if name in by_name:                      # ফাইল আছে, নাম বদলাতে হবে না
            return name
        cand = strip_version(name)               # libssl.so.3 → libssl.so
        if cand in by_name and cand not in drop:
            return cand
        return None

    changed = patched_need = patched_sn = patched_ver = 0
    problems = []
    for p in libs:
        fn = os.path.basename(p)
        if fn in drop:
            continue
        try:
            e = Elf(p)
        except Exception as ex:
            if a.verbose: print(f"  · স্কিপ {fn}: {ex}")
            continue
        touched = False
        # ১) DT_NEEDED
        for tag, val, _ in list(e.entries):
            if tag != DT_NEEDED:
                continue
            cur = e.str_at(val)
            tgt = target_for(cur)
            if tgt is None:
                problems.append(f"{fn}: NEEDED '{cur}' → কোনো provider নেই")
                continue
            if tgt != cur:
                if e.patch_str(val, tgt):
                    patched_need += 1; touched = True
                    if a.verbose: print(f"  ✏️ {fn}: NEEDED {cur} → {tgt}")
                else:
                    problems.append(f"{fn}: '{cur}' → '{tgt}' লম্বা, in-place সম্ভব না")
        # ২) SONAME (provider নিজে)
        sn = next((e.str_at(v) for t, v, _ in e.entries if t == DT_SONAME), None)
        if sn and sn != fn and fn.startswith(sn) is False and sn.startswith(fn):
            if e.patch_str(next(v for t, v, _ in e.entries if t == DT_SONAME), fn):
                patched_sn += 1; touched = True
        elif sn and sn != fn and not sn.startswith(fn):
            problems.append(f"{fn}: SONAME '{sn}' ফাইলনামের prefix না (alias → ডিলিট হওয়া চাই)")
        # ৩) DT_VERNEED vn_file
        for (str_off, _foff) in verneed_records(e):
            cur = e.str_at(str_off)
            tgt = target_for(cur)
            if tgt and tgt != cur:
                if e.patch_str(str_off, tgt):
                    patched_ver += 1; touched = True
                else:
                    problems.append(f"{fn}: verneed '{cur}' → '{tgt}' shortenable না")
        if touched:
            changed += 1
            if not a.dry_run:
                e.save()

    # ── alias + ভার্সনড ডুপ্লিকেট ডিলিট ─────────────────────────────
    removed = []
    for fn in sorted(drop):
        removed.append(fn)
        if not a.dry_run:
            os.remove(soname[fn][1])
    for p in libs:
        fn = os.path.basename(p)
        if '.so.' in fn and strip_version(fn) in by_name:
            removed.append(fn)
            if not a.dry_run:
                os.remove(p)

    print(f"  🔧 elf_unversion: {changed}টা ELF প্যাচড · NEEDED {patched_need} · SONAME {patched_sn} "
          f"· VERNEED {patched_ver} · {len(removed)}টা alias/ভার্সনড কপি সরানো")
    if problems:
        print("  ⚠️ সমস্যা:")
        for x in sorted(set(problems))[:15]:
            print("     " + x)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
