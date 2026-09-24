"""Turn bn_rules_learned.json into eSpeak rule lines inside bn_rules.

usage: bn_emit.py BN_RULES_IN BN_RULES_OUT [N_RULES]
Safe to re-run on its own output: earlier generated lines are removed first.
"""
import json, re, sys

V = 'L10'   # written vowels: signs and independent vowels, not অ
C = 'L11'   # consonant letters
ANY = 'L12' # any Bengali letter
HEADER = """// Inherent vowel dropped in the middle of a word (আপনি apni, আমরা amra,
// আকবর akbor). The rule lines marked "// generated" in each consonant group
// are not hand-written: android/tools/bn_schwa learns them from
// WikiPron (rule induction on 80% of the words) and writes them here. On the
// 20% it never saw, words read right went from 59.0% to 61.9% (50 fixed,
// 12 broken). Regenerate them with those tools rather than editing by hand.
// The "_L11) X (suffix_" lines are a hand rule for verb forms (বলতে bolte,
// করছে korchhe), which the word list the rules were learned from lacks.
// L10 = written vowels (signs, and independent vowels except অ);
// L11 = consonant letters; L12 = any Bengali letter.
"""
GROUPS = HEADER + """.L10 া ি ী ু ূ ৃ ৄ ে ৈ ো ৌ আ ই ঈ উ ঊ ঋ এ ঐ ও ঔ
.L11 %s
.L12 %s
""" % (' '.join([chr(c) for c in range(0x995, 0x9BA)] + ['ড়', 'ঢ়', 'য়']),
       ' '.join([chr(c) for c in range(0x985, 0x9BA)] + [chr(c) for c in range(0x9BE, 0x9CE)] + ['ড়', 'ঢ়', 'য়', 'ং', 'ঁ', 'ঃ']))

# Verb forms: a one-consonant stem + this consonant + a verb suffix drops the
# vowel (বলতে bolte, করছে korchhe, ধরলে dhorle).
VERB_SUFFIXES = ['তে', 'লে', 'ছে', 'লাম', 'তাম', 'ছি', 'ছিল', 'লো', 'বে', 'বো',
                 'লেন', 'তেন', 'ছেন', 'বেন', 'তিস', 'লি', 'ছিস']

TAG = '// generated'


def strip_generated(lines):
    """Remove lines an earlier run added, so the tool can be re-run."""
    out, skip, after = [], False, False
    for line in lines:
        if line.startswith(HEADER.splitlines()[0]):
            skip = True
        if skip:
            if line.startswith('.L12'):
                skip, after = False, True
            continue
        if after:
            after = False
            if not line.strip():
                continue   # the blank line the header block ends with
        if line.rstrip().endswith(TAG):
            continue
        out.append(line)
    return out


def atom(a):
    kind, val = a[:2], a[2:]
    if kind == 'L:':
        return None if val == '_' else val
    return {'C': C, 'V': V, 'অ': 'অ', '্': '্'}.get(val, 'SKIP')

def side(cons, names, implicit_first=None):
    """cons: {pos: atom}. names: ['pre1','pre2','pre3'] or post.
    Yields context strings for each possible distance to the word edge."""
    out = []
    for n in range(0, 4):       # letters present before the edge (3 = 3 or more)
        ok = True
        items = []
        for k, name in enumerate(names, start=1):
            a = cons.get(name)
            is_pad = a in ('L:_', 'K:_')
            if k <= n or n == 3:
                if is_pad:
                    ok = False; break
                if a is None:
                    items.append(implicit_first if k == 1 and implicit_first else ANY)
                else:
                    r = atom(a)
                    if r == 'SKIP':
                        ok = False; break
                    items.append(r)
            else:
                if a is not None and not is_pad:
                    ok = False; break
        if not ok:
            continue
        if n < 3:
            items = items[:n] + ['_']
        # trim trailing wildcards (nothing constrained further out)
        while items and items[-1] == ANY:
            items.pop()
        out.append(items)
    # drop duplicates
    seen, res = set(), []
    for it in out:
        key = tuple(it)
        if key not in seen:
            seen.add(key); res.append(it)
    return res

def emit(rules):
    per_self = []   # (self letter or None, pre, post, drop)
    for cons_list, drop, _ in rules:
        cons = {p: a for p, a in cons_list}
        self_letter = cons.get('self', 'L:')[2:] or None
        pres = side(cons, ['pre1', 'pre2', 'pre3'])
        posts = side(cons, ['post1', 'post2', 'post3'], implicit_first=C)
        # A drop right before a word-final consonant stops the existing final
        # rule from dropping that consonant's vowel (চামচ -> "chamcho"), so
        # unless the learned rule says otherwise the next consonant must not
        # end the word.
        if drop and 'post2' not in cons:
            posts = [p if len(p) > 1 else p + [ANY] for p in posts if not (len(p) == 2 and p[1] == '_')]
        for pre in pres:
            for post in posts:
                if post == ['_'] and 'post1' not in cons:
                    continue   # word-final: handled by the existing final rule
                if not pre and not post:
                    continue
                per_self.append((self_letter, pre, post, drop))
    return per_self

def main():
    src, dst = sys.argv[1], sys.argv[2]
    n = int(sys.argv[3]) if len(sys.argv) > 3 else 999
    rules = json.load(open('bn_rules_learned.json', encoding='utf-8'))[:n]
    lines_in = strip_generated(open(src, encoding='utf-8', newline='').read().split('\n'))
    per_self = emit(rules)
    out, grp, added = [], None, 0
    for line in lines_in:
        out.append(line)
        m = re.match(r'^\.group (\S)\s*$', line)
        if m:
            grp = m.group(1); continue
        m = re.match(r'^\s+(\S)\s+(\S+)V\s*(//.*)?$', line)
        if grp and m and m.group(1) == grp and (0x995 <= ord(grp) <= 0x9B9 or grp in 'ড়ঢ়য়'):
            base = m.group(2)
            for self_letter, pre, post, drop in per_self:
                if self_letter and self_letter != grp:
                    continue
                pre_s = ''.join(reversed(pre))
                ctx = (pre_s + ') ' if pre else '') + grp + (' (' + ''.join(post) if post else '')
                out.append('        %-24s %-8s %s' % (ctx, base if drop else base + 'V', TAG))
                added += 1
            for suf in VERB_SUFFIXES:
                out.append('        %-24s %-8s %s' % ('_L11) %s (%s_' % (grp, suf), base, TAG))
                added += 1
    text = '\n'.join(out)
    text = text.replace('\n.replace', '\n' + GROUPS + '\n.replace', 1)
    open(dst, 'w', encoding='utf-8', newline='').write(text)
    print('rule lines added', added)

if __name__ == '__main__':
    main()
