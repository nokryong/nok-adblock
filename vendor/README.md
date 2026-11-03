# Pinned native tunnel dependency

`hev-socks5-tunnel` 2.19.3, from the upstream release. The build script downloads the AAR when missing and verifies its SHA-256. Binary archives are excluded from Git; only these dependency references and license notices are tracked.

Upstream release:

https://github.com/heiher/hev-socks5-tunnel/releases/tag/2.19.3

| File | SHA-256 |
| --- | --- |
| `hev-socks5-tunnel-2.19.3.aar` | `ab6c581e6157680cfe5687ee7bbdcf95405c006c2db3701879ec44b9e5f83836` |
| `hev-socks5-tunnel-2.19.3.tar.xz` | `8aaa33b344cab03a5cfc3675b9aaae55c6ee6088d78678ad1fbb44855b5ba570` |

Original AAR: https://github.com/heiher/hev-socks5-tunnel/releases/download/2.19.3/hev-socks5-tunnel.aar

Corresponding source (download from upstream; not duplicated in this repository): https://github.com/heiher/hev-socks5-tunnel/releases/download/2.19.3/hev-socks5-tunnel-2.19.3.tar.xz

The AAR contains its official `hev.htproxy.TProxyService` JNI binding and all four Android ABIs. The upstream build targets Android API 29 and enables flexible page sizes. NOK only loads it when the optional bypass mode is enabled on Android 10 or newer. DNS-only mode remains available on Android 8 and 9.

The APK packages the native libraries compressed with `extractNativeLibs=true`. Android-relevant license files from the unchanged source archive are concatenated into `app/src/main/assets/licenses/hev-third-party.txt`. The source archive additionally includes platform-specific components not used by the Android build, such as Wintun.
