# 부스 스탬프 QR

[스탬프투어 기획](../../docs/wiki/product/stamp.md)에 따라 부스마다 고유한 QR을 만든다. QR 링크에는
128비트 난수 토큰이 들어가고, 서버는 같은 브라우저가 한 부스에서 하루 한 번만 적립하도록 막는다.

| 파일 | 내용 | 커밋 |
|---|---|---|
| `booths.example.json` | 부스 목록 예시(멋사 부스 + 자리표시 34곳). `booths.json`으로 복사해 쓴다 | 예 |
| `Publish-BoothStamps.ps1` | 게시본 export → 토큰 생성 → import·publish를 한 번에 한다 | 예 |
| [`../../tools/stamp/generate-booth-stamps.mjs`](../../tools/stamp/generate-booth-stamps.mjs) | 토큰·링크 생성과 manifest 병합 | 예 |
| `out/stamp-qr-links.csv` | 부스별 QR 링크(원문 토큰) | **아니오 — 비밀** |
| `out/manifest-with-stamps.json` | 게시본 + 부스·토큰 hash | 아니오(git이 무시) |

`out/`은 `.gitignore`의 `out/` 규칙으로 커밋되지 않는다. QR 링크를 가진 사람은 그 부스에 가지 않고도
적립할 수 있으므로 링크 파일은 부스 운영자에게 QR 이미지로만 전달한다.

## 규칙

- 부스 토큰: 부스당 16바이트 난수(링크에서 22자). catalog에는 SHA-256만 들어간다.
- 링크: `https://festival.likelionerica.com/stamps?b=<토큰>`. 프런트는 이 주소로 들어오면
  `POST /api/v2/stamp-collections`에 `{"token": "<b 값>"}`을 보낸다.
- 한 브라우저(익명 참여자 쿠키)는 축제 하루에 **부스당 1회, 합계 4개**까지 적립한다. 같은 부스를 다시
  찍으면 `409 STAMP_ALREADY_COLLECTED`다. 날짜는 축제 시간대(Asia/Seoul) 기준이다.
- `-Daily`를 쓰면 부스마다 축제일별 토큰을 만든다. 찍어서 공유된 QR은 그날만 유효하지만, 부스는 날마다
  다른 QR을 써야 한다. 쓰지 않으면 축제 기간 전체에 QR 하나를 쓴다.

## 원격 적용 순서

1. **서버 배포**: migration V27이 포함된 버전을 배포한다(Flyway가 시작 시 적용). `/readyz` 200을 확인한다.
2. **부스 목록**: `booths.example.json`을 `booths.json`으로 복사해 id·이름을 채운다. id는
   `^[a-z0-9][a-z0-9-]{0,63}$`, 순서가 표시 순서다. 부스 이름을 아직 모르면 자리표시 이름으로 먼저
   게시해도 된다(아래 "이름만 바꾸기").
3. **jar 빌드**: `.\mvnw.cmd --batch-mode --no-transfer-progress -DskipTests package`
4. **확인 실행**: 게시하지 않고 파일만 만든다.

   ```powershell
   .\ops\stamp\Publish-BoothStamps.ps1 -DatabaseUrl '<jdbc url>' -Username '<catalog publish 역할>' -FestivalId '<축제 UUID>' -BaselineRevision '<현재 published revision id>' -Actor '<이름>' -DryRun
   ```

   현재 published revision은 DBeaver에서
   `SELECT id FROM festival_revisions WHERE state = 'published';`로 확인한다.
5. **게시**: `-DryRun` 없이 다시 실행하고 `yes`로 확인한다. 이미 토큰이 있는 게시본에 다시 만들면
   생성기가 멈춘다. 새 토큰으로 바꾸려면 `-Rotate`를 붙인다(인쇄한 QR이 모두 무효가 된다).
6. **재시작**: 백엔드를 재시작한다. 재시작 전에는 이전 revision의 토큰이 쓰인다.
7. **QR 제작**: `out/stamp-qr-links.csv`의 `url`로 QR 이미지를 만든다. 한 링크로
   `/api/v2/stamp-collections`가 200인지, 같은 링크 두 번째가 409인지 확인한다.

되돌리기는 스크립트가 마지막에 출력하는 `rollback` 명령을 쓴다. 적립·수령 기록(`stamp_collections`,
`stamp_rewards`)은 catalog 밖이라 rollback해도 지워지지 않는다.

## 이름만 바꾸기

토큰은 그대로 두고 부스 이름만 고치려면 생성기를 쓰지 않는다. catalog CLI로 게시본을 export해
`stampBooths[].name`만 고친 뒤, 그 파일을 `import → publish`한다. 토큰 hash가 같으므로 인쇄한 QR은
계속 유효하다.
