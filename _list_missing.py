"""List the 37 missing strings + their EN values."""
import os, re

RES = r"D:\GitHub\eucplanet\app\src\main\res"
with open(os.path.join(RES, 'values', 'strings.xml'), 'r', encoding='utf-8') as f:
    en = f.read()
with open(os.path.join(RES, 'values-de', 'strings.xml'), 'r', encoding='utf-8') as f:
    de = f.read()

en_keys = set(re.findall(r'<string name="([^"]+)"', en))
de_keys = set(re.findall(r'<string name="([^"]+)"', de))
missing = sorted(en_keys - de_keys)

print(f"// Missing in non-EN locales ({len(missing)} keys):")
for k in missing:
    m = re.search(r'<string name="' + re.escape(k) + r'">([^<]+)</string>', en)
    if m:
        print(f"  '{k}': '{m.group(1)}',")
