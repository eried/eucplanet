#!/usr/bin/env python3
"""Regenerate British English (values-en-rGB) from the English strings.

British English is the English file with British spelling. It is generated,
never edited by hand: run this after adding or changing an English string,
from the repo root:

    python tools/i18n/en_gb.py

Only the words between tags change. Names, placeholders and markup are left
alone, and strings marked translatable="false" are left out, as in every
other language. A "meter" that measures (trip meter, odometer) keeps its
spelling in both; the unit of length is "metres".
"""
import io
import re
import sys

SRC = "app/src/main/res/values/strings.xml"
DST = "app/src/main/res/values-en-rGB/strings.xml"

# (pattern, replacement), applied to text only. Whole words, case kept.
RULES = [
    (r"\b([Aa])nalyz", r"\1nalys"),
    (r"\b([Cc])enter", r"\1entre"),
    (r"\b([Cc])olor", r"\1olour"),
    (r"\b([Cc])ustomiz", r"\1ustomis"),
    (r"\b([Ff])inaliz", r"\1inalis"),
    (r"\b([Ii])nitializ", r"\1nitialis"),
    (r"\b([Kk])ilometer", r"\1ilometre"),
    (r"\b([Cc])entimeter", r"\1entimetre"),
    (r"\b([Mm])eters\b", r"\1etres"),
    (r"\b([Mm])inimiz", r"\1inimis"),
    (r"\b([Oo])ptimiz", r"\1ptimis"),
    (r"\b([Pp])rioritiz", r"\1rioritis"),
    (r"\b([Rr])eorganiz", r"\1eorganis"),
    (r"\b([Oo])rganiz", r"\1rganis"),
    (r"\b([Ss])ynchroniz", r"\1ynchronis"),
    (r"\b([Tt])ire(s?)\b", r"\1yre\2"),
    (r"\b([Ll])icense\b", r"\1icence"),
    (r"\b([Ff])avorite", r"\1avourite"),
    (r"\b([Gg])ray\b", r"\1rey"),
]
RULES = [(re.compile(p), r) for p, r in RULES]


def british(text):
    for pat, rep in RULES:
        text = pat.sub(rep, text)
    return text


def convert(xml):
    # Drop what no language carries.
    xml = re.sub(r'[ \t]*<string\s+name="[^"]+"[^>]*translatable="false"[^>]*>.*?</string>\r?\n', "", xml, flags=re.S)
    # Text nodes only: whatever sits between a tag's end and the next tag's
    # start. Comments are skipped whole.
    out = []
    pos = 0
    for m in re.finditer(r"<!--.*?-->|<[^>]+>", xml, flags=re.S):
        out.append(british(xml[pos:m.start()]))
        out.append(m.group(0))
        pos = m.end()
    out.append(british(xml[pos:]))
    return "".join(out)


def main():
    src = io.open(SRC, encoding="utf-8", newline="").read()
    dst = convert(src)
    changed = sum(1 for a, b in zip(src.splitlines(), dst.splitlines()) if a != b)
    import os
    os.makedirs(os.path.dirname(DST), exist_ok=True)
    io.open(DST, "w", encoding="utf-8", newline="").write(dst)
    print("wrote %s" % DST)
    if "--show" in sys.argv:
        names = re.findall(r'<string\s+name="([^"]+)"[^>]*>([^<]*)</string>', src)
        for name, text in names:
            if british(text) != text:
                print("  %s: %s -> %s" % (name, text[:60], british(text)[:60]))


if __name__ == "__main__":
    main()
