#!/usr/bin/env python3
"""Fetch pinned SDK tools for the enabled ARM64 mc-linux environment."""
import hashlib
import os
from pathlib import Path
import shutil
import tarfile
import urllib.request
import zipfile

root = Path(__file__).resolve().parent.parent
cache = root / '.build-tools'; cache.mkdir(exist_ok=True)
sdk = Path(os.environ.get('NOK_SDK', '/opt/nok-sdk')); sdk.mkdir(parents=True, exist_ok=True)
downloads = [
    ('platform-35.zip', 'https://dl.google.com/android/repository/platform-35_r02.zip',
     '0988cacad01b38a18a47bac14a0695f246bc76c1b06c0eeb8eb0dc825ab0c8e0'),
    ('build-tools-arm64.tar.xz', 'https://github.com/AndroidIDEOfficial/androidide-tools/releases/download/v34.0.4/build-tools-34.0.4-aarch64.tar.xz',
     '4bbbdeee608ff8d3a2c1534d0a5270305a02b1f200403a536c75ac84c41e52fa'),
    ('android-sdk-tools-static-aarch64.zip', 'https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip',
     'db1cea2c4454d5f9c5a802646b2d1cf560b4ee7badbe23e51ab8e1881bb50fc2'),
]
for name, url, digest in downloads:
    p = cache / name
    if not p.exists() or hashlib.sha256(p.read_bytes()).hexdigest() != digest:
        print('Downloading ' + name, flush=True)
        part = p.with_suffix(p.suffix + '.part')
        with urllib.request.urlopen(url, timeout=120) as src, part.open('wb') as dest:
            shutil.copyfileobj(src, dest)
        if hashlib.sha256(part.read_bytes()).hexdigest() != digest:
            raise SystemExit('Checksum mismatch: ' + name)
        part.replace(p)
    print('Verified ' + name, flush=True)
    if name.endswith('.tar.xz'):
        with tarfile.open(p) as archive: archive.extractall(sdk, filter='data')
    else:
        target = sdk / 'native-35' if name.startswith('android-sdk-tools') else sdk
        target.mkdir(exist_ok=True)
        with zipfile.ZipFile(p) as archive: archive.extractall(target)
for folder in (sdk / 'build-tools/34.0.4', sdk / 'native-35/build-tools'):
    for name in ('aapt', 'aapt2', 'zipalign', 'dexdump', 'aidl', 'split-select'):
        tool = folder / name
        if tool.is_file(): tool.chmod(0o755)
print('SDK ready at ' + str(sdk))
