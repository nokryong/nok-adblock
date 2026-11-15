#!/usr/bin/env python3
"""Check complete, matching English/Korean Android string resources."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parent.parent
res = root / 'app/src/main/res'
def strings(folder):
    entries = ET.parse(res / folder / 'strings.xml').getroot().findall('string')
    result = {e.attrib['name']: e.text or '' for e in entries}
    assert len(result) == len(entries), 'Duplicate resource keys'
    assert all(result.values()), 'Empty translations'
    return result
en, ko = strings('values'), strings('values-ko')
assert en.keys() == ko.keys(), 'Missing translations'
for key in en:
    assert sorted(re.findall(r'%\d+\$[sd]', en[key])) == sorted(re.findall(r'%\d+\$[sd]', ko[key])), key
    assert not re.search('[가-힣]', en[key]), key
used = set()
for path in (root / 'app/src/main/java').rglob('*.java'):
    used.update(re.findall(r'R\.string\.(\w+)', path.read_text()))
assert used <= en.keys(), 'Undefined resources: ' + str(used - en.keys())
assert not re.search('[가-힣]', (root / 'app/src/main/java/dev/nok/adblock/MainActivity.java').read_text())
assert not re.search('[가-힣]', (root / 'app/src/main/AndroidManifest.xml').read_text())
print(f'PASS: {len(en)} translated strings; {len(used)} Java resource references')
