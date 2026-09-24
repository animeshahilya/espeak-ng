"""Write learned inherent-vowel rules into an eSpeak rules file.

usage: emit.py bn|hi RULES_IN RULES_OUT [N_RULES]

Reads <lang>_rules_learned.json (from bn_learn.py / hi_learn.py) and adds
eSpeak rule lines to every consonant group, each ending "// generated".
Safe to re-run on its own output: earlier generated lines are removed
first. Line endings of the input file are kept.
"""
import json, re, sys

TAG = '// generated'


def letters(ranges, extra=()):
    out = []
    for a, b in ranges:
        out += [chr(c) for c in range(a, b)]
    return ' '.join(out + list(extra))


LANGS = {
    'bn': dict(
        V='L10', C='L11', ANY='L12', N=None,
        consonant=lambda ch: 0x995 <= ord(ch) <= 0x9B9 or ch in 'ড়ঢ়য়',
        keep='V',
        header="""// Inherent vowel dropped in the middle of a word (আপনি apni, আমরা amra,
// আকবর akbor). The rule lines marked "// generated" in each consonant group
// are not hand-written: android/tools/schwa learns them from WikiPron (rule
// induction on 80% of the words) and writes them here. On the 20% it never
// saw, words read right went from 59.0% to 61.9% (50 fixed, 12 broken).
// Regenerate them with those tools rather than editing by hand.
// The "_L11) X (suffix_" lines are a hand rule for verb forms (বলতে bolte,
// করছে korchhe), which the word list the rules were learned from lacks.
// L10 = written vowels (signs, and independent vowels except অ);
// L11 = consonant letters; L12 = any Bengali letter.
""",
        groups=lambda: """.L10 া ি ী ু ূ ৃ ৄ ে ৈ ো ৌ আ ই ঈ উ ঊ ঋ এ ঐ ও ঔ
.L11 %s
.L12 %s
""" % (letters([(0x995, 0x9BA)], ['ড়', 'ঢ়', 'য়']),
       letters([(0x985, 0x9BA), (0x9BE, 0x9CE)], ['ড়', 'ঢ়', 'য়', 'ং', 'ঁ', 'ঃ'])),
        # Verb forms: a one-consonant stem + this consonant + a verb suffix
        # drops the vowel (বলতে bolte, করছে korchhe, ধরলে dhorle).
        verb_suffixes=['তে', 'লে', 'ছে', 'লাম', 'তাম', 'ছি', 'ছিল', 'লো', 'বে', 'বো',
                       'লেন', 'তেন', 'ছেন', 'বেন', 'তিস', 'লি', 'ছিস'],
        protect_final=True,
        skip_initial=False,
    ),
    'hi': dict(
        V='L20', C='L21', ANY='L22', N='L23',
        consonant=lambda ch: 0x915 <= ord(ch) <= 0x939 or 0x958 <= ord(ch) <= 0x95F,
        keep='@4',   # a schwa the phoneme table never reduces (ph_hindi)
        header="""// Schwa kept or dropped: corrections learned from WikiPron on top of the
// phoneme table's own schwa deletion (कहना kəhnaː, बहरापन bəɦɾaːpən).
// The rule lines marked "// generated" in each consonant group are not
// hand-written: android/tools/schwa learns them (rule induction on 80% of
// the words) and writes them here. On the 20% it never saw, inherent vowels
// kept or dropped right went from 85.2% to 91.7% (280 fixed, 34 broken).
// Regenerate them with those tools rather than editing by hand.
// L20 = written vowels (signs, and independent vowels except अ);
// L21 = consonant letters; L22 = any Devanagari letter; L23 = ं ँ.
""",
        groups=lambda: """.L20 %s
.L21 %s
.L22 %s
.L23 ं ँ
""" % (letters([(0x93E, 0x94D), (0x905, 0x915)], ['ॠ', 'ॡ', 'ॢ', 'ॣ']).replace('अ ', ''),
       letters([(0x915, 0x93A), (0x958, 0x960)]),
       letters([(0x904, 0x93A), (0x93C, 0x94E), (0x958, 0x964)], ['ं', 'ँ', 'ः'])),
        verb_suffixes=[],
        protect_final=True,
        # hi_slots.py leaves out word-initial consonants (their vowel is never
        # dropped), so no learned rule may apply there: हमारा is not "hmaara".
        skip_initial=True,
    ),
}


def strip_generated(lines, header):
    """Remove what an earlier run added, so the tool can be re-run."""
    out, skip, after = [], False, False
    first = header.splitlines()[0]
    for line in lines:
        text = line.rstrip('\r')
        if text.startswith(first):
            skip = True
        if skip:
            if re.match(r'^\.L(12|22|23)\b', text) and not text.startswith('.L22'):
                skip, after = False, True
            continue
        if after:
            after = False
            if not text.strip():
                continue   # the blank line the header block ends with
        if text.rstrip().endswith(TAG):
            continue
        out.append(line)
    return out


