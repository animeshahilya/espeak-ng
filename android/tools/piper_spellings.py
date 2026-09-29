#!/usr/bin/env python3
"""Measure how today's eSpeak IPA differs from what natural (Piper) voices
were trained on, and check PiperPhonemes' respelling table against it.

Piper voices learned from the eSpeak NG of their day: piper 1.0-1.2 from
rhasspy's 2023 snapshot (rhasspy/espeak-ng 0f65aa30), 1.3+ from upstream.
Build this fork and both references with CMake, then:

  piper_spellings.py --fork BUILD --ref old=BUILD --ref new=BUILD \\
      --cldr cldr/common score sv:old ne:new
      words spelled exactly as trained, before -> after the rules, and what
      each rule contributes (keep a rule only if it raises the count);
      --offset 1500 scores held-out words
  ... residual pt:old      the commonest differences left after the rules
  ... fixture              rewrite test/resources/piper_spellings_cases.tsv,
                           which PiperPhonemesTest checks the Java table against

BUILD is a CMake build dir holding src/espeak-ng(.exe) and espeak-ng-data.
Words come from CLDR emoji annotations plus the language's dictsource list.
RULES must mirror PiperPhonemes.SPELLINGS (the fixture test catches drift).
"""
import argparse
import collections
import concurrent.futures
import difflib
import glob
import html
import json
import os
import re
import subprocess
import unicodedata

import regex  # pip install regex: \p{L}, variable-length lookbehind

END = r"(?![\p{L}\p{M}ː])"
BEFORE_CONSONANT = (r"(?=[ˈˌ]?[^\p{L}\p{M}ːˈˌ ]|[ˈˌ]?"
                    r"[bcdfɡhjklmnpqrstvwxzʃʒθðŋɲɟʎʁɾɹʋβɣçʝɕʑʈɖɳɭɻɽ])")
FLAP = [("ɽʱ", "r.h"), ("ɽ", "r."), ("ʱ", "ʰ")]
PT_NASALS = [("ẽn", "eɪŋ"), ("ẽm", "eɪm"), ("ĩn", "iŋ"), ("ũn", "ũŋ"),
             ("ɐ̃n" + BEFORE_CONSONANT, "ɐ̃ŋ")]


def pt_r(cluster_r):
    return [(r"(?<=[ptkbdɡfv])ɾ", cluster_r), (r"ɾ(?=[ptkbdɡfvszʃʒmnl])", "ɾə")]


RULES = {lang: FLAP for lang in "pa gu mr or as sd bn kn ta si kok bpy".split()}
RULES.update({
    "hi": FLAP + [("æ", "ɛ")],
    "ur": FLAP + [("ɾ", "r"), ("ɑ", "a"), ("ɳ", "n")],
    "ml": [("ɻ", "r.")] + FLAP,
    "ne": [("ɽʱ", "ɖʰ"), ("ɽ", "ɖ"), ("dzʱ", "ɟʰ"), ("dz", "ɟ"), ("tsʰ", "cʰ"), ("ts", "c"),
           ("ʱ", "ʰ")],
    "te": [("ŋ", "n"), ("ɲ", "n")],
    "sv:old": [("ɧ", "sx"), ("ɳ", "rn"), ("ɭ", "rl"), ("ɖ", "rd"), ("ʈ", "t")],
    "uk:old": [("w(?=[ptkfsʃxʧ])", "f"), ("[ʋw]", "β"), ("ʲ", "j")],
    "ca:old": [("ʃ", "ɕ"), ("ʒ", "ʑ"), ("ɱ", "n"), ("ə" + END, "ɐ"),
               (r"(?<!ˈ[^\s\p{M}aeiouɛɔəɐ]{0,3})u", "ʊ"), (r"i(?=[ˈˌ]?[aeoɔɛɐə])", "j")],
    "de:old": [("ʏ", "y"), ("ʊɐ", "??")],
    "pt-br:old": [("ʎ", "lj"), ("ɐ" + END, "æ"), ("ɾ" + END, "r"), ("ɪ" + END, "y"),
                  ("õn", "oŋ")] + PT_NASALS + pt_r("r"),
    "pt:old": [("ɾ" + END, "ɹ"), ("õn", "uŋ")] + PT_NASALS + pt_r("ɹ"),
})


def rules_for(voice, gen):
    if gen == "old" and f"{voice}:old" in RULES:
        return RULES[f"{voice}:old"]
    return RULES.get(voice, RULES.get(voice.split("-")[0], []))


def respell(ipa, rules):
    ipa = unicodedata.normalize("NFC", ipa)
    for pattern, replacement in rules:
        ipa = regex.sub(pattern, lambda _m, r=replacement: r, ipa)
    return ipa


def nfd(s):
    return unicodedata.normalize("NFD", s)


def edit(a, b):
    d = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        p, d[0] = d[0], i
        for j, y in enumerate(b, 1):
            p, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, p + (x != y))
    return d[-1]


