# 암호화한 서명키 백업

이 폴더에는 AES-256-GCM으로 암호화한 서명키 백업만 보관합니다.
암호화 전 백업과 32바이트 복호화키는 `.local-backups/`에 있으며 Git에서 제외합니다.
암호화키를 알지 못하면 이 저장소의 백업 파일만으로 복원할 수 없습니다.

백업 내용: 이전 개발용 키, 정식 키, 정식 키 비밀번호 파일, 키 회전 기록,
파일별 SHA-256과 복원 안내. 실제 키나 비밀번호는 이 폴더에 평문으로 저장하지 않습니다.

JDK 11 이상에서 다음과 같이 복호화합니다. 출력 파일이 이미 있으면 덮어쓰지 않습니다.

```sh
java backups/BackupCrypto.java decrypt \
  backups/nok-signing-20261103-222115-KST.aes256gcm.json \
  .local-backups/nok-signing-20261103-222115-KST.aes256.key \
  .local-backups/restored-signing.tar.gz
```

복호화한 압축 파일의 `RESTORE.md`와 `MANIFEST.json`에 따라 복원하세요.
복호화키는 이 기기에만 보관하므로 기기와 로컬 백업을 모두 잃으면 공개 백업을 복원할 수 없습니다.
