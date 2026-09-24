"""Devanagari schwa slots (hi, mr, ne): what eSpeak does now and what
WikiPron wants.

usage: deva_slots.py LANG WIKIPRON_TSV

Needs LANG_nodel.tsv (eSpeak with schwa deletion switched off, so every
inherent vowel is spoken) and LANG_norm.tsv (normal eSpeak). Each inherent
vowel is mapped to its position in the no-deletion output; the reference
alignment says whether it should be kept, and the normal output says
whether eSpeak keeps it today. Writes LANG_slots.tsv: word, index, want, now
(DROP/KEEP).
"""
import sys
import score as S

CONS = set(chr(c) for c in range(0x915, 0x93A)) | set(chr(c) for c in range(0x958, 0x960))
SIGNS = set(chr(c) for c in range(0x93E, 0x94D)) | {'ॢ', 'ॣ'}
INDEP = set(chr(c) for c in range(0x904, 0x915)) | {'ॠ', 'ॡ'}
VIRAMA, NUKTA = '्', '़'
VOWELS = set('aeiouə')


def sources(word):
    out = []
    for i, ch in enumerate(word):
        nxt = word[i + 1] if i + 1 < len(word) else ''
        if nxt == NUKTA:                       # क़ written as क + nukta
            nxt = word[i + 2] if i + 2 < len(word) else ''
        if ch in INDEP:
            out.append((i, 'inh' if ch == 'अ' else 'exp'))
        elif ch in SIGNS:
            out.append((i, 'exp'))
        elif ch in CONS and nxt not in SIGNS and nxt != VIRAMA:
            out.append((i, 'inh'))
    return out


def load(path):
    d = {}
    for line in open(path, encoding='utf-8'):
        w, _, g = line.rstrip('\n').partition('\t')
        d[w] = g
    return d


def main():
    ref = {}
    lang, tsv = sys.argv[1], sys.argv[2]
    for line in open(tsv, encoding='utf-8'):
        w, _, p = line.rstrip('\n').partition('\t')
        if ' ' not in w and w not in ref:
            ref[w] = p
    nodel, norm = load(f'{lang}_nodel.tsv'), load(f'{lang}_norm.tsv')
    rows, used, skipped = [], 0, 0
    for w in ref:
        if w not in nodel or w not in norm:
            continue
        gp = S.phones(nodel[w])
        src = sources(w)
        gv = [k for k, p in enumerate(gp) if p[:1] in VOWELS]
        if len(gv) == len(src) - 1 and src and src[-1][1] == 'inh':
            src = src[:-1]                     # final inherent vowel, never spoken
        if len(gv) != len(src):
            skipped += 1
            continue
        used += 1
        # ᵊ is a brief vowel release (मित्र mɪt̪ɾᵊ); eSpeak says it as @-, so it
        # counts as a vowel here even though the scorer normally drops it.
        _, pr = S.align(S.phones(ref[w].replace('ᵊ', 'ə')), gp)  # reference vs no-deletion
        _, pn = S.align(gp, S.phones(norm[w])) # no-deletion vs normal
        want, now = {}, {}
        k = 0
        for a, b in pr:
            if b != '∅':
                want[k] = a != '∅'
                k += 1
        k = 0
        for a, b in pn:
            if a != '∅':
                now[k] = b != '∅'
                k += 1
        for (idx, kind), gpos in zip(src, gv):
            if kind != 'inh' or idx == 0:
                continue
            rows.append((w, idx, 'KEEP' if want.get(gpos, True) else 'DROP',
                         'KEEP' if now.get(gpos, True) else 'DROP'))
    with open(f'{lang}_slots.tsv', 'w', encoding='utf-8') as f:
        for r in rows:
            f.write('%s\t%d\t%s\t%s\n' % r)
    import collections
    c = collections.Counter((r[2], r[3]) for r in rows)
    print(f'words used {used}, skipped {skipped}; slots {len(rows)}')
    print('want/now:', dict(c))


if __name__ == '__main__':
    main()
