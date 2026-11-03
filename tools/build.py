#!/usr/bin/env python3
"""Dependency-free APK builder. Run inside mc-linux with Python 3 and OpenJDK 17."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import zipfile
import xml.etree.ElementTree as ET

HEV_DIGEST = 'ab6c581e6157680cfe5687ee7bbdcf95405c006c2db3701879ec44b9e5f83836'

ROOT = Path(__file__).resolve().parent.parent
SDK = Path(os.environ.get('NOK_SDK', '/opt/nok-sdk'))
TOOLS = Path(os.environ.get('NOK_BUILD_TOOLS', str(SDK / 'build-tools/34.0.4')))
ANDROID = SDK / 'android-35/android.jar'
NATIVE = Path(os.environ.get('NOK_NATIVE_TOOLS', str(SDK / 'native-35/build-tools')))
BUILD = ROOT / 'build'

def native(name):
    return NATIVE / name if (NATIVE / name).exists() else TOOLS / name

def run(*args):
    subprocess.run([str(a) for a in args], cwd=ROOT, check=True)

def main():
    if not ANDROID.exists() or not (TOOLS / 'aapt2').exists():
        raise SystemExit('Android SDK is missing. See README.md for setup.')
    BUILD.mkdir(exist_ok=True)
    for name in ('classes', 'generated', 'dex'):
        p = BUILD / name
        if p.exists(): shutil.rmtree(p)
        p.mkdir()
    aar = ROOT / 'vendor/hev-socks5-tunnel-2.19.3.aar'
    if not aar.exists():
        aar.parent.mkdir(parents=True, exist_ok=True)
        part = aar.with_suffix('.aar.part')
        try:
            url = 'https://github.com/heiher/hev-socks5-tunnel/releases/download/2.19.3/hev-socks5-tunnel.aar'
            print('Downloading pinned native tunnel dependency', flush=True)
            with urllib.request.urlopen(url, timeout=120) as source, part.open('wb') as dest:
                shutil.copyfileobj(source, dest)
            if hashlib.sha256(part.read_bytes()).hexdigest() != HEV_DIGEST:
                raise SystemExit('Native tunnel dependency checksum mismatch')
            part.replace(aar)
        finally:
            if part.exists(): part.unlink()
    if hashlib.sha256(aar.read_bytes()).hexdigest() != HEV_DIGEST:
        raise SystemExit('Native tunnel dependency checksum mismatch')
    hev_jar = BUILD / 'hev-classes.jar'
    with zipfile.ZipFile(aar) as archive: hev_jar.write_bytes(archive.read('classes.jar'))
    print('1/6 Compiling Android resources', flush=True)
    run(native('aapt2'), 'compile', '--dir', ROOT / 'app/src/main/res', '-o', BUILD / 'resources.zip')
    run(native('aapt2'), 'link', '-o', BUILD / 'resources.apk', '-I', ANDROID,
        '--manifest', ROOT / 'app/src/main/AndroidManifest.xml', '--java', BUILD / 'generated',
        '-A', ROOT / 'app/src/main/assets', BUILD / 'resources.zip')
    print('2/6 Compiling Java', flush=True)
    sources = sorted((ROOT / 'app/src/main/java').rglob('*.java')) + sorted((BUILD / 'generated').rglob('*.java'))
    run('javac', '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:unchecked',
        '-bootclasspath', str(TOOLS / 'core-lambda-stubs.jar') + os.pathsep + str(ANDROID), '-classpath', hev_jar, '-d', BUILD / 'classes', *sources)
    run('jar', 'cf', BUILD / 'classes.jar', '-C', BUILD / 'classes', '.')
    print('3/6 Converting to DEX', flush=True)
    run('java', '-cp', TOOLS / 'lib/d8.jar', 'com.android.tools.r8.D8', '--release', '--min-api', '26',
        '--lib', ANDROID, '--output', BUILD / 'dex', BUILD / 'classes.jar', hev_jar)
    unsigned = BUILD / 'unsigned.apk'
    shutil.copyfile(BUILD / 'resources.apk', unsigned)
    with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_STORED) as archive:
        for dex in sorted((BUILD / 'dex').glob('*.dex')): archive.write(dex, dex.name)
        with zipfile.ZipFile(aar) as dependency:
            for entry in dependency.namelist():
                if entry.startswith('jni/') and entry.endswith('.so'):
                    archive.writestr('lib/' + entry[4:], dependency.read(entry), compress_type=zipfile.ZIP_DEFLATED)
    print('4/6 Aligning APK', flush=True)
    run(native('zipalign'), '-f', '4', unsigned, BUILD / 'aligned.apk')
    # Keys persist privately across rebuilds and never enter the repository or dist/.
    keydir = Path(os.environ.get('NOK_SIGNING_DIR', '/root/.local/share/nok-signing'))
    keydir.mkdir(parents=True, exist_ok=True)
    key = keydir / 'development.jks'
    if not key.exists():
        run('keytool', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android',
            '-alias', 'nok-dev', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '3650',
            '-dname', 'CN=NOK Local Development, O=NOK, C=KR', '-noprompt')
        key.chmod(0o600)
    print('5/6 Signing APK', flush=True)
    dist = ROOT / 'dist'; dist.mkdir(exist_ok=True)
    manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
    version = manifest.attrib['{http://schemas.android.com/apk/res/android}versionName']
    apk = dist / ('nok-adblock-' + version + '.apk')
    signers = ['--ks', key, '--ks-key-alias', 'nok-dev', '--ks-pass', 'pass:android', '--key-pass', 'pass:android']
    # A rotated release key keeps updates working on installs signed by the earlier key.
    release, secret, lineage = keydir / 'release.jks', keydir / 'release.pass', keydir / 'release.lineage'
    if release.exists() and secret.exists() and lineage.exists():
        print('Signing with the release key and rotation lineage', flush=True)
        signers += ['--next-signer', '--ks', release, '--ks-key-alias', 'nok-release', '--ks-pass', f'file:{secret}',
                    '--lineage', lineage]
    else:
        print('Signing with the local development key. Do not publish this APK.', flush=True)
    run('java', '-jar', TOOLS / 'lib/apksigner.jar', 'sign', *signers, '--v1-signing-enabled', 'true',
        '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true', '--out', apk, BUILD / 'aligned.apk')
    print('6/6 Verifying APK and signature', flush=True)
    run('java', '-jar', TOOLS / 'lib/apksigner.jar', 'verify', '--verbose', '--print-certs', apk)
    run(native('zipalign'), '-c', '4', apk)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    (dist / (apk.name + '.sha256')).write_text(f'{digest}  {apk.name}\n')
    print(f'APK: {apk}\nSize: {apk.stat().st_size:,} bytes\nSHA-256: {digest}', flush=True)

if __name__ == '__main__': main()
