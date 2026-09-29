#!/usr/bin/env python3
"""Regenerate android/assets/symbols/ from NVDA's per-language symbol data.

NVDA reads punctuation and symbols with its locale's symbols.dic, then the
locale's CLDR names, then English (characterProcessing.py). This copies the
same files so NvdaSymbolProcessor can merge them the same way:

  git clone --depth 1 --filter=blob:none --sparse https://github.com/nvaccess/nvda.git
  git -C nvda sparse-checkout set --no-cone 'source/locale/*/symbols.dic'
  git clone --depth 1 --branch main-out https://github.com/nvaccess/nvda-cldr.git
  python android/tools/update_nvda_symbols.py nvda nvda-cldr

Output, with lower-cased NVDA locale names (pt_br, zh_cn):
  <locale>.dic       NVDA's symbols.dic, unchanged (GPL v2 or later, NV Access
                     and translators; the header of each file says so)
  <locale>.cldr.dic  the non-emoji lines of nvda-cldr's cldr.dic (Unicode
                     license); emoji names already live in assets/emoji/
  SOURCE.txt         the commits both came from
"""
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from update_emoji_names import is_emoji  # noqa: E402


def commit(repo):
    return subprocess.run(["git", "-C", repo, "log", "-1", "--format=%H %cs"],
                          capture_output=True, text=True).stdout.strip()


def main():
    nvda, cldr = sys.argv[1], sys.argv[2]
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "assets", "symbols")
    os.makedirs(out, exist_ok=True)
    for old in os.listdir(out):
        os.remove(os.path.join(out, old))
    locales = os.path.join(nvda, "source", "locale")
    count = 0
    for locale in sorted(os.listdir(locales)):
        src = os.path.join(locales, locale, "symbols.dic")
        if os.path.isfile(src):
            data = open(src, "rb").read()
            open(os.path.join(out, locale.lower() + ".dic"), "wb").write(data)
            count += 1
    cldr_count = 0
    for locale in sorted(os.listdir(os.path.join(cldr, "locale"))):
        src = os.path.join(cldr, "locale", locale, "cldr.dic")
        if not os.path.isfile(src):
            continue
        lines = ["symbols:"]
        for line in open(src, encoding="utf-8-sig"):
            line = line.rstrip("\r\n")
            ident = line.split("\t")[0]
            if "\t" in line and not line.startswith("#") and not is_emoji(ident):
                lines.append(line)
        if len(lines) > 1:
            with open(os.path.join(out, locale.lower() + ".cldr.dic"), "w", encoding="utf-8",
                      newline="\n") as f:
                f.write("# Non-emoji symbol names from Unicode CLDR via nvda-cldr"
                        " (Unicode License v3).\n")
                f.write("\n".join(lines) + "\n")
            cldr_count += 1
    with open(os.path.join(out, "SOURCE.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("symbols.dic: https://github.com/nvaccess/nvda " + commit(nvda) + "\n")
        f.write("cldr.dic: https://github.com/nvaccess/nvda-cldr (main-out) " + commit(cldr) + "\n")
    print(count, "symbols.dic,", cldr_count, "cldr.dic ->", os.path.normpath(out))


if __name__ == "__main__":
    main()
