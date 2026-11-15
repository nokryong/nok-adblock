# NOK Ad Blocker

[한국어](README.md) · English

An independent Android app that filters advertising and tracking domains on your device through a local VPN, without root. It also offers experimental TLS connection splitting for sites you explicitly add. This project is not affiliated with AdGuard or Unicorn.

[Download released APK 0.3.3](https://github.com/nokryong/nok-adblock/releases/tag/v0.3.3) · [Issues](https://github.com/nokryong/nok-adblock/issues) · [License](LICENSE)

The app follows the device language: Korean for Korean devices, English otherwise. This covers the interface, dialogs, notifications, Quick Settings tile, and error messages.

## Features

- DNS filtering with a bundled AdAway blocklist and online updates.
- Cloudflare or Google DNS over HTTPS, with an optional network-DNS fallback.
- Allowed domains (including subdomains) and a user-managed TLS bypass list.
- Protection controls in the app, notification, and Quick Settings.
- Daily DNS request, blocked request, and error counters.
- Up to 100 recent blocked domains kept only in memory; logging can be disabled.
- Verified GitHub release updates, with Android confirmation before installation.
- Dark theme; no accounts, analytics SDK, advertising SDK, or remote log collection.

## Requirements and limitations

DNS filtering supports Android 8.0/API 26 and newer. Experimental connection bypass requires Android 10/API 29 and newer. The target API is 35, with arm64-v8a, armeabi-v7a, x86, and x86_64 native libraries.

DNS-only mode routes DNS requests to the virtual DNS address through the VPN; ordinary Internet traffic keeps using the original network. Experimental bypass routes IPv4 and IPv6 TCP/UDP through an on-device relay and splits TLS ClientHello only for configured domains. It does not provide a foreign IP, decrypt HTTPS, install certificates, or bypass every form of blocking. QUIC and connections without usable SNI are not split.

Android permits only one VPN at a time. DNS filtering cannot distinguish ads served from the same domain as the content (such as YouTube video ads). App-specific secure DNS, cached ads, hardcoded DNS/IP addresses, and Android Private DNS can affect filtering. Always-on VPN, “Block connections without VPN,” automatic protection after reboot, and ICMP forwarding are not supported.

## Using the app

1. Download the APK and checksum from a release, verify the checksum, and install the APK. GitHub's source-code ZIP is not an APK.
2. Open NOK and select **Turn on protection**, then approve Android's VPN prompt.
3. Select **Update blocklist**. A failed update keeps the previous list.
4. Use **Add to Quick Settings**, or add the NOK tile manually through Android's Quick Settings editor.
5. If a site breaks, allow its domain from **Blocked activity** or **Settings → Allowed domains**. Enter a domain such as `example.com`, not a full URL. Subdomains are included.

For experimental bypass, stop protection, enable **Connection bypass (experimental)**, add the required domains, then restart protection. List changes affect new connections. New installations have an empty bypass list.

Automatic updates check GitHub's stable releases and download and verify an update before prompting you. Installation requires Android confirmation and may require permission to install from NOK. Protection may briefly stop during installation. Do not uninstall the old app to update it: install a compatibly signed APK over it to retain settings.

## Privacy

Block decisions happen on your device. Allowed DNS queries go to the selected Cloudflare or Google service over HTTPS. When fallback is enabled (the default), failed DoH connections temporarily use the network's unencrypted DNS. Disable it in Settings if unwanted. Initial DoH hostname resolution also uses system DNS.

Blocklist updates contact AdAway's GitHub-hosted list; app updates contact GitHub. Bypass traffic still exits through your own Internet connection. Statistics, settings, domain lists, and updated filters remain in app-private storage. Recent blocked domains are memory-only and disappear when logging is disabled or the process ends. App data backup is disabled.

## Building and testing

The project uses Java and Python scripts, not Gradle. See the [Korean build guide](README.md#빌드) and [pinned native dependency details](vendor/README.md). The default SDK setup targets ARM64 Linux; other hosts need compatible Android build tools. JDK 17 and Python 3.12+ are required.

```sh
python3 tools/setup.py
python3 tools/test.py
python3 tools/build.py
```

Use `NOK_SDK`, `NOK_BUILD_TOOLS`, and `NOK_NATIVE_TOOLS` to override SDK/tool paths. `NOK_SIGNING_DIR` specifies private signing material. **Do not publish a development-key APK.** Updates to existing installations require the existing signing keys and rotation lineage; never commit keys or passwords.

The JVM regression suite runs without the Android SDK and includes a 65-second idle-connection test. Run `python3 tools/test_localization.py` to check English/Korean resource coverage. Passing these tests does not replace real-device verification of installation, VPN consent, notifications, tiles, and network changes.

## Licenses

NOK: Copyright (c) 2026 nokryong, [MIT](LICENSE). AdAway default blocklist: Kicelo, Dominik Schuermann and contributors, CC BY 3.0. hev-socks5-tunnel 2.19.3: MIT, with additional bundled notices. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
