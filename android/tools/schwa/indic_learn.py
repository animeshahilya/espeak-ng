"""Learn Indic inherent-vowel correction rules expressible as eSpeak contexts.

usage: python indic_learn.py LANG [MAX_RULES]

Learns DROP where eSpeak wrongly keeps the vowel, and KEEP where it wrongly drops it.
Rules are learned on 80% train words (hash split) and evaluated on 20% held-out test words.
Writes <lang>_rules_learned.json.
"""
import collections, hashlib, itertools, json, os, sys

SCRIPTS = {
    'deva': {
        'cons': set(chr(c) for c in range(0x915, 0x93A)) | set(chr(c) for c in range(0x958, 0x960)),
        'signs': set(chr(c) for c in range(0x93E, 0x94D)) | {'ॢ', 'ॣ'},
        'indep': set(chr(c) for c in range(0x904, 0x915)) | {'ॠ', 'ॡ'},
        'virama': '्',
        'inh_char': 'अ',
        'nasals': set('ंँ'),
    },
    'gujr': {
        'cons': set(chr(c) for c in range(0x0A95, 0x0ABA)),
        'signs': set(chr(c) for c in range(0x0ABE, 0x0ACD)) | {'ૢ', 'ૣ', 'ૅ', 'ૉ'},
        'indep': set(chr(c) for c in range(0x0A85, 0x0A95)) | {'ૠ', 'ૡ', 'ઍ', 'ઑ'},
        'virama': '્',
        'inh_char': 'અ',
        'nasals': set('ંઁ'),
    },
    'guru': {
        'cons': set(chr(c) for c in range(0x0A15, 0x0A3A)) | set(chr(c) for c in range(0x0A59, 0x0A5F)) | {'ਲ਼', 'ਸ਼'},
        'signs': set(chr(c) for c in range(0x0A3E, 0x0A4D)),
        'indep': set(chr(c) for c in range(0x0A05, 0x0A15)),
        'virama': '੍',
        'inh_char': 'ਅ',
        'nasals': set('ੰਂ'),
    },
    'beng': {
        'cons': set(chr(c) for c in range(0x0995, 0x09BA)) | set('ড়ঢ়য়'),
        'signs': set('ািীুূৃৄেৈোৌ'),
        'indep': set('অআইঈউঊঋএঐওঔ'),
        'virama': '্',
        'inh_char': 'অ',
        'nasals': set('ংঁঃ'),
    },
}

LANG_SCRIPT = {
    'hi': 'deva',
    'mr': 'deva',
    'ne': 'deva',
    'gu': 'gujr',
    'pa': 'guru',
    'bn': 'beng',
}

POS = ['pre3', 'pre2', 'pre1', 'self', 'post1', 'post2', 'post3']


def make_cls(script):
    s = SCRIPTS[script]
    cons, signs, indep = s['cons'], s['signs'], s['indep']
    inh_char, virama, nasals = s['inh_char'], s['virama'], s['nasals']

    def cls(ch):
        if ch == '_': return '_'
        if ch in cons: return 'C'
        if ch in signs or (ch in indep and ch != inh_char): return 'V'
        if ch == inh_char: return inh_char
        if ch == virama: return virama
        if ch in nasals: return 'N'
        return 'X'
    return cls


def context(w, i):
    pad = '___' + w + '___'
    j = i + 3
    return {'pre3': pad[j-3], 'pre2': pad[j-2], 'pre1': pad[j-1], 'self': pad[j],
            'post1': pad[j+1], 'post2': pad[j+2], 'post3': pad[j+3]}


def feats(ctx, cls_fn):
    out = []
    for p in POS:
        out.append((p, 'L:' + ctx[p]))
        if p != 'self':
            out.append((p, 'K:' + cls_fn(ctx[p])))
    return out


def is_test(w):
    return int(hashlib.md5(w.encode()).hexdigest(), 16) % 5 == 0


def load(lang):
    script_name = LANG_SCRIPT.get(lang, 'deva')
    cls_fn = make_cls(script_name)
    data = []
    for line in open(f'{lang}_slots.tsv', encoding='utf-8'):
        w, i, want, now = line.rstrip('\n').split('\t')
        ctx = context(w, int(i))
        data.append((w, int(i), want == 'DROP', frozenset(feats(ctx, cls_fn)), now == 'DROP'))
    return data


def learn(train, max_rules, min_gain=5, max_atoms=3, min_prec=0.75):
    pred = [d[4] for d in train]
    rules = []
    counts = collections.Counter(a for d in train for a in d[3])
    for _ in range(max_rules):
        cand = collections.Counter()
        for k, d in enumerate(train):
            if d[2] == pred[k]:
                continue
            fl = sorted(a for a in d[3] if counts[a] >= 5)
            for n in range(1, max_atoms + 1):
                for combo in itertools.combinations(fl, n):
                    if len({p for p, _ in combo}) == n:
                        cand[frozenset(combo)] += 1
        best = None
        for rule, _ in cand.most_common(3000):
            for target in (True, False):
                good = bad = 0
                for k, d in enumerate(train):
                    if pred[k] != target and rule <= d[3]:
                        if d[2] == target: good += 1
                        else: bad += 1
                if good + bad == 0 or good / (good + bad) < min_prec:
                    continue
                if best is None or good - bad > best[0]:
                    best = (good - bad, rule, target)
        if best is None or best[0] < min_gain:
            break
        gain, rule, target = best
        rules.append((sorted(rule), target, gain))
        for k, d in enumerate(train):
            if rule <= d[3]:
                pred[k] = target
        print(f'  rule {len(rules)}: {"DROP" if target else "KEEP"} {sorted(rule)} gain {gain}', file=sys.stderr)
    return rules


def apply(rules, data):
    out = []
    for d in data:
        p = d[4]
        for rule, target, _ in rules:
            if frozenset(rule) <= d[3]:
                p = target
        out.append(p)
    return out


def report(name, data, pred):
    right_before = sum(d[2] == d[4] for d in data)
    right_after = sum(d[2] == p for d, p in zip(data, pred))
    fixed = sum(d[2] == p and d[2] != d[4] for d, p in zip(data, pred))
    broke = sum(d[2] != p and d[2] == d[4] for d, p in zip(data, pred))
    pct_before = 100 * right_before / len(data) if data else 0
    pct_after = 100 * right_after / len(data) if data else 0
    print(f'{name}: slots {len(data)}, right {right_before} -> {right_after} ({pct_before:.1f}% -> {pct_after:.1f}%), fixed {fixed}, broke {broke}')


def main():
    if len(sys.argv) < 2:
        print("usage: python indic_learn.py LANG [MAX_RULES]")
        sys.exit(1)
    lang = sys.argv[1]
    max_rules = int(sys.argv[2]) if len(sys.argv) > 2 else 40
    min_prec = float(os.environ.get('MIN_PREC', '0.75'))

    data = load(lang)
    train = [d for d in data if not is_test(d[0])]
    test = [d for d in data if is_test(d[0])]
    print(f'Loaded {len(data)} slots ({len(train)} train, {len(test)} test). Learning rules (min_prec={min_prec})...')

    rules = learn(train, max_rules, min_prec=min_prec)
    report('train', train, apply(rules, train))
    report('TEST', test, apply(rules, test))

    out_file = f'{lang}_rules_learned.json'
    json.dump(rules, open(out_file, 'w', encoding='utf-8'), ensure_ascii=False)
    print(f'Wrote {len(rules)} rules to {out_file}')


if __name__ == '__main__':
    main()
