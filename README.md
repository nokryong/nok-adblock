# NOK 광고 차단

루팅 없이 광고·추적 도메인을 차단하는 Android 앱입니다. DNS 광고 차단과 선택한 사이트에 적용하는 실험적 HTTPS 접속 우회를 제공합니다. AdGuard·유니콘과는 별개 프로젝트입니다.

**[APK 0.3.2 다운로드](https://github.com/nokryong/nok-adblock/releases/download/v0.3.2/nok-adblock-0.3.2.apk)** · [SHA-256](https://github.com/nokryong/nok-adblock/releases/download/v0.3.2/nok-adblock-0.3.2.apk.sha256) · [변경 내역](CHANGELOG.md)

## 지원 환경

- DNS 광고 차단: Android 8.0 이상(API 26).
- 접속 우회: Android 10 이상(API 29).
- 대상 API: 35. ARM64, ARMv7, x86, x86_64 라이브러리 포함.

## 사용 방법

1. APK를 내려받아 설치하고 **NOK 광고 차단**을 엽니다.
2. **보호 켜기**를 누르고 Android의 VPN 연결 요청을 승인합니다.
3. **빠른 설정에 추가**로 알림창에 켜기·끄기 타일을 추가할 수 있습니다. 직접 추가가 지원되지 않으면 빠른 설정 편집 화면에서 **NOK 광고 차단**을 선택합니다.
4. 사이트가 제대로 작동하지 않으면 **차단 기록**을 확인하고 필요한 도메인을 허용합니다.

기존 버전 사용자는 앱을 삭제하지 않고 같은 서명의 새 APK를 덮어 설치할 수 있습니다. 업데이트 후 보호를 껐다 켜 주세요.

### 접속 우회

1. 보호를 끄고 **설정 → 접속 우회 (실험)**를 켭니다.
2. **우회할 사이트**에 필요한 도메인을 직접 추가합니다. 기본 목록은 비어 있으며 하위 도메인도 포함됩니다.
3. 보호를 다시 켭니다. 우회 목록 변경은 새 연결부터 적용됩니다.

우회 목록에 있는 사이트의 TLS 연결 시작 메시지를 분할해 일부 SNI 기반 차단의 영향을 줄입니다. 해외 서버를 거치거나 해외 IP로 바뀌지 않습니다. 통신망과 사이트에 따라 효과가 다르며, IP 차단이나 사이트 자체의 지역 제한은 해결하지 못합니다.

0.3.1부터 등록하지 않은 사이트의 TLS 연결은 분할하지 않고, UDP 443도 중계합니다. QUIC 전용 연결, SNI가 없거나 암호화된 연결에는 TLS 분할 우회가 적용되지 않습니다. 호환성 문제가 있으면 보호를 끄고 접속 우회를 해제한 뒤 DNS 광고 차단만 사용할 수 있습니다.

### 자동 업데이트

**설정 → 자동 업데이트**가 켜져 있으면 앱을 열 때와 백그라운드에서 이 저장소의 공개 릴리즈를 확인합니다. 새 버전이 있으면 미리 내려받아 검증하고 알림과 첫 화면에 표시합니다. **업데이트 설치**를 누르면 Android 설치 확인 화면이 열립니다. Android 정책상 일반 앱의 설치에는 이 확인이 필요합니다. 처음에는 NOK의 **이 출처 허용**도 켜야 합니다.

검증 항목은 GitHub 자산의 SHA-256과 크기, 패키지명, 더 높은 버전 코드, 릴리즈와 같은 버전 이름, 지원 Android 버전, 설치된 앱과 호환되는 서명입니다. GitHub의 지정된 HTTPS 주소에서만 내려받습니다. 설치 후에는 켜져 있던 보호를 다시 시작합니다.

## 주요 기능

- 로컬 VPN, 앱·알림·빠른 설정 타일에서 켜기·끄기.
- 기본 AdAway 차단 목록 6,540개 도메인과 온라인 목록 갱신.
- Cloudflare 또는 Google의 DNS over HTTPS. 연결할 수 없을 때 통신망 DNS를 잠시 사용하는 선택 기능.
- 공개 릴리즈 자동 확인·검증과 앱 내 업데이트.
- 허용 도메인, 편집 가능한 접속 우회 도메인 목록.
- 하루 단위 DNS 요청·차단·오류 통계.
- 메모리에만 보관하는 최근 차단 기록 100건과 기록 끄기.
- 한국어 화면과 어두운 테마.

## 동작과 제약

DNS 전용 모드는 가상 DNS 서버 `10.111.0.2/32` 경로만 받아 광고·추적 도메인을 확인합니다. 허용된 DNS 요청은 선택한 암호화 DNS 제공자에게 전송합니다. 일반 인터넷 트래픽과 IPv6 연결은 기존 네트워크를 사용합니다.

접속 우회 모드는 IPv4·IPv6 트래픽을 로컬 VPN으로 받고, `hev-socks5-tunnel`과 인증된 기기 내부 SOCKS5 중계기를 통해 원래 목적지에 직접 연결합니다. 선택한 사이트의 TLS ClientHello만 유효한 TLS 레코드 두 개로 분할합니다. HTTPS를 복호화하거나 인증서를 설치하지 않으며, 인증서 검사는 브라우저와 앱에서 수행합니다.

- 유튜브 영상 광고와 콘텐츠와 같은 도메인에서 오는 광고까지 제거하지 않습니다.
- 자체 보안 DNS, 하드코딩한 DNS/IP, Android 프라이빗 DNS 등에 의해 DNS 필터가 우회될 수 있습니다.
- 다른 VPN 앱과 동시에 사용할 수 없습니다.
- Android의 **VPN 없는 연결 차단** 옵션은 사용하지 마세요. DNS 전용 모드에서 일반 연결도 막힐 수 있습니다.
- 재부팅 자동 시작과 상시 VPN, ICMP 중계는 지원하지 않습니다.
- 연결 수와 대기 시간에 제한이 있습니다. 모든 앱·통신망에서의 호환성을 보장하지 않는 초기 개발 버전입니다.

## 개인정보

광고 차단 판정은 기기에서 수행합니다. 허용된 DNS 질문은 선택한 Cloudflare 또는 Google에 HTTPS로 전송되며, 제공자는 질의 도메인과 연결 IP를 볼 수 있습니다. 암호화 DNS에 연결할 수 없으면 Wi-Fi 로그인 등을 위해 약 30초 동안 통신망의 DNS를 암호화 없이 사용하며, **설정 → 암호화 DNS 실패 시 기본 DNS 사용**에서 끌 수 있습니다. 끄면 암호화 DNS 장애 시 이름 확인이 실패합니다. 차단 목록 갱신과 앱 업데이트 확인은 GitHub에 연결합니다. 접속 우회는 기기 내부에서 처리하므로 대상 사이트에는 원래 인터넷 연결의 공인 IP가 보입니다.

별도 계정, 분석 SDK, 광고 SDK, 원격 로그 수집은 없습니다. 통계·설정·허용 및 우회 목록·갱신한 필터는 앱 전용 저장소에 보관합니다. 최근 차단 도메인은 메모리에만 보관하며, 연결 본문이나 SNI 도메인을 접속 로그로 저장하지 않습니다. 앱 데이터 백업은 비활성화되어 있습니다.

## 소스와 빌드

앱은 Java로 작성했으며 Gradle 없이 Python 스크립트로 APK를 생성합니다. 현재 제공하는 SDK 준비 스크립트는 ARM64 Linux 환경을 대상으로 합니다. JDK 17, Python 3.12 이상, ZIP 도구가 필요합니다. `tools/setup.py`는 버전과 SHA-256이 고정된 도구를 다운로드하며, `tools/build.py`는 네이티브 AAR이 없으면 고정 버전의 공식 배포 파일을 내려받아 체크섬을 검증합니다. Android SDK 및 각 도구의 배포 조건을 확인하세요.

Android의 ARM64 Linux 환경에서 실행하는 예:

```sh
mc-linux -- /bin/bash -lc 'pacman -S --needed --noconfirm jdk17-openjdk zip unzip'
mc-linux -- /bin/bash -lc 'python /workspace/tools/setup.py'
mc-linux -- /bin/bash -lc 'python /workspace/tools/test.py'
mc-linux -- /bin/bash -lc 'python /workspace/tools/build.py'
```

일반 ARM64 Linux에서는 프로젝트 폴더에서 다음 스크립트를 직접 실행할 수 있습니다. 기본 SDK 경로 `/opt/nok-sdk`에 쓸 수 있어야 하며, 필요하면 `NOK_SDK`로 변경하세요.

```sh
python3 tools/setup.py
python3 tools/test.py
python3 tools/build.py
```

`NOK_SDK`, `NOK_BUILD_TOOLS`, `NOK_NATIVE_TOOLS`, `NOK_SIGNING_DIR`로 SDK·패키징 도구·서명키 경로를 지정할 수 있습니다. 기본 도구 외의 호스트 환경에서 빌드하려면 해당 환경에서 실행 가능한 `aapt2`, `zipalign` 등을 별도로 제공해야 합니다.

서명키는 빌드 환경에서 별도로 생성하며 저장소에 포함하지 않습니다. 같은 설치를 계속 업데이트하려면 같은 키를 보관하세요. 자신의 환경에서 새로 만든 키로 서명한 APK는 이 저장소의 배포 APK 위에 업데이트 설치할 수 없습니다. 서명 폴더에 `release.jks`, `release.pass`, `release.lineage`가 없으면 빌드 스크립트는 개발용 키로 서명하며, 이 APK는 배포하지 마세요.

결과 APK와 SHA-256 파일은 `dist/`에 생성됩니다. 빌드 스크립트는 APK 서명과 ZIP 정렬도 검사합니다.

## 검증

0.3.2 기준 ARM64 JVM 시험. 네이티브 라이브러리 시험은 0.3.1 결과입니다.

- 핵심·종료·TLS·프록시 검사 88개, 업데이트·DNS 대체 경로 검사 44개, 비정상 DNS/IP 입력 20,000건, 비정상 TLS 입력 10,000건 통과.
- 동시 연결·종료 경합 1,000회 시험.
- UDP 443 왕복, 등록하지 않은 구글 TLS 원본 보존, 양방향 TCP half-close 검증.
- 65초 유휴 후 양방향 송수신 유지 검증. 이 시험 때문에 기본 테스트는 65초 이상 걸립니다.
- example.com의 TLS 1.2·1.3 분할 연결, www.google.com의 TLS 1.3 일반 연결에서 인증서·호스트 이름 검사를 거쳐 HEAD HTTP 200 응답 확인.
- 소켓 쌍으로 만든 가상 TUN에서 네이티브 엔진 → 인증된 SOCKS5 → Java DNS → TUN 왕복과 종료·재시작 3회 확인.
- APK v2·v3 서명, ZIP 정렬, 네 가지 네이티브 ABI 포함 여부 확인.

이 시험은 실제 Android VPN 권한, 앱 내 설치 화면, 모든 앱의 동작, 절전, Wi-Fi·모바일 데이터 전환 또는 통신망 차단 우회 성공을 보장하지 않습니다.

추가 온라인 HTTPS 시험은 공개 페이지의 응답 헤더만 받습니다.

```sh
mc-linux -- /bin/bash -lc 'javac -encoding UTF-8 -cp /workspace/build/tests -d /workspace/build/tests /workspace/tests/LiveProxyTests.java'
mc-linux -- /bin/bash -lc 'java -cp /workspace/build/tests dev.nok.adblock.LiveProxyTests'
```

Android 네이티브 가상 TUN 시험은 먼저 아래 Java fixture를 실행한 상태에서 Android 호스트의 다른 셸에서 `python tools/native_test.py`를 실행합니다. 이 시험은 기기 VPN이나 시스템 라우팅을 변경하지 않습니다.

```sh
mc-linux -- /bin/bash -lc 'javac -encoding UTF-8 -cp /workspace/build/tests -d /workspace/build/tests /workspace/tests/NativeProxyFixture.java'
mc-linux -- /bin/bash -lc 'java -cp /workspace/build/tests dev.nok.adblock.NativeProxyFixture /workspace/build/native-fixture.json /workspace/build/native-fixture.stop'
```

## 라이선스

NOK의 코드는 [MIT License](LICENSE)로 제공합니다. 외부 구성 요소에는 각자의 라이선스가 적용됩니다.

## 외부 구성 요소

- AdAway default blocklist: CC BY 3.0.
- hev-socks5-tunnel 2.19.3: MIT. 포함된 lwIP는 BSD 3-Clause이며 관련 의존성의 고지도 함께 제공합니다.
- 네이티브 AAR과 대응 소스의 다운로드 링크 및 고정 체크섬: [vendor/README.md](vendor/README.md).
- 전체 출처와 라이선스 고지: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), [앱에 포함된 라이선스](app/src/main/assets/licenses/hev-third-party.txt).