def corpus(args, voice, n=1500):
    base = voice.split("-")[0]
    loc = {"cmn": "zh", "nb": "no"}.get(base, base)
    items = []
    for f in (f"{args.cldr}/annotations/{loc}.xml", f"{args.cldr}/annotationsDerived/{loc}.xml"):
        if os.path.exists(f):
            for m in re.findall(r"<annotation[^>]*>([^<]+)</annotation>",
                                open(f, encoding="utf-8").read()):
                items += [html.unescape(x).strip() for x in m.split("|")]
    lst = os.path.join(os.path.dirname(__file__), "..", "..", "dictsource", f"{base}_list")
    if os.path.exists(lst):
        for line in open(lst, encoding="utf-8", errors="replace"):
            w = line.split("//")[0].split()
            if w and w[0][0] not in "$._?" and not re.search(r"[0-9]", w[0]):
                items.append(w[0].strip("()"))
    items = [x for x in dict.fromkeys(items) if regex.fullmatch(r"[\p{L}\p{M} ]+", x)]
    return items[args.offset:args.offset + n]


def ipa(build, voice, text, tmp):
    # Through a file: Windows mangles non-ASCII command-line arguments.
    path = os.path.join(tmp, f"w{abs(hash((build, text)))}.txt")
    open(path, "w", encoding="utf-8").write(text)
    exe = glob.glob(os.path.join(build, "src", "espeak-ng*"))[0]
    out = subprocess.run([exe, "--path=" + build, "-v", voice, "-q", "--ipa", "-f", path],
                         capture_output=True).stdout
    os.remove(path)
    return re.sub(r"\([a-z-]+\)", "", out.decode("utf-8", "replace")).replace("\n", " ").strip()


def pairs(args, voice, gen):
    cache = os.path.join(args.tmp, f"cache_{voice}_{gen}_{args.offset}.json")
    if os.path.exists(cache):
        return json.load(open(cache, encoding="utf-8"))
    words = corpus(args, voice)
    with concurrent.futures.ThreadPoolExecutor(8) as ex:
        fork = list(ex.map(lambda w: ipa(args.fork, voice, w, args.tmp), words))
        ref = list(ex.map(lambda w: ipa(args.refs[gen], voice, w, args.tmp), words))
    data = list(zip(words, fork, ref))
    json.dump(data, open(cache, "w", encoding="utf-8"), ensure_ascii=False)
    return data


def metric(data, rules):
    out = [nfd(respell(f, rules)) for _, f, _ in data]
    refs = [nfd(u) for _, _, u in data]
    return sum(edit(o, u) for o, u in zip(out, refs)), sum(o == u for o, u in zip(out, refs))


def fixture(args):
    specs = args.specs or ["hi:new", "ne:new", "ur:new", "ml:new", "te:new", "bn:new"] + [
        k for k in RULES if k.endswith(":old")]
    rows = []
    for spec in specs:
        voice, gen = spec.split(":")
        rules = rules_for(voice, gen)
        changed = [(f, respell(f, rules)) for _, f, _ in pairs(args, voice, gen)]
        changed = [c for c in changed if nfd(c[0]) != nfd(c[1])][:40]
        version = "1.0.0" if gen == "old" else "1.3.0"
        rows += [f"{voice}\t{version}\t{f}\t{r}" for f, r in changed]
    path = os.path.join(os.path.dirname(__file__), "..", "test", "resources",
                        "piper_spellings_cases.tsv")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as out:
        out.write("# espeak voice\tpiper_version\teSpeak IPA\texpected model input"
                  " (tools/piper_spellings.py fixture)\n")
        out.write("\n".join(rows) + "\n")
    print(len(rows), "cases ->", os.path.normpath(path))


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--fork", required=True)
    ap.add_argument("--ref", action="append", default=[], help="old=BUILD or new=BUILD")
    ap.add_argument("--cldr", required=True)
    ap.add_argument("--tmp", default=os.environ.get("TMP", "/tmp"))
    ap.add_argument("--offset", type=int, default=0, help="skip this many words (held-out)")
    ap.add_argument("command", choices=["score", "residual", "fixture"])
    ap.add_argument("specs", nargs="*", help="voice:old|new, e.g. pt-br:old")
    args = ap.parse_args()
    args.refs = dict(r.split("=", 1) for r in args.ref)
    if args.command == "fixture":
        fixture(args)
        return
    for spec in args.specs:
        voice, gen = spec.split(":")
        rules, data = rules_for(voice, gen), pairs(args, voice, gen)
        (bd, bx), (ad, ax) = metric(data, []), metric(data, rules)
        print(f"== {spec} ({len(data)} words) exact {bx} -> {ax}, edit distance {bd} -> {ad}",
              flush=True)
        if args.command == "score":
            for i, rule in enumerate(rules):
                d, x = metric(data, rules[:i] + rules[i + 1:])
                print(f"   {rule[0]:24s} -> {rule[1]:6s} {ax - x:+5d} exact, {d - ad:+6d} distance")
            continue
        subs, ex = collections.Counter(), {}
        for w, f, u in data:
            a, b = nfd(respell(f, rules)), nfd(u)
            for op, i1, i2, j1, j2 in difflib.SequenceMatcher(None, a, b,
                                                              autojunk=False).get_opcodes():
                if op != "equal":
                    k = (a[max(0, i1 - 1):i2 + 1], b[max(0, j1 - 1):j2 + 1])
                    subs[k] += 1
                    ex.setdefault(k, w)
        for (x, y), n in subs.most_common(20):
            print(f"   {n:5d} '{x}' -> '{y}'  ({ex[(x, y)]})")


if __name__ == "__main__":
    main()