def atom(a, cfg):
    kind, val = a[:2], a[2:]
    if kind == 'L:':
        return None if val == '_' else val
    return {'C': cfg['C'], 'V': cfg['V'], 'অ': 'অ', 'अ': 'अ', '্': '্', '्': '्',
            'N': cfg['N'] or 'SKIP'}.get(val, 'SKIP')


def side(cons, names, cfg, implicit_first=None):
    """Context strings for every possible distance to the word edge."""
    out = []
    for n in range(0, 4):       # letters before the edge (3 = 3 or more)
        ok, items = True, []
        for k, name in enumerate(names, start=1):
            a = cons.get(name)
            is_pad = a in ('L:_', 'K:_')
            if k <= n or n == 3:
                if is_pad:
                    ok = False; break
                if a is None:
                    items.append(implicit_first if k == 1 and implicit_first else cfg['ANY'])
                else:
                    r = atom(a, cfg)
                    if r == 'SKIP':
                        ok = False; break
                    items.append(r)
            elif a is not None and not is_pad:
                ok = False; break
        if not ok:
            continue
        if n < 3:
            items = items[:n] + ['_']
        while items and items[-1] == cfg['ANY']:
            items.pop()
        out.append(items)
    seen, res = set(), []
    for it in out:
        if tuple(it) not in seen:
            seen.add(tuple(it)); res.append(it)
    return res


def emit(rules, cfg):
    per_self = []
    for cons_list, drop, _ in rules:
        cons = {p: a for p, a in cons_list}
        self_letter = cons.get('self', 'L:')[2:] or None
        pres = side(cons, ['pre1', 'pre2', 'pre3'], cfg)
        posts = side(cons, ['post1', 'post2', 'post3'], cfg, implicit_first=cfg['C'])
        # A drop right before a word-final consonant stops the final-vowel
        # rule from dropping that consonant's vowel (bn চামচ -> "chamcho"),
        # so unless the learned rule says otherwise the next consonant must
        # not end the word.
        if cfg['protect_final'] and drop and 'post2' not in cons:
            posts = [p if len(p) > 1 else p + [cfg['ANY']] for p in posts
                     if not (len(p) == 2 and p[1] == '_')]
        for pre in pres:
            for post in posts:
                if post == ['_'] and 'post1' not in cons:
                    continue   # word-final: handled by the existing final rule
                if not pre and not post:
                    continue
                if cfg['skip_initial']:
                    if pre == ['_']:
                        continue
                    if not pre:
                        pre = [cfg['ANY']]   # at least one letter before
                per_self.append((self_letter, pre, post, drop))
    return per_self


def main():
    lang, src, dst = sys.argv[1], sys.argv[2], sys.argv[3]
    n = int(sys.argv[4]) if len(sys.argv) > 4 else 999
    cfg = LANGS[lang]
    raw = open(src, encoding='utf-8', newline='').read()
    nl = '\r\n' if '\r\n' in raw else '\n'
    rules = json.load(open(f'{lang}_rules_learned.json', encoding='utf-8'))[:n]
    lines = strip_generated(raw.split('\n'), cfg['header'])
    per_self = emit(rules, cfg)
    cr = '\r' if nl == '\r\n' else ''
    out, grp, added = [], None, 0
    for line in lines:
        out.append(line)
        text = line.rstrip('\r')
        m = re.match(r'^\.group (\S)\s*$', text)
        if m:
            grp = m.group(1); continue
        m = re.match(r'^\s+(\S)\s+(\S+)V\s*(//.*)?$', text)
        if grp and m and m.group(1) == grp and cfg['consonant'](grp):
            base = m.group(2)
            for self_letter, pre, post, drop in per_self:
                if self_letter and self_letter != grp:
                    continue
                ctx = (''.join(reversed(pre)) + ') ' if pre else '') + grp + (' (' + ''.join(post) if post else '')
                out.append('        %-24s %-8s %s%s' % (ctx, base if drop else base + cfg['keep'], TAG, cr))
                added += 1
            for suf in cfg['verb_suffixes']:
                out.append('        %-24s %-8s %s%s' % ('_%s) %s (%s_' % (cfg['C'], grp, suf), base, TAG, cr))
                added += 1
    block = (cfg['header'] + cfg['groups']()).replace('\n', nl)
    text = '\n'.join(out).replace(nl + '.replace', nl + block + nl + '.replace', 1)
    open(dst, 'w', encoding='utf-8', newline='').write(text)
    print('rule lines added', added)


if __name__ == '__main__':
    main()
