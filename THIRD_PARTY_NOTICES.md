# Third-party notices

The app bundles the **AdAway default blocklist**, unchanged as `app/src/main/assets/hosts.txt`.

- Authors: Kicelo, Dominik Schuermann, and contributors recorded in the upstream history.
- Project and source: https://github.com/AdAway/adaway.github.io
- Original file: https://raw.githubusercontent.com/AdAway/adaway.github.io/master/hosts.txt
- License: Creative Commons Attribution 3.0 Unported (CC BY 3.0).
- License text: https://creativecommons.org/licenses/by/3.0/legalcode
- Acknowledgment is also available in the app's information dialog and the bundled file header.
- The runtime interprets listed domains as matching the domain and its subdomains. A user's local allow list overrides these rules.

There is no AdAway, AdGuard, or Unicorn application code in this project.

The APK also includes **hev-socks5-tunnel 2.19.3**, its Java JNI binding and Android native libraries for arm64-v8a, armeabi-v7a, x86, and x86_64.

- Author: hev and upstream contributors.
- Project and release: https://github.com/heiher/hev-socks5-tunnel/releases/tag/2.19.3
- License: MIT; the Android build also contains lwIP (BSD 3-Clause), libyaml, hev-task-system, and hev-socks5-core (MIT).
- Full upstream license notices are packaged as `assets/licenses/hev-third-party.txt` and accessible in the app's information dialog.
- The build downloads the unchanged AAR from its official release and verifies its pinned SHA-256. `vendor/README.md` links to the AAR and corresponding upstream source archive, with checksums; neither binary archive is tracked in this repository.
- The library handles local IP-to-SOCKS forwarding. The loopback proxy, DNS policy integration, and TLS record fragmentation in NOK are separate code in this project.

Build-time dependencies are downloaded separately and are not packaged in the application:

- Android SDK platform 35, from Google: https://developer.android.com/studio/terms
- D8, apksigner, and Java compilation stubs from AndroidIDE's SDK build tools: https://github.com/AndroidIDEOfficial/androidide-tools
- ARM64 aapt2 and zipalign builds: https://github.com/lzhiyong/android-sdk-tools/releases/tag/35.0.2
- OpenJDK 17: https://openjdk.org/legal/gplv2+ce.html

The tool archives retain their upstream notices in the local SDK directory. Google SDK platform terms are included in its `NOTICE.txt`.
