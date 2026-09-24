"""Per-word right/wrong for a language, for before/after diffs: perword.py lang tsv out"""
import sys, score as S
lang, tsv, out = sys.argv[1:4]
ref = {}
for line in open(tsv, encoding='utf-8'):
    w, _, p = line.rstrip('\n').partition('\t')
    if ' ' not in w and w not in ref: ref[w] = p
words = list(ref)
got = []
for i in range(0, len(words), 2000):
    got += S.espeak(lang, words[i:i+2000])
with open(out, 'w', encoding='utf-8') as f:
    for w, g in zip(words, got):
        d, _ = S.align(S.phones(ref[w]), S.phones(g))
        f.write(f"{w}\t{int(d == 0)}\t{ref[w]}\t{g}\n")
print(lang, sum(1 for w, g in zip(words, got) if S.align(S.phones(ref[w]), S.phones(g))[0] == 0), '/', len(words))
