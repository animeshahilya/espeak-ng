"""Devanagari (hi, mr, ne) schwa-correction rules

usage: deva_learn.py LANG [MAX_RULES]
 on top of eSpeak's current behaviour.

Same rule induction as bn_learn, but each slot starts from what eSpeak does
today (LANG_slots.tsv 'now'), so rules only learn corrections: DROP where
eSpeak wrongly keeps the vowel, KEEP where it wrongly drops it.
"""
import collections, hashlib, itertools, json, os, sys
import bn_learn as B

CONS = set(chr(c) for c in range(0x915, 0x93A)) | set(chr(c) for c in range(0x958, 0x960))
SIGNS = set(chr(c) for c in range(0x93E, 0x94D)) | {'ॢ', 'ॣ'}
INDEP = set(chr(c) for c in range(0x904, 0x915)) | {'ॠ', 'ॡ'}

def cls(ch):
    if ch == '_': return '_'
    if ch in CONS: return 'C'
    if ch in SIGNS or (ch in INDEP and ch != 'अ'): return 'V'
    if ch == 'अ': return 'अ'
    if ch == '्': return '्'
    if ch in 'ंँ': return 'N'      # anusvara, candrabindu
    return 'X'

B.cls = cls

def load(lang):
    data = []
    for line in open(f'{lang}_slots.tsv', encoding='utf-8'):
        w, i, want, now = line.rstrip('\n').split('\t')
        ctx = B.context(w, int(i))
        data.append((w, int(i), want == 'DROP', frozenset(B.feats(ctx)), now == 'DROP'))
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
    print(f'{name}: slots {len(data)}, right {right_before} -> {right_after} ({100*right_before/len(data):.1f}% -> {100*right_after/len(data):.1f}%), fixed {fixed}, broke {broke}')

if __name__ == '__main__':
    lang = sys.argv[1]
    data = load(lang)
    train = [d for d in data if not B.is_test(d[0])]
    test = [d for d in data if B.is_test(d[0])]
    rules = learn(train, int(sys.argv[2]) if len(sys.argv) > 2 else 40,
                  min_prec=float(os.environ.get('MIN_PREC', '0.75')))
    report('train', train, apply(rules, train))
    report('TEST', test, apply(rules, test))
    json.dump(rules, open(f'{lang}_rules_learned.json', 'w', encoding='utf-8'), ensure_ascii=False)
