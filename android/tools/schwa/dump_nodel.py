"""Dump normal and no-deletion eSpeak IPA for WikiPron words.

usage: dump_nodel.py LANG WIKIPRON_TSV

Generates <lang>_norm.tsv and <lang>_nodel.tsv in the current directory.
To generate no-deletion output, temporarily compiles dictsource/<lang>_rules with
consonant inherent vowels mapped to '@' so eSpeak never reduces or deletes them.
Cleanly restores original rules and recompiles upon completion.
"""
import os, re, subprocess, sys
import score as S

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
BUILD = os.path.join(ROOT, 'build')
EXE = os.path.join(BUILD, 'src', 'espeak-ng.exe')
DICT = os.path.join(ROOT, 'dictsource')


def compile_dict(lang):
    res = subprocess.run([EXE, f'--compile={lang}', f'--path={BUILD}'],
                         cwd=DICT, capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"Failed to compile {lang}: {res.stderr}")


def dump_words(lang, words, out_path):
    print(f"Dumping {len(words)} words for {lang} -> {out_path}...")
    got = []
    for i in range(0, len(words), 2000):
        got += S.espeak(lang, words[i:i + 2000])
    assert len(got) == len(words), f"Mismatch: {len(got)} vs {len(words)}"
    with open(out_path, 'w', encoding='utf-8') as f:
        for w, g in zip(words, got):
            f.write(f'{w}\t{g}\n')
    print(f"Wrote {out_path}")


def main():
    if len(sys.argv) < 3:
        print("usage: python dump_nodel.py LANG WIKIPRON_TSV")
        sys.exit(1)
    lang, tsv = sys.argv[1], sys.argv[2]
    words = []
    for line in open(tsv, encoding='utf-8'):
        w = line.split('\t')[0]
        if ' ' not in w and w not in words:
            words.append(w)
    print(f"Loaded {len(words)} unique words from {tsv}")

    # 1. Normal dump
    norm_path = f"{lang}_norm.tsv"
    dump_words(lang, words, norm_path)

    # 2. No-deletion dump
    rules_path = os.path.join(DICT, f"{lang}_rules")
    with open(rules_path, 'r', encoding='utf-8') as f:
        orig = f.read()

    # In rule files, consonants typically have:
    #   '        ક          kV\n' or '\tક\tkV\n'
    # Replace the default 'V' with '@'
    nodel_text = re.sub(r'(\s+\S+\s+\S+)V(\s*(\r?\n))', r'\1@\2', orig)
    if nodel_text == orig:
        print(f"Warning: pattern did not change any lines in {rules_path}!")

    try:
        with open(rules_path, 'w', encoding='utf-8') as f:
            f.write(nodel_text)
        compile_dict(lang)
        nodel_path = f"{lang}_nodel.tsv"
        dump_words(lang, words, nodel_path)
    finally:
        # Always restore original rules and recompile
        with open(rules_path, 'w', encoding='utf-8') as f:
            f.write(orig)
        compile_dict(lang)
        print("Restored original rules and recompiled dictionary.")


if __name__ == '__main__':
    main()
