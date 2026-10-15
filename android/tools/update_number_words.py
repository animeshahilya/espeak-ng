"""Regenerate android/data/numbers/ from Unicode CLDR's number spell-out rules.

Natural "text" voices read letters, so numbers must reach them as words.
Android's ICU has spell-out rules for only a few Indian languages (Hindi,
Gujarati, Tamil, Nepali on Android 16); CLDR has them for the rest since
2026-05-08 (CLDR-19450, on main after release 48.2). This copies CLDR's rule
text, which ICU's RuleBasedNumberFormat reads as is.

  python android/tools/update_number_words.py [cldr-commit-or-tag]

Needs the GitHub CLI (gh). Output: data/numbers/<lang>.txt (Unicode
license, see SOURCE.txt).
"""
import base64
import json
import os
import re
import subprocess
import sys

# Languages with natural text voices (SYSPIN/Rasa) that CLDR covers.
LANGUAGES = ["bn", "gu", "hi", "kn", "ml", "mr", "ne", "pa", "ta", "te"]


def fetch(tag, lang):
    out = subprocess.run(["gh", "api", "repos/unicode-org/cldr/contents/common/rbnf/%s.xml?ref=%s" % (lang, tag)],
                         capture_output=True, check=True).stdout
    return base64.b64decode(json.loads(out)["content"]).decode("utf-8")


def main():
    tag = sys.argv[1] if len(sys.argv) > 1 else "8ed394566f9364759a772179b76056b9f31c08ae"
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "numbers")
    os.makedirs(out, exist_ok=True)
    for lang in LANGUAGES:
        xml = fetch(tag, lang)
        m = re.search(r'<rulesetGrouping type="SpelloutRules">\s*<rbnfRules><!\[CDATA\[(.*?)\]\]>', xml, re.S)
        with open(os.path.join(out, lang + ".txt"), "w", encoding="utf-8", newline="\n") as f:
            f.write(m.group(1).strip() + "\n")
    with open(os.path.join(out, "SOURCE.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("Unicode CLDR %s common/rbnf (SpelloutRules), Unicode License v3\n" % tag)
    print(len(LANGUAGES), "languages ->", os.path.normpath(out))


if __name__ == "__main__":
    main()
