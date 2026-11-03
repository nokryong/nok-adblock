# NOK 광고 차단

광고·추적 도메인을 기기에서 걸러 주는 Android 앱입니다. 루팅 없이 로컬 VPN으로 DNS 요청을 처리하고, 사용자가 등록한 사이트에는 실험적인 TLS 연결 분할을 적용할 수 있습니다. AdGuard·유니콘과는 별개인 독립 프로젝트입니다.

**[APK 0.3.2 다운로드](https://github.com/nokryong/nok-adblock/releases/download/v0.3.2/nok-adblock-0.3.2.apk)** · [SHA-256](https://github.com/nokryong/nok-adblock/releases/download/v0.3.2/nok-adblock-0.3.2.apk.sha256) · [릴리즈 목록](https://github.com/nokryong/nok-adblock/releases) · [변경 기록](CHANGELOG.md) · [문제 제보](https://github.com/nokryong/nok-adblock/issues) · [MIT 라이선스](LICENSE)

현재 배포 버전은 **0.3.2**입니다. [v0.3.2 릴리즈](https://github.com/nokryong/nok-adblock/releases/tag/v0.3.2)에서 APK와 SHA-256 파일을 제공합니다. GitHub의 **Source code** 압축 파일은 설치용 APK가 아닙니다.

## 주요 기능

- AdAway 기본 차단 목록 6,540개 도메인과 온라인 목록 갱신.
- Cloudflare 또는 Google의 DNS over HTTPS(DoH), 연결 실패 시 기본 DNS를 사용하는 선택 기능.
- 허용 도메인과 하위 도메인 예외, 사용자가 지정하는 TLS 우회 도메인.
- 앱·알림·빠른 설정 타일에서 보호 켜기와 끄기.
- 하루 단위 DNS 요청·차단·오류 통계, 메모리에만 보관하는 최근 차단 기록 100건.
- GitHub 정식 릴리즈의 APK 확인·다운로드·검증과 사용자 확인 후 업데이트 설치.
- 한국어 화면과 어두운 테마. 계정이나 원격 로그 수집 없이 사용.

## 지원 환경과 동작 모드

| 모드 | 지원 환경 | 처리하는 트래픽 |
| --- | --- | --- |
| DNS 광고 차단 | Android 8.0 이상(API 26) | 가상 DNS 주소 `10.111.0.2/32`로 향하는 DNS 요청. 일반 인터넷 트래픽과 IPv6 연결은 원래 네트워크 사용 |
| 접속 우회(실험) | Android 10 이상(API 29) | IPv4·IPv6 트래픽을 기기 안의 SOCKS5 중계기로 전달. 등록한 사이트의 TLS ClientHello만 분할 |

대상 API는 35입니다. 빌드 시 네이티브 중계 엔진의 `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` 라이브러리를 패키징합니다. 네이티브 엔진은 접속 우회 모드에서만 사용합니다.

접속 우회는 일부 SNI 기반 차단을 피하기 위한 실험 기능입니다. 해외 서버나 해외 IP를 제공하지 않으며, HTTPS를 복호화하거나 인증서를 설치하지 않습니다. 인증서·호스트 이름 검증과 암호화는 브라우저와 각 앱에서 수행합니다.

## 설치와 사용

[v0.3.2 릴리즈](https://github.com/nokryong/nok-adblock/releases/tag/v0.3.2)의 APK로 설치할 수 있습니다.

1. [릴리즈 목록](https://github.com/nokryong/nok-adblock/releases)에서 APK와 SHA-256 파일을 내려받고 체크섬을 확인합니다.
2. Android에서 APK를 열어 설치합니다. 필요한 경우 APK를 연 앱의 **이 출처 허용**을 켭니다.
3. **NOK 광고 차단**을 열고 **보호 켜기**를 누른 뒤 Android의 VPN 연결 요청을 승인합니다.
4. **차단 목록 갱신**으로 목록을 갱신합니다. 실패하면 기존 목록을 유지합니다.
5. **빠른 설정에 추가**로 타일을 추가할 수 있습니다. 자동 추가를 지원하지 않는 기기는 빠른 설정 편집 화면에서 **NOK 광고 차단**을 직접 추가합니다.

사이트가 정상 작동하지 않으면 **차단 기록**에서 관련 도메인을 확인해 허용하거나, **설정 → 허용 도메인**에 추가합니다. 주소 전체 대신 `example.com`처럼 도메인만 입력합니다. 허용 규칙은 하위 도메인에도 바로 적용됩니다.

### 접속 우회 사용

1. 보호를 끈 상태에서 **설정 → 접속 우회 (실험)**을 켭니다.
2. **우회할 사이트**에 필요한 도메인을 직접 추가합니다. 새 설치의 기본 목록은 비어 있고, 기존 사용자 목록은 유지됩니다.
3. 보호를 다시 켭니다. 우회 목록 변경은 새 연결부터 반영됩니다.

등록하지 않은 사이트의 TLS는 다시 쓰지 않고, UDP 443도 그대로 중계합니다. QUIC 연결, SNI가 없거나 암호화된 연결에는 TLS 분할이 적용되지 않습니다. 통신망·사이트에 따라 효과가 다르며 IP 차단이나 사이트 자체의 지역 제한을 해결하지 못할 수 있습니다.

### 앱 업데이트

**설정 → 자동 업데이트**가 켜져 있으면 앱 실행 시와 백그라운드 작업에서 정식 릴리즈를 확인합니다. 새 버전은 미리 내려받아 검증하고, 알림과 첫 화면에서 설치를 안내합니다. **지금 업데이트 확인**으로 직접 확인할 수도 있습니다.

다운로드 크기와 GitHub가 제공하는 SHA-256, 패키지명, 증가한 버전 코드, 파일명과 일치하는 버전 이름, 최소 Android 버전, 설치된 앱과의 서명 호환성을 확인합니다. 설치 시 Android 확인 화면을 거치며, 처음에는 NOK의 **이 출처 허용**이 필요합니다. 업데이트로 중단된 보호는 설치 후 재시작을 시도합니다. 기존 앱을 삭제하면 설정이 사라질 수 있으므로 호환되는 서명의 APK로 덮어 설치합니다.

## 한계와 문제 해결

| 상황 | 확인할 내용 |
| --- | --- |
| 광고가 남아 있음 | DNS 차단은 도메인 단위입니다. 유튜브 영상 광고처럼 콘텐츠와 같은 도메인에서 오는 광고는 구분하지 못합니다. 캐시, 앱 자체 보안 DNS, 하드코딩된 DNS/IP, Android 프라이빗 DNS도 영향을 줍니다. |
| 사이트나 앱이 작동하지 않음 | 차단 기록을 확인해 필요한 도메인을 허용합니다. 접속 우회 중이라면 보호를 끄고 우회 모드를 해제한 뒤 DNS 전용 모드로 확인합니다. |
| Wi-Fi 로그인 또는 DNS 연결 실패 | **암호화 DNS 실패 시 기본 DNS 사용** 설정을 확인합니다. 이 기능이 켜져 있으면 DoH 실패 후 약 30초간 통신망의 DNS를 사용합니다. 이때 DNS 질문은 암호화되지 않습니다. |
| 화면을 끈 뒤 보호 중지 | 기기의 앱 배터리 설정과 백그라운드 실행 제한을 확인합니다. |
| 다른 VPN과 충돌 | Android VPN 슬롯을 사용하므로 다른 VPN 앱과 동시에 사용할 수 없습니다. |
| 인터넷 전체가 끊김 | Android의 **VPN 없는 연결 차단**은 DNS 전용 모드의 일반 연결도 막을 수 있습니다. 이 앱은 해당 옵션과 항상 켜짐 VPN을 지원하지 않습니다. |

재부팅 후 보호 자동 시작과 ICMP 중계도 지원하지 않습니다. 초기 실험 버전이므로 모든 앱·통신망의 호환성이나 배터리 사용 시간을 보장하지 않습니다.

문제 제보에는 Android 버전, 기기, 앱 버전, 동작 모드, 재현 순서와 오류 문구를 적어 주세요. 서명키·비밀번호·복구키는 첨부하지 마세요.

## 개인정보와 외부 연결

차단 판단은 기기에서 수행합니다. 차단되지 않은 DNS 질문은 선택한 Cloudflare 또는 Google에 HTTPS로 전송되므로 제공자는 질의 도메인과 접속 IP를 알 수 있습니다. 기본 DNS 대체 기능은 기본적으로 켜져 있고 **설정 → 암호화 DNS 실패 시 기본 DNS 사용**에서 끌 수 있습니다. DoH 제공자의 이름을 처음 확인할 때도 시스템 DNS를 사용합니다.

차단 목록 갱신은 AdAway의 GitHub 호스팅 목록에, 앱 업데이트 확인·다운로드는 GitHub에 연결합니다. 접속 우회 트래픽도 기기에서 직접 나가므로 상대 사이트에는 현재 인터넷 회선의 공인 IP가 보입니다.

로그인, 분석 SDK, 광고 SDK, 원격 로그 수집은 없습니다. 통계·설정·허용 및 우회 목록·갱신된 차단 필터는 앱 전용 저장소에 저장합니다. 최근 차단 도메인은 메모리에 최대 100건만 보관하고 **최근 차단 기록**을 끄면 비웁니다. 프로세스 종료 시 이 기록은 사라집니다. 앱 데이터 백업은 비활성화되어 있습니다.

## 소스 구조

| 경로 | 내용 |
| --- | --- |
| `app/src/main/AndroidManifest.xml` | 패키지 `dev.nok.adblock`, 버전 `0.3.2` / 코드 `6`, 권한과 서비스 |
| `app/src/main/java/dev/nok/adblock/` | 화면, VPN·DNS 처리, 로컬 중계, TLS 분할, 업데이트 검증 |
| `app/src/main/assets/` | 기본 차단 목록과 외부 라이선스 |
| `tools/` | SDK 준비, APK 빌드, JVM·Android 네이티브 검증 스크립트 |
| `tests/` | 핵심 로직과 프록시·업데이트 회귀 테스트 |
| `vendor/README.md` | 네이티브 의존성의 원본과 고정 체크섬 |
| `backups/` | 암호화된 서명 백업과 복구 안내. 복구키는 별도 보관 |

## 빌드

Java 소스를 Gradle 없이 Python 스크립트로 APK로 만듭니다. 기본 SDK 준비 스크립트는 **ARM64 Linux**용입니다. JDK 17, Python 3.12 이상과 인터넷 연결이 필요하며, Android SDK 및 각 도구의 사용 조건을 확인해야 합니다.

ARM64 Linux의 저장소 루트에서 실행합니다. 기본 SDK 경로 `/opt/nok-sdk`에 쓸 수 없다면 먼저 쓰기 가능한 `NOK_SDK` 경로를 지정합니다.

```sh
python3 tools/setup.py
python3 tools/test.py
python3 tools/build.py
```

Android의 `mc-linux` 환경에서 저장소가 `/workspace`에 있다면 `mc-linux -- /bin/bash -lc 'cd /workspace && python3 tools/test.py'` 같은 형태로 실행할 수 있습니다. JDK와 SDK 준비도 해당 Linux 환경 안에서 수행합니다.

| 환경 변수 | 기본값 / 역할 |
| --- | --- |
| `NOK_SDK` | `/opt/nok-sdk`: 플랫폼 35와 SDK 도구 |
| `NOK_BUILD_TOOLS` | `$NOK_SDK/build-tools/34.0.4`: D8·apksigner·컴파일 스텁과 패키징 도구 |
| `NOK_NATIVE_TOOLS` | `$NOK_SDK/native-35/build-tools`: 호스트용 `aapt2`·`zipalign` 우선 경로 |
| `NOK_SIGNING_DIR` | `/root/.local/share/nok-signing`: 비공개 서명 자료 |

`tools/setup.py`는 SDK 다운로드의 고정 SHA-256을 확인합니다. `tools/build.py`도 `hev-socks5-tunnel` 2.19.3 AAR의 고정 SHA-256을 확인한 뒤 빌드하고, APK 서명과 ZIP 정렬을 검증합니다. 출력은 `dist/nok-adblock-0.3.2.apk`와 `.apk.sha256`입니다. 다른 호스트에서는 호환되는 도구와 경로를 따로 준비해야 하며, 기본 ARM64 Linux 도구를 Windows나 x86 Linux에서 그대로 실행할 수 없습니다.

### 배포 서명과 릴리즈

**빌드 성공만으로 배포 가능한 APK가 되지는 않습니다.** 빌드 스크립트는 `development.jks`가 없으면 새 개발키를 만듭니다. 배포 자료가 없으면 개발키만으로 서명하며, 이 APK는 게시하면 안 됩니다.

기존 설치와 서명 연속성을 유지하려면 기존 `development.jks`와 회전된 `release.jks`, `release.pass`, `release.lineage`를 모두 동일한 비공개 서명 디렉터리에 준비해야 합니다. 기존 키를 새로 생성한 키로 대체하지 마세요. 공개된 암호화 백업만으로는 복구할 수 없으며, 원래 별도 보관한 복구키가 필요합니다. 자세한 안내는 [서명 백업 문서](backups/README.md)에 있습니다. 평문 키·비밀번호·복구키는 Git이나 릴리즈에 올리지 않습니다.

배포 전 확인할 사항:

1. Manifest의 버전과 코드, 테스트 결과, 배포 대상 커밋을 확인합니다. 현재 소스의 릴리즈 버전은 `v0.3.2`입니다.
2. 기존 서명 자료로 빌드했는지 확인하고 `apksigner verify --verbose --print-certs`로 서명 및 기존 인증서·회전 이력과의 연속성을 확인합니다. 패키지는 `dev.nok.adblock`, 버전은 `0.3.2`, 코드는 `6`이어야 합니다. ZIP 정렬과 네 가지 ABI 포함 여부도 확인합니다.
3. GitHub Release에 `nok-adblock-0.3.2.apk`와 `nok-adblock-0.3.2.apk.sha256`을 첨부합니다. 소스 압축 파일만으로는 앱 업데이트가 동작하지 않습니다.
4. 공개 다운로드가 정상인지, APK의 SHA-256이 체크섬 파일 및 GitHub asset의 `digest`와 일치하는지 확인합니다. 앱 업데이트는 draft·prerelease를 제외하며, `uploaded` 상태와 유효한 `sha256:` digest가 있는 정식 버전 APK만 선택합니다.

저장소에는 현재 자동 빌드·릴리즈 워크플로가 없습니다. 이후 릴리즈도 기존 서명 자료와 APK 검증 결과를 확인한 뒤 게시해야 합니다.

## 테스트

JDK 17의 `java`와 `javac`가 PATH에 있으면 Android SDK 없이 저장소 루트에서 실행할 수 있습니다.

```sh
python3 tools/test.py
```

DNS·IP 파싱과 잘못된 입력 20,000개, 도메인 규칙, TCP DNS, 종료 경쟁 1,000회, SOCKS5 TCP·UDP 중계, TLS 분할 및 비대상 연결 유지, half-close, 업데이트 검증과 기본 DNS 대체 처리를 검사합니다. 루프백 소켓 연결이 허용되어야 하며, 65초 유휴 연결 테스트 때문에 전체 실행에 65초 이상 걸립니다.

선택적인 실제 HTTPS 검증은 외부 네트워크가 필요합니다. 기본 테스트를 실행한 뒤 다음 명령으로 example.com의 TLS 1.2·1.3 분할과 www.google.com의 일반 TLS 1.3 연결을 확인합니다.

```sh
javac -encoding UTF-8 -cp build/tests -d build/tests tests/LiveProxyTests.java
java -cp build/tests dev.nok.adblock.LiveProxyTests
```

Android ARM64 네이티브 엔진의 모의 TUN 검증은 별도 환경에서 수행합니다. `mc-linux` 안에서 다음 fixture를 실행한 상태로 Android 호스트의 다른 터미널에서 `python tools/native_test.py`를 실행합니다. 이 테스트는 VPN이나 시스템 라우팅을 변경하지 않습니다.

```sh
mc-linux -- /bin/bash -lc 'cd /workspace && javac -encoding UTF-8 -cp build/tests -d build/tests tests/NativeProxyFixture.java'
mc-linux -- /bin/bash -lc 'cd /workspace && java -cp build/tests dev.nok.adblock.NativeProxyFixture /workspace/build/native-fixture.json /workspace/build/native-fixture.stop'
```

JVM·모의 TUN 테스트 통과는 실제 Android VPN 승인, APK 설치·업데이트 화면, 알림·빠른 설정 타일, 배터리, Wi-Fi·모바일 네트워크 전환의 검증을 대신하지 않습니다. 배포 전 실제 기기 검증이 필요합니다.

## 라이선스와 출처

NOK 코드: Copyright (c) 2026 nokryong, [MIT License](LICENSE).

- AdAway default blocklist: Kicelo, Dominik Schuermann 및 기여자, CC BY 3.0.
- hev-socks5-tunnel 2.19.3: hev 및 기여자, MIT. 포함된 lwIP는 BSD 3-Clause이며 기타 구성 요소의 고지도 함께 제공합니다.
- 원본·고정 체크섬: [vendor/README.md](vendor/README.md).
- 전체 고지: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), [앱에 포함된 라이선스](app/src/main/assets/licenses/hev-third-party.txt).
