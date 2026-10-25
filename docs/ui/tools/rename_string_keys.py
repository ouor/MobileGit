"""ARC-13: rename numbered string keys. Usage: rename_keys.py map.txt <app/src dir>. Map lines: old new."""
import os, re, sys
m = dict(l.split() for l in open(sys.argv[1], encoding='utf-8') if l.strip())
assert len(set(m.values())) == len(m)
pat = re.compile(r'\b(R\.string\.|@string/|name=")(' + '|'.join(map(re.escape, m)) + r')\b')
n = 0
for root, _, files in os.walk(sys.argv[2]):
    for f in files:
        if not f.endswith(('.kt', '.xml')): continue
        p = os.path.join(root, f)
        s = open(p, encoding='utf-8').read()
        t, k = pat.subn(lambda x: x.group(1) + m[x.group(2)], s)
        if k: open(p, 'w', encoding='utf-8', newline='').write(t); n += k
print('replaced', n)
