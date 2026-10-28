#!/usr/bin/env python3
# Checks that the iOS Compose strings read back exactly as Android shows them.
#
# shared/compose-strings.gradle.kts turns app/src/main/res/values*/strings.xml
# into Compose resources. This compares every string in every locale, as
# Compose will decode it, with what aapt2 compiled into an APK.
#
#   aapt2 dump resources app/build/outputs/apk/phone/debug/phone-debug.apk > dump.txt
#   ./gradlew :shared:generateComposeStrings
#   python3 tools/check-ios-strings.py dump.txt shared/build/generated/composeStrings
#
# aapt2's dump indents the line after a newline by six spaces; that is undone
# before comparing. Exits non-zero on any difference.
import os
import re
import sys
import xml.etree.ElementTree as ET
dump=open(sys.argv[1],encoding='utf-8').read().split('\n')
gen=sys.argv[2]
aapt={}  # (cfg,name)->text
cur=None; i=0
entry=re.compile(r'^      \(([^)]*)\) (\(styled string\) )?"(.*)$')
while i<len(dump):
    l=dump[i]
    m=re.match(r'^    resource 0x[0-9a-f]+ string/(\S+)$',l)
    if m: cur=m[1]; i+=1; continue
    if cur and l.startswith('    resource') : cur=None
    m=entry.match(l) if cur else None
    if m:
        cfg=m[1]; styled=m[2]; buf=m[3]; j=i+1
        while j<len(dump) and not entry.match(dump[j]) and not dump[j].startswith('    resource') and not dump[j].startswith('  type'):
            buf+='\n'+dump[j]; j+=1
        if styled:
            k=buf.rfind('"'); buf=buf[:k]
        else:
            buf=buf[:buf.rfind('"')]
        aapt[(cfg,cur)]=buf.replace('\n      ','\n'); i=j; continue
    i+=1
def cmp_unescape(s):
    dbl=[m.start() for m in re.finditer(r'\\\\',s)]
    def rep(m):
        if m.start()-1 in dbl: return m.group(0)
        v=m.group(0)
        return '\n' if v=='\\n' else '\t' if v=='\\t' else chr(int(v[2:],16))
    return re.sub(r'\\u[a-fA-F\d]{4}|\\n|\\t',rep,s).replace('\\\\','\\')
cfgmap={'values':''}
bad=0;tot=0;missing=0
for d in sorted(os.listdir(gen)):
    cfg='' if d=='values' else d[7:]
    if cfg=='es-rXL': cfg='es-r419'
    root=ET.parse(os.path.join(gen,d,'strings.xml')).getroot()
    for el in root.findall('string'):
        n=el.get('name'); t=cmp_unescape(el.text or '')
        a=aapt.get((cfg,n))
        if a is None:
            missing+=1
            if missing<4: print('MISSING',cfg,n)
            continue
        tot+=1
        if a!=t:
            bad+=1
            if bad<15: print('DIFF',cfg,n,repr(a)[:150],'|',repr(t)[:150])
print('compared',tot,'diff',bad,'missing',missing)

sys.exit(1 if bad or missing else 0)
