"""Score espeak-ng IPA against WikiPron ground truth, per language.

usage: python score.py <espeak lang> <wikipron tsv> [max words] [--dump N]
Prints word accuracy, phone error rate, and the most frequent phone
confusions (ref -> espeak) so systematic errors surface first.
"""
import sys, os, subprocess, unicodedata, random, collections

ROOT = os.environ.get('ESPEAK_ROOT', os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..'))
EXE = os.path.join(ROOT, 'build', 'src', 'espeak-ng.exe')
DATA = os.path.join(ROOT, 'build')

# Pure notation differences between WikiPron and espeak, not errors.
EQUIV = {
    'ɡ': 'g', 'ɾ': 'r', 'ɹ': 'r', 'ʋ': 'v', 'ɐ': 'ə', 'ʌ': 'ə', 'ɛ': 'e', 'ɔ': 'o',
    'ɪ': 'i', 'ʊ': 'u', 'ɦ': 'h', 'ç': 'ʃ', 'ʲ': '', 'ɕ': 'ʃ', 'ʑ': 'ʒ',
    'ᵄ': '', 'ˀ': '', 'ʰ': 'ʰ', 'ɑ': 'a', 'æ': 'e', 'ɘ': 'ə', 'ɨ': 'ə', 'ɯ': 'u', 'ʉ': 'u', 'ɤ': 'o', 'ɜ': 'ə', 'χ': 'x', 'ʱ': 'ʰ', 'ä': 'a', 'ɟ': 'dʒ', 'c': 'tʃ', 'ᵊ': '', '˥': '', '˦': '', '˧': '', '˨': '', '˩': '',
}
# stress, syllable breaks, ties, length (too inconsistent across WikiPron sets)
# and fine diacritics (dental, unreleased, breathy, lowered, retracted, diaeresis)
DROP = set('ˈˌ.‿ ͜͡-‖|()ˑ+') | {chr(c) for c in (0x32F, 0x32A, 0x329, 0x325, 0x306, 0x30D, 0x30A, 0x31A,
                                                  0x308, 0x324, 0x31E, 0x31D, 0x320, 0x331, 0x318, 0x319)}


def phones(ipa):
    ipa = unicodedata.normalize('NFD', ipa)
    ipa = ipa.replace('ə̆', '')  # extra-short epenthetic schwa
    out = []
    for ch in ipa:
        if ch in DROP:
            continue
        if ch == 'ː':  # length: keep on consonants (gemination), drop on vowels
            if out and out[-1][:1] not in 'aeiouəã' and not out[-1].endswith('ː'):
                out[-1] += 'ː'
            continue
        ch = EQUIV.get(ch, ch)
        if not ch:
            continue
        if out and len(ch) == 1 and (unicodedata.combining(ch) or ch in 'ʰʷ'):
            out[-1] += ch
        else:
            out.append(ch)
    # "tʃ"/"dʒ" affricates as one unit, so ʧ vs tʃ is not a 2-phone error
    res = []
    for p in out:
        if res and (res[-1], p[:1]) in (('t', 'ʃ'), ('d', 'ʒ'), ('t', 's'), ('d', 'z')):
            res[-1] += p
        else:
            res.append(p)
    # offglides: "oj"/"ow" and "o ɪ"/"o ʊ" are the same diphthong
    res = [('i' if p == 'j' else 'u') if p in ('j', 'w') and k and res[k-1][:1] in 'aeiouə' and (k + 1 == len(res) or res[k+1][:1] not in 'aeiouə') else p
           for k, p in enumerate(res)]
    # geminates: "pp" and "pː" are the same thing written two ways
    gem = []
    for p in res:
        if gem and p == gem[-1] and p not in 'aeiouə':
            gem[-1] += 'ː'
        else:
            gem.append(p)
    return gem


def align(a, b):
    n, m = len(a), len(b)
    d = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1): d[i][0] = i
    for j in range(m + 1): d[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            d[i][j] = min(d[i-1][j] + 1, d[i][j-1] + 1, d[i-1][j-1] + (a[i-1] != b[j-1]))
    pairs, i, j = [], n, m
    while i or j:
        if i and j and d[i][j] == d[i-1][j-1] + (a[i-1] != b[j-1]):
            pairs.append((a[i-1], b[j-1])); i -= 1; j -= 1
        elif i and d[i][j] == d[i-1][j] + 1:
            pairs.append((a[i-1], '∅')); i -= 1
        else:
            pairs.append(('∅', b[j-1])); j -= 1
    return d[n][m], pairs[::-1]


def espeak(lang, words):
    tmp = os.path.join(os.path.dirname(os.path.abspath(__file__)), '_in.txt')
    with open(tmp, 'w', encoding='utf-8') as f:
        f.write('\n'.join(w + ' .' for w in words))  # " ." forces one clause per line
    env = dict(os.environ, ESPEAK_DATA_PATH=DATA)
    out = subprocess.run([EXE, '-q', '--ipa', '-v', lang, '-f', tmp], capture_output=True, env=env).stdout
    lines = [l.strip() for l in out.decode('utf-8', 'replace').splitlines() if l.strip()]
    return lines


def main():
    lang, tsv = sys.argv[1], sys.argv[2]
    limit = int(sys.argv[3]) if len(sys.argv) > 3 and sys.argv[3].isdigit() else 3000
    dump = int(sys.argv[sys.argv.index('--dump') + 1]) if '--dump' in sys.argv else 0
    ref = {}
    for line in open(tsv, encoding='utf-8'):
        w, _, p = line.rstrip('\n').partition('\t')
        if ' ' not in w and w not in ref:
            ref[w] = p
    words = sorted(ref)
    random.Random(1).shuffle(words)
    words = words[:limit]
    got = []
    for k in range(0, len(words), 500):  # batch so a stray line merge only costs one batch
        chunk = words[k:k+500]
        out = espeak(lang, chunk)
        got += out if len(out) == len(chunk) else [None] * len(chunk)
    conf, exact, errs, total, bad = collections.Counter(), 0, 0, 0, []
    n = 0
    for w, g in zip(words, got):
        if g is None:
            continue
        n += 1
        r, h = phones(ref[w]), phones(g)
        e, pairs = align(r, h)
        errs += e; total += len(r)
        if e == 0:
            exact += 1
        else:
            bad.append((w, ref[w], g))
            for a, b in pairs:
                if a != b:
                    conf[(a, b)] += 1
    print(f'{lang}: {n} words  exact {100*exact/max(n,1):.1f}%  PER {100*errs/max(total,1):.1f}%')
    for (a, b), c in conf.most_common(15):
        print(f'   {c:5d}  {a} -> {b}')
    for w, r, g in bad[:dump]:
        print(f'      {w}\tref {r}\tgot {g}')


if __name__ == '__main__':
    main()
