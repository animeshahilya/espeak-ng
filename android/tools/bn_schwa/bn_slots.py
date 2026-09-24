"""Bengali inherent-vowel slots with keep/drop labels from WikiPron.

For each word: find every consonant that carries the inherent vowel (no
vowel sign, virama or nukta after it), map eSpeak's output vowels onto the
word's vowel sources in order, align eSpeak's phones with the reference,
and label each inherent vowel eSpeak says: KEEP if the reference has a
vowel there, DROP if the reference has none. Words whose vowel counts do
not line up are skipped. Writes bn_slots.tsv: word, index, label.
"""
import score as S

CONS = set(chr(c) for c in range(0x995, 0x9BA)) | set('ড়ঢ়য়')
SIGNS = set('ািীুূৃৄেৈোৌ')
INDEP = set('অআইঈউঊঋএঐওঔ')
VIRAMA, NUKTA = '্', '়'
VOWELS = set('aeiouə')


def sources(word):
    """[(index, 'inh'|'exp')] in reading order."""
    out = []
    for i, ch in enumerate(word):
        nxt = word[i + 1] if i + 1 < len(word) else ''
        if ch in INDEP:
            out.append((i, 'inh' if ch == 'অ' else 'exp'))
        elif ch in SIGNS:
            out.append((i, 'exp'))
        elif ch in CONS and nxt not in SIGNS and nxt not in (VIRAMA, NUKTA):
            out.append((i, 'inh'))
    return out


def main():
    ref = {}
    for line in open('ben_beng_dhaka_broad.tsv', encoding='utf-8'):
        w, _, p = line.rstrip('\n').partition('\t')
        if ' ' not in w and w not in ref:
            ref[w] = p
    words = list(ref)
    got = []
    for i in range(0, len(words), 2000):
        got += S.espeak('bn', words[i:i + 2000])
    rows, used, skipped = [], 0, 0
    for w, g in zip(words, got):
        gp = S.phones(g)
        rp = S.phones(ref[w])
        src = sources(w)
        gv = [k for k, p in enumerate(gp) if p[:1] in VOWELS]
        # eSpeak drops the final inherent vowel itself; drop that source too.
        if len(gv) == len(src) - 1 and src and src[-1][1] == 'inh':
            src = src[:-1]
        if len(gv) != len(src):
            skipped += 1
            continue
        used += 1
        _, pairs = S.align(rp, gp)
        # position in gp -> aligned ref symbol
        k = 0
        aligned = {}
        for a, b in pairs:
            if b != '∅':
                aligned[k] = a
                k += 1
        for (idx, kind), gpos in zip(src, gv):
            if kind != 'inh' or idx == 0 and w[0] == 'অ':
                continue
            label = 'DROP' if aligned.get(gpos) == '∅' else 'KEEP'
            rows.append((w, idx, label))
    with open('bn_slots.tsv', 'w', encoding='utf-8') as f:
        for r in rows:
            f.write('%s\t%d\t%s\n' % r)
    drops = sum(1 for r in rows if r[2] == 'DROP')
    print(f'words used {used}, skipped {skipped}; slots {len(rows)}, DROP {drops}')


if __name__ == '__main__':
    main()
