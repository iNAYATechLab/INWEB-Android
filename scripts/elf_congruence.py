#!/usr/bin/env python3
"""
elf_congruence.py — PT_LOAD congruence / alignment গেট

Android linker (bionic) ELF লোড করার আগে চেক করে:
    (p_vaddr - p_offset) % p_align == 0
এটা ভাঙলে সegnমেন্ট ম্যাপিং ঠিকমতো হয় না → রানটাইমে SIGSEGV/SIGBUS, আর
১৬ KB পেজ ডিভাইসে (Android 15/16) ব্যাপারটা আরও কঠোর।

কেন এটা INWEB-এ দরকার (মাপা, beta.12): patchelf DT_NEEDED রিরাইট করার সময়
php-র ৩টা PT_LOAD-এর জায়গায় ৫টা বানিয়ে ফেলেছিল, নতুন দুটো incongruent →
`php -v` = exit 139। এই গেট থাকলে সেই বিল্ড CI-তেই থামত।

Usage: python3 scripts/elf_congruence.py <dir> [<dir> …]
Exit : 0 = সব ঠিক · 1 = সমস্যা
"""
import os, struct, sys, glob

PF_X, PT_LOAD, PT_GNU_RELRO = 1, 1, 0x6474E552

def check(path):
    b = open(path, 'rb').read()
    if b[:4] != b'\x7fELF':
        return []
    if b[4] != 2:                      # শুধু ELF64
        return []
    e_phoff, = struct.unpack_from('<Q', b, 0x20)
    e_phentsize, e_phnum = struct.unpack_from('<HH', b, 0x36)
    errs = []
    n_load = 0
    for i in range(e_phnum):
        o = e_phoff + i * e_phentsize
        p_type, p_flags = struct.unpack_from('<II', b, o)
        p_offset, p_vaddr = struct.unpack_from('<QQ', b, o + 8)
        p_filesz, p_memsz, p_align = struct.unpack_from('<QQQ', b, o + 32)
        # PT_GNU_RELRO-র p_align ইচ্ছাই 1 (bionic সেটা দেখে না) → শুধু PT_LOAD চেক করি
        if p_type != PT_LOAD:
            continue
        n_load += 1
        if p_align == 0 or (p_align & (p_align - 1)) != 0:
            errs.append(f"p_align অশূন্য/পাওয়ার-অফ-টু হতে হবে (পাওয়া গেছে {p_align:#x})")
            continue
        if (p_vaddr - p_offset) % p_align != 0:
            errs.append(f"incongruent: (vaddr {p_vaddr:#x} - off {p_offset:#x}) "
                        f"% align {p_align:#x} = {(p_vaddr - p_offset) % p_align:#x}")
        if p_align < 0x1000:
            errs.append(f"p_align {p_align:#x} < 4 KB পেজ")
        if p_type == PT_LOAD and (p_flags & PF_X) and p_align < 0x4000:
            errs.append("exec segment-এর align 4 KB-এর কম")
    if n_load > 8:
        errs.append(f"সন্দেহজনক: {n_load}টা PT_LOAD (ELF ররাইটার লেআউট বদলে দিয়েছে?)")
    return errs


def main():
    if len(sys.argv) < 2:
        print(__doc__); return 2
    files = []
    for d in sys.argv[1:]:
        if os.path.isdir(d):
            files += [f for f in sorted(glob.glob(os.path.join(d, '*'))) if os.path.isfile(f)]
        elif os.path.isfile(d):
            files.append(d)
    bad = 0
    for f in files:
        try:
            errs = check(f)
        except Exception as e:
            errs = [f"পার্স ব্যর্থ: {e}"]
        if errs:
            bad += 1
            print(f"  ❌ {os.path.basename(f)}")
            for e in errs:
                print(f"       {e}")
    print(f"  🔍 elf_congruence: {len(files)}টা ফাইল চেকড · {bad}টা সমস্যা"
          + ("" if bad else " ✅"))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
