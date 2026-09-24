"""Learn Bengali inherent-vowel deletion rules expressible as eSpeak contexts.

Greedy rule induction over bn_slots.tsv: each candidate rule is a set of
positional constraints (pre1..pre3, post1..post3, the consonant itself),
each an exact letter or a letter class. Rules are learned on the train
words only (hash split) and scored on the held-out test words.
"""
import collections, hashlib, itertools, json, sys

CONS = set(chr(c) for c in range(0x995, 0x9BA)) | set('ড়ঢ়য়')
SIGNS = set('ািীুূৃৄেৈোৌ')
INDEP = set('আইঈউঊঋএঐওঔ')

def cls(ch):
    if ch == '_': return '_'
    if ch in CONS: return 'C'
    if ch in SIGNS or ch in INDEP: return 'V'   # a written vowel (not অ)
    if ch == 'অ': return 'অ'
    if ch == '্': return '্'
    return 'X'

MIN_PREC = float(__import__('os').environ.get('MIN_PREC', '0'))
POS = ['pre3', 'pre2', 'pre1', 'self', 'post1', 'post2', 'post3']

def context(w, i):
    pad = '___' + w + '___'
    j = i + 3
    return {'pre3': pad[j-3], 'pre2': pad[j-2], 'pre1': pad[j-1], 'self': pad[j],
            'post1': pad[j+1], 'post2': pad[j+2], 'post3': pad[j+3]}

def feats(ctx):
    """All (pos, value) atoms: exact letter or class, per position."""
    out = []
    for p in POS:
        out.append((p, 'L:' + ctx[p]))
        if p != 'self':
            out.append((p, 'K:' + cls(ctx[p])))
    return out

def is_test(w):
    return int(hashlib.md5(w.encode()).hexdigest(), 16) % 5 == 0

def load():
    rows = [l.rstrip('\n').split('\t') for l in open('bn_slots.tsv', encoding='utf-8')]
    data = []
    for w, i, label in rows:
        ctx = context(w, int(i))
        data.append((w, int(i), label == 'DROP', frozenset(feats(ctx))))
    return data

def matches(rule, fs):
    return rule <= fs

def learn(train, max_rules=40, min_gain=3, max_atoms=3):
    pred = [False] * len(train)
    rules = []
    atoms_all = collections.Counter(a for _, _, _, fs in train for a in fs)
    atoms = [a for a, c in atoms_all.items() if c >= 5]
    for _ in range(max_rules):
        best = None
        # candidate rules: combos of up to max_atoms atoms seen together in an
        # error slot (limits the search to rules that fix something)
        cand = collections.Counter()
        for k, (_, _, y, fs) in enumerate(train):
            if y == pred[k]:
                continue
            fl = [a for a in fs if a in atoms_all and atoms_all[a] >= 5]
            for n in range(1, max_atoms + 1):
                for combo in itertools.combinations(sorted(fl), n):
                    if len({p for p, _ in combo}) == n:
                        cand[frozenset(combo)] += 1
        for rule, _ in cand.most_common(4000):
            for target in (True, False):
                good = bad = 0
                for k, (_, _, y, fs) in enumerate(train):
                    if pred[k] != target and rule <= fs:
                        if y == target: good += 1
                        else: bad += 1
                gain = good - bad
                if good + bad and good / (good + bad) < MIN_PREC:
                    continue
                if best is None or gain > best[0]:
                    best = (gain, rule, target)
        if best is None or best[0] < min_gain:
            break
        gain, rule, target = best
        rules.append((sorted(rule), target, gain))
        for k, (_, _, y, fs) in enumerate(train):
            if rule <= fs:
                pred[k] = target
        print(f'  rule {len(rules)}: {"DROP" if target else "KEEP"} {sorted(rule)} gain {gain}', file=sys.stderr)
    return rules

def apply(rules, data):
    pred = []
    for _, _, _, fs in data:
        p = False
        for rule, target, _ in rules:
            if frozenset(rule) <= fs:
                p = target
        pred.append(p)
    return pred

def score(data, pred):
    tp = sum(1 for d, p in zip(data, pred) if d[2] and p)
    fp = sum(1 for d, p in zip(data, pred) if not d[2] and p)
    fn = sum(1 for d, p in zip(data, pred) if d[2] and not p)
    return tp, fp, fn

if __name__ == '__main__':
    data = load()
    train = [d for d in data if not is_test(d[0])]
    test = [d for d in data if is_test(d[0])]
    print(f'train slots {len(train)} (drop {sum(d[2] for d in train)}), test slots {len(test)} (drop {sum(d[2] for d in test)})')
    rules = learn(train, max_rules=int(sys.argv[1]) if len(sys.argv) > 1 else 30)
    for name, part in (('train', train), ('test', test)):
        tp, fp, fn = score(part, apply(rules, part))
        print(f'{name}: correct drops {tp}, wrong drops {fp}, missed drops {fn} -> net slots fixed {tp - fp}')
    json.dump(rules, open('bn_rules_learned.json', 'w', encoding='utf-8'), ensure_ascii=False)
