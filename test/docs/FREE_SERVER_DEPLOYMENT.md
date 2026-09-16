# 무료 서버 배포 가이드

이 문서는 Docker Compose 네 서비스를 공인 HTTPS로 올려 Android/iOS 실기기에서 Web Push를 시험하는 절차입니다. 조사 기준은 2026-09-05이며, 무료 정책과 가용성은 가입·배포 직전 공식 페이지에서 다시 확인해야 합니다. 아래 기본 절차는 사용자가 Tokyo home region에서 실제로 확인한 `VM.Standard.E2.1.Micro` 1대만 사용할 수 있다는 조건을 반영합니다.

## 결론: 현실적인 선택

이 구성에는 Next.js, Java, PostgreSQL, Caddy가 계속 실행되고 백엔드가 외부 FCM/Apple Push endpoint로 HTTPS 요청을 보낼 수 있는 VM과 영속 디스크가 필요합니다. A1을 확보할 수 있다면 `VM.Standard.A1.Flex`가 더 적합하지만, Tokyo AD-1에서 실제 생성 시 `Out of capacity`가 발생해 `VM.Standard.E2.1.Micro`를 사용했습니다. 따라서 이 가이드는 **E2.1.Micro 1대에서 소수 실기기의 단기 기능 검증**을 목표로 합니다.

E2.1.Micro는 1/8 OCPU burstable과 메모리 1 GB뿐입니다. 일반 `docker-compose.yml`로 서버 안에서 이미지를 빌드하면 OOM 또는 장시간 정체 가능성이 높습니다. 반드시 Docker Buildx를 사용할 수 있는 개발 컴퓨터/CI에서 `linux/amd64` 이미지를 빌드하고 `docker-compose.e2-micro.yml`로 pull-only 배포합니다. 이것은 무료·24시간·영구·무중단을 보장하지 않으며 실제 축제 운영 사양도 아닙니다.

### 2026-09 공식 조건 비교

| 후보                     | 공식 무료 조건                                                                                                                         | 이 프로젝트 판단                                                       |
| ---------------------- | -------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------- |
| OCI Always Free A1     | home region에서 월 1,500 OCPU-hour + 9,000 GB-hour, Always Free tenancy 기준 합계 2 OCPU/12 GB; block volume 총 200 GB; outbound 월 10 TB | 사양상 가장 적합하지만 Tokyo tenancy의 현재 shape 목록에는 없음                         |
| OCI E2.1.Micro         | 최대 2대, 각 1/8 OCPU·1 GB                                                                                                           | 현재 선택. 외부 빌드·swap·강한 메모리 제한을 적용한 소수 기기 테스트만 지원               |
| Google Cloud Free Tier | 미국 3개 region 중 e2-micro 1대, 30 GB disk, 북미 outbound 1 GB/월                                                                       | 사양이 작고 VM 외부 IPv4는 월 1시간 초과 시 $0.005/시간이라 공개 IPv4 배포는 완전 무료가 아님 |
| AWS 신규 Free Plan       | 2025-07-15 이후 신규 계정은 6개월 또는 credit 소진 중 먼저 도래할 때 종료                                                                              | 장기 Always Free VM 대안 아님                                         |
| Azure 신규 무료            | 대상 VM은 신규 고객 12개월 월 750시간; 이후 실행분은 PAYG                                                                                          | 영구 무료 아님. 30일 후 무료 항목을 계속 받으려면 PAYG 전환 필요                       |
| Render Free            | 15분 inbound 없음 시 sleep, 재기동 약 1분, 로컬 FS ephemeral, 무료 Postgres 30일 만료                                                            | 상시 scheduler/영속 DB/단일 Compose에 부적합                              |

공식 근거:

- [OCI Always Free 리소스](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm): 현재 A1 한도, 200 GB volume, 10 TB outbound, capacity 오류와 idle reclaim 조건.
- [OCI Free Tier 개요](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier.htm): Always Free는 만료되지 않으며 compute는 home region에 생성.
- [Google Cloud Free Tier](https://docs.cloud.google.com/free/docs/free-cloud-features): e2-micro region/디스크/outbound 한도. [외부 IP 가격](https://cloud.google.com/vpc/network-pricing): 표준 VM의 외부 IPv4는 월 1시간만 free, 이후 $0.005/시간.
- [AWS EC2 Free Tier](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-free-tier-usage.html), [AWS Free Tier FAQ](https://aws.amazon.com/free/free-tier-faqs/): 2025-07-15 이후 신규 계정의 6개월/credit 조건.
- [Azure 계정과 무료 서비스](https://azure.microsoft.com/en-us/pricing/purchase-options/azure-account): VM 12개월 한도와 이후 과금 조건.
- [Render Free 제한](https://render.com/docs/free): idle sleep, ephemeral filesystem, free PostgreSQL 30일 만료.

OCI의 이전 안내를 인용한 블로그에는 A1 `4 OCPU/24 GB`가 남아 있을 수 있습니다. 현재 Oracle 공식 Always Free 문서는 `2 OCPU/12 GB`라고 명시하므로 이 가이드는 후자를 기준으로 합니다.

## 비용·가입 위험을 먼저 확인

1. OCI 무료 가입에는 유효한 이메일, 주소, 전화번호, 결제 검증용 카드가 필요합니다. 작은 임시 승인 금액이 보일 수 있고 자동 해제됩니다. Oracle은 사용자가 유료 계정으로 업그레이드하지 않는 한 카드를 청구하지 않는다고 안내합니다: [OCI 가입 공식 문서](https://docs.oracle.com/en-us/iaas/Content/GSG/Tasks/signingup_topic-Sign_Up_for_Free_Oracle_Cloud_Promotion.htm).
2. Home Region은 가입 후 변경할 수 없고 Always Free compute는 그 region에 만들어야 합니다. 이 배포에서는 Tokyo에서 콘솔이 실제로 `Always Free-eligible`로 표시한 `VM.Standard.E2.1.Micro`만 사용합니다. 이름이 비슷한 `VM.Standard.E2.1`, `E2.2`, `E3.Flex`, `VM.Standard2.*`는 무료라고 가정하지 않습니다.
3. `Out of host capacity`이면 무료 자원이 없는 상태일 수 있습니다. 다른 availability domain을 시도하거나 기다려야 하며, PAYG upgrade는 무료 한도 밖 리소스를 만들 때 실제 과금 위험을 엽니다.
4. Oracle은 일정 조건의 Always Free VM을 idle로 보고 회수할 수 있다고 명시합니다. 의미 없는 부하를 만들어 회피하지 마세요. 테스트가 중요하면 유료 VM 또는 다른 SLA 있는 호스팅으로 전환합니다.
5. 도메인 등록비는 보통 별도이며 이 가이드의 무료 범위에 포함되지 않습니다. 이미 소유한 도메인의 subdomain을 쓰는 것이 가장 단순합니다.
6. OCI budget/notification은 경고 수단이지 hard spending cap으로 가정하지 않습니다. PAYG로 전환했다면 Always Free eligible shape·region·volume 표시를 배포 때마다 확인하고, paid resource 생성을 막을 quota/policy를 별도로 검토합니다.

## 1. VM 만들기

OCI Console에서 다음처럼 만듭니다.

- Image: Ubuntu 24.04 LTS x86-64 또는 현재 Docker가 공식 지원하는 64-bit Ubuntu LTS
- Shape: 정확히 `VM.Standard.E2.1.Micro`, 콘솔에서 `Always Free-eligible` 확인
- 크기: 고정 1 GB. 비슷한 이름의 유료 shape로 변경하지 않음
- Boot volume: 기본 50 GB 정도, 계정의 Always Free 총 200 GB 한도 안
- Network: public subnet, public IPv4 할당
- SSH: 새 전용 key pair; private key는 안전하게 보관

두 번째 E2.1.Micro를 무료로 만들 수 있다면 PostgreSQL을 private VCN의 별도 VM으로 옮겨 메모리 여유를 확보할 수 있습니다. 다만 현재 절차는 우선 한 대에서 기능을 검증합니다. DB 분리는 5432를 인터넷에 공개하지 않고 NSG, `pg_hba.conf`, TLS/credential을 다시 설계해야 하므로 단순히 Compose service 주소만 바꾸지 않습니다.

### 1.1 2 GB swap 준비

서비스를 설치하기 전에 swap 존재 여부를 확인합니다.

```bash
free -h
sudo swapon --show
```

swap이 없을 때만 다음과 같이 2 GB 파일을 만듭니다.

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-espero-memory.conf
sudo sysctl --system
```

`swapon --show`와 `free -h`로 다시 확인합니다. 기존 `/swapfile` 또는 동일한 `fstab` 항목이 있다면 위 명령을 반복하지 않습니다. swap은 순간적인 OOM 방지 장치일 뿐 추가 RAM이나 빌드 공간이 아닙니다. swap 입출력이 계속 발생하면 푸시 작업이 1분 이상 지연되어 시계 알림이 `MISSED_SCHEDULE`로 끝날 수 있습니다.

## 2. DNS와 방화벽

DNS 공급자에서 subdomain A record를 VM public IPv4로 지정합니다.

```text
pwa.example.com.  A  203.0.113.10
```

AAAA record는 실제로 라우팅되는 public IPv6가 있을 때만 추가합니다. 잘못된 AAAA는 일부 휴대전화에서 인증서 발급/접속 실패를 만들 수 있습니다. DNS 전파 후 로컬에서 확인합니다.

```bash
dig +short A pwa.example.com
dig +short AAAA pwa.example.com
```

OCI VCN security list 또는 Network Security Group inbound:

- TCP 22: 본인 관리 IP/CIDR만 허용
- TCP 80: `0.0.0.0/0` (Caddy redirect와 ACME HTTP challenge)
- TCP 443: `0.0.0.0/0`
- UDP 443: `0.0.0.0/0` 선택 사항(HTTP/3). 열지 않아도 HTTPS/Web Push는 TCP로 동작
- 3000, 8080, 5432: 열지 않음

IPv6를 쓰면 동등한 IPv6 ingress rule도 별도로 구성합니다. VM의 Ubuntu firewall과 OCI network rule 두 계층을 모두 확인합니다.

Caddy는 공개 DNS A/AAAA가 서버를 가리키고 외부 TCP 80/443이 도달해야 자동으로 공인 TLS 인증서를 발급합니다: [Caddy HTTPS 공식 조건](https://caddyserver.com/docs/quick-starts/https).

> Docker 공식 문서는 publish한 container port가 ufw/firewalld 규칙을 우회할 수 있다고 경고합니다. Compose는 Caddy의 80/443만 publish하지만, 이후 디버깅 목적으로 DB/API port를 추가하지 마세요. 필요한 필터는 `DOCKER-USER` chain까지 고려합니다: [Docker Ubuntu 설치의 firewall 경고](https://docs.docker.com/engine/install/ubuntu/), [Docker port publishing](https://docs.docker.com/engine/network/port-publishing/).

## 3. Docker Engine과 Compose 설치

SSH로 접속해 [Docker의 Ubuntu 공식 설치 문서](https://docs.docker.com/engine/install/ubuntu/)에 있는 apt repository 절차를 그대로 사용합니다. convenience script를 운영 서버 설치의 기본으로 삼지 않습니다. 설치할 공식 패키지는 다음입니다.

```text
docker-ce
docker-ce-cli
containerd.io
docker-buildx-plugin
docker-compose-plugin
```

설치 확인:

```bash
sudo docker version
sudo docker compose version
```

사용자를 `docker` group에 넣으면 root-equivalent 권한을 주는 것이므로 편의상 무조건 적용하지 않습니다. 이 가이드의 명령은 필요하면 `sudo`를 붙입니다.

## 4. 코드와 비밀 설정

서버에 저장소를 clone하거나 검증된 release archive를 전송한 뒤 `test` 디렉터리로 이동합니다. `.env`는 배포 서버에서만 만들고, frontend/backend 이미지는 서버 밖에서 먼저 빌드합니다.

```bash
cd /opt/espero/test
cp .env.example .env
chmod 600 .env
```

비밀값은 신뢰할 수 있는 로컬 컴퓨터에서 생성합니다. E2 VM에서 `npx`나 Docker build를 실행하지 않습니다.

```bash
openssl rand -hex 32
openssl rand -hex 24
openssl rand -hex 32
docker run --rm node:24-alpine npx --yes web-push generate-vapid-keys --json
```

- 첫 임의값 → `POSTGRES_PASSWORD`
- 둘째 임의값 → `TEST_ACCESS_CODE`
- 셋째 임의값 → `APP_TOKEN_PEPPER` (최소 32바이트; 배포 간 유지)
- VAPID JSON의 한 쌍 → `APP_VAPID_PUBLIC_KEY`, `APP_VAPID_PRIVATE_KEY`
- 실제 운영자 연락처 → `APP_VAPID_SUBJECT=mailto:...`

`.env`의 도메인도 정확히 맞춥니다.

```dotenv
APP_DOMAIN=pwa.example.com
APP_ALLOWED_ORIGINS=https://pwa.example.com
APP_COOKIE_SECURE=true
BACKEND_IMAGE=ghcr.io/ACCOUNT/espero-pwa-backend@sha256:BACKEND_DIGEST
FRONTEND_IMAGE=ghcr.io/ACCOUNT/espero-pwa-frontend@sha256:FRONTEND_DIGEST
POSTGRES_IMAGE=postgres:16-alpine@sha256:POSTGRES_DIGEST
CADDY_IMAGE=caddy:2-alpine@sha256:CADDY_DIGEST
```

네 image reference는 모두 반드시 `linux/amd64`를 포함하고 `@sha256:...` digest로 고정해야 합니다. private registry를 쓴다면 서버에서 최소 read 권한 token으로 로그인하고 해당 token을 `.env`나 shell history에 적지 않습니다.

`TEST_ACCESS_CODE`는 참여자에게 안전한 채널로 전달하고 URL query나 QR에 직접 넣지 않습니다. 이미 발급된 세션은 다시 코드를 요구하지 않습니다. `APP_TOKEN_PEPPER`는 공유하지 않으며, 회전하면 기존 세션과 아직 확인하지 않은 알림 ACK가 무효화됩니다. `APP_PUSH_ALLOWED_ENDPOINT_HOST_SUFFIXES` 기본값은 Chromium의 FCM/Google, Apple Web Push, Firefox의 Mozilla Push, Windows용 Microsoft Edge의 WNS endpoint를 포함합니다. 다른 provider는 실제 endpoint를 확인한 뒤에만 확장합니다.

## 5. 외부 빌드와 E2 기동

### 5.1 개발 컴퓨터/CI에서 이미지 빌드

다음 예시는 저장소의 `test` 디렉터리에서 x86-64 이미지를 GHCR에 push합니다. `ACCOUNT`와 tag를 실제 값으로 바꾸고 `docker login ghcr.io`의 비밀번호 prompt에는 registry token을 입력합니다. token을 명령행 인자로 넘기지 않습니다.

```powershell
docker login ghcr.io -u ACCOUNT
docker buildx build --platform linux/amd64 --pull --push -t ghcr.io/ACCOUNT/espero-pwa-backend:COMMIT_SHA ./backend
docker buildx build --platform linux/amd64 --pull --push --build-arg NEXT_PUBLIC_API_BASE_PATH=/test-api -f ./frontend/Dockerfile.e2-micro -t ghcr.io/ACCOUNT/espero-pwa-frontend:COMMIT_SHA ./frontend
docker buildx imagetools inspect postgres:16-alpine
docker buildx imagetools inspect caddy:2-alpine
```

frontend의 E2 전용 Dockerfile은 Next.js를 정적 export하고 최종 image에는 Node 대신 non-root BusyBox `httpd`만 남깁니다. 빌드 출력 또는 registry 화면에서 애플리케이션 image digest를 확인하고, `imagetools inspect`의 최상위 `Digest`에서 공식 image digest를 확인합니다. E2 서버 `.env`에 네 reference를 `이름@sha256:값` 형태로 기록합니다. 최상위 manifest-list digest를 고정해도 Compose의 `platform: linux/amd64`가 그 안의 x86-64 image를 선택합니다. 이미지를 public으로 공개하지 않는다면 E2 서버에서도 read-only token으로 `docker login ghcr.io`를 한 번 수행합니다.

### 5.2 E2 서버에서는 pull만 수행

```bash
sudo docker compose -f docker-compose.e2-micro.yml config --quiet
sudo docker compose -f docker-compose.e2-micro.yml pull
sudo docker compose -f docker-compose.e2-micro.yml up -d --no-build
sudo docker compose -f docker-compose.e2-micro.yml ps
sudo docker compose -f docker-compose.e2-micro.yml logs --tail=100 caddy backend frontend postgres
```

`docker-compose.e2-micro.yml`에는 의도적으로 `build:`가 없습니다. `config --quiet`이 실패하면 누락된 `.env` 값부터 해결합니다. `pull` 중 registry 인증이나 architecture 오류가 나면 실행을 강행하지 않습니다. 정상 상태가 된 뒤 외부 네트워크에서 확인합니다.

```bash
curl -I https://pwa.example.com/
curl -fsS https://pwa.example.com/healthz
```

Caddy 인증서 오류가 나면 가장 먼저 DNS A/AAAA, TCP 80/443, VM 시간, 도메인 오타, Caddy log를 확인합니다. IP 주소 자체나 사설 hostname으로는 휴대전화에서 신뢰되는 자동 인증서를 기대하지 않습니다.

백엔드는 브라우저 push service의 TCP 443 outbound가 필요합니다. 일반 OCI public VM은 public IPv4 경로를 통해 이를 사용하며, egress rule이나 조직 방화벽을 제한했다면 기본 allowlist의 `fcm.googleapis.com`, `jmt17.google.com`, `*.push.apple.com`, `*.push.services.mozilla.com`, `*.notify.windows.com` HTTPS 연결을 허용합니다. Apple의 요구사항은 [WebKit 공식 문서](https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/), WNS FQDN은 [Microsoft 방화벽 allowlist 문서](https://learn.microsoft.com/en-us/windows/apps/develop/notifications/push-notifications/firewall-allowlist-config)를 참고합니다.

### 5.3 레지스트리 없이 TAR로 직접 전송

테스트 배포에서는 애플리케이션 image를 public registry에 올리지 않고 Docker Desktop에서 TAR로 내보내 서버에 직접 보낼 수 있습니다. PostgreSQL과 Caddy는 서버가 Docker Hub에서 직접 pull하므로 자체 image 두 개만 묶습니다.

```powershell
docker save --output "$env:TEMP\espero-app-images.tar" `
  espero-pwa-backend:RELEASE_TAG `
  espero-pwa-frontend:RELEASE_TAG

ssh -i "C:\path\to\private.key" ubuntu@SERVER_IP "mkdir -p /home/ubuntu/espero/test"
scp -i "C:\path\to\private.key" `
  "$env:TEMP\espero-app-images.tar" `
  docker-compose.e2-micro.yml Caddyfile .env.example `
  ubuntu@SERVER_IP:/home/ubuntu/espero/test/
```

서버에서는 image를 불러오고 공식 runtime image를 받습니다.

```bash
cd /home/ubuntu/espero/test
docker load -i espero-app-images.tar
docker pull --platform linux/amd64 postgres:16-alpine
docker pull --platform linux/amd64 caddy:2-alpine
```

이 저장소의 `scripts/init-e2-env.sh`는 현재 테스트 도메인용 `.env`를 서버 내부에서 처음 한 번만 생성합니다. 기존 `.env`가 있으면 덮어쓰지 않으며 파일 권한을 `600`으로 설정합니다. PostgreSQL 비밀번호, token pepper와 VAPID private key는 출력하지 않고, 참여자에게 필요한 `TEST_ACCESS_CODE`만 한 번 표시합니다.

```bash
chmod 700 init-e2-env.sh
./init-e2-env.sh
docker compose -f docker-compose.e2-micro.yml config --quiet
```

표시된 접근 코드는 별도 password manager에 보관합니다. `.env`와 image TAR를 Git에 추가하지 않습니다. 다른 도메인이나 release tag로 재배포할 때는 스크립트 내부의 공개 설정값을 먼저 바꾸고 검토합니다.

E2 profile은 backend 416 MiB, PostgreSQL 160 MiB, 정적 frontend 32 MiB, Caddy 96 MiB로 물리 메모리 상한을 두며 합계는 704 MiB입니다. Ubuntu, Docker daemon과 page cache에 이론상 약 320 MiB를 남깁니다. 각 서비스의 `memswap_limit`은 순간적인 swap 사용도 제한합니다. Docker에서 `memswap_limit`은 memory와 swap의 합계 상한이며 swap을 자주 쓰면 성능 비용이 발생합니다: [Compose service memory 설정](https://docs.docker.com/reference/compose-file/services/#mem_limit). JVM heap은 192 MiB, Hikari pool은 4, Tomcat worker는 16으로 제한합니다. frontend는 Node 없이 정적 파일만 제공합니다. PostgreSQL은 32 MiB shared buffers, 최대 12 connections, parallel worker 비활성화로 시작합니다. 데이터 안전성을 해치는 `fsync=off`, `full_page_writes=off`는 사용하지 않습니다.

기본 `APP_PUSH_REQUEST_TIMEOUT=PT15S`는 응답 없는 provider가 worker를 오래 점유하지 않게 하고, `APP_SCHEDULER_POOL_SIZE=2`는 시계 dispatcher와 push worker가 별도 scheduler thread에서 진행될 여지를 줍니다. 이를 1로 줄이면 한 push의 최대 15초 대기가 clock scheduler도 막을 수 있으므로 E2 profile에서도 2를 유지합니다. 작은 무료 VM에서 값을 무작정 늘리면 connection·memory 경쟁이 커집니다. delivery claim lease는 고정값이 아니라 `min(claim 시각 + request timeout + 5초, event 만료 시각)`으로 함께 조정됩니다. 각 재시도도 event 만료까지 남은 TTL만 provider에 전달합니다.

## 6. iOS 실기기 시험

조건: iOS/iPadOS 16.4 이상, 공인 HTTPS, 홈 화면 웹 앱.

1. Safari 또는 홈 화면 추가를 제공하는 브라우저에서 `https://pwa.example.com`을 엽니다.
2. 첫 세션을 만들기 전에 공유 메뉴의 **홈 화면에 추가**를 실행합니다. iOS/iPadOS 26에서는 추가 화면의 **Open as Web App(웹 앱으로 열기)** 옵션을 끄지 않습니다.
3. 브라우저 탭을 닫고 홈 화면 아이콘으로 앱을 엽니다. standalone 실행 안내가 사라졌는지 확인합니다.
4. `TEST_ACCESS_CODE`로 익명 세션을 한 번 발급합니다.
5. 앱의 **알림 활성화** 버튼을 직접 탭하고 iOS prompt에서 허용합니다. 페이지 로드시 자동 prompt는 정상 흐름이 아닙니다.
6. 카운터를 바꾸고 앱을 완전히 종료·재실행해 값 복원을 확인합니다.
7. 시계 알림 테스트를 시작하고 5분 이상 기다립니다. 잠금 화면, Notification Center, 앱 화면의 실행/전송/수신 ACK 상태를 함께 기록합니다.
8. 알림 하나를 탭해 앱이 열리거나 focus되는지 확인합니다. 수신 ACK는 알림 tap이 아니라 Service Worker의 표시 요청 직후 전송됩니다.

집중 모드, 알림 요약, 저전력 모드를 기록하고 결과를 해석합니다. iOS 일반 Safari 탭이나 WKWebView에서 홈 화면 웹 앱과 같은 Web Push 동작을 기대하지 않습니다. 공식 지원 범위와 사용자 동작 조건: [WebKit iOS Web Push](https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/). iOS/iPadOS 26의 웹 앱 열기 옵션: [WebKit Safari 26](https://webkit.org/blog/17333/webkit-features-in-safari-26-0/).

## 7. Android 실기기 시험

1. 최신 Chrome에서 HTTPS URL을 엽니다.
2. 메뉴의 **앱 설치** 또는 **홈 화면에 추가**로 설치하고 설치된 앱을 실행합니다.
3. 접근 코드로 새 세션을 만든 뒤 알림 활성화 버튼을 탭해 권한을 허용합니다.
4. 카운터 `0`, 중간값, `10`의 경계를 시험하고 재실행 후 복원을 확인합니다.
5. 시계 테스트를 시작하고 +1분부터 +5분까지 5개가 각각 고유 알림으로 생성되는지 관찰합니다.
6. 앱을 background/종료/화면 잠금 상태로 나눠 알림 시각과 누락을 기록합니다.
7. 각 알림 표시 뒤 수신 ACK가 기록되는지, 알림 tap으로 앱이 열리거나 focus되는지 각각 확인합니다.

Android의 배터리 최적화와 제조사별 정책에 따라 지연될 수 있습니다. 테스트 반복은 브라우저/OS 스팸 방어와 사용자 피로를 일으킬 수 있으므로 필요한 횟수만 수행합니다.

## 8. 백업과 복원

DB dump 전에 백업 디렉터리 권한을 제한합니다.

```bash
mkdir -p backups
chmod 700 backups
sudo docker compose -f docker-compose.e2-micro.yml exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "backups/espero-$(date -u +%Y%m%dT%H%M%SZ).dump"
chmod 600 backups/*.dump
```

single quote 안의 `$POSTGRES_USER`와 `$POSTGRES_DB`는 PostgreSQL 컨테이너 안에서 확장됩니다. dump 파일에는 민감한 세션·Push 구독 정보가 포함될 수 있으므로 암호화된 별도 저장소로 복사하고 보존 기간 뒤 삭제합니다. named volume 하나만 믿는 것은 백업이 아닙니다.

복원은 **별도 빈 DB/검증 환경에서 먼저 연습**합니다.

```bash
sudo docker compose -f docker-compose.e2-micro.yml exec -T postgres sh -c 'pg_restore --clean --if-exists --no-owner -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < backups/FILE.dump
```

이 명령은 대상 DB 객체를 교체할 수 있으므로 운영 DB에 즉시 실행하지 않습니다. PostgreSQL 공식 도구: [pg_dump](https://www.postgresql.org/docs/current/app-pgdump.html), [pg_restore](https://www.postgresql.org/docs/current/app-pgrestore.html).

Caddy의 `caddy_data`에는 인증서 상태가 있지만 없어져도 DNS/port 조건이 맞으면 재발급할 수 있습니다. 빈번한 삭제·재발급은 CA rate limit에 걸릴 수 있으므로 volume을 유지합니다. 장기 비밀인 `.env`의 VAPID key pair와 `APP_TOKEN_PEPPER`는 안전한 password manager/secret backup에 별도 보관합니다.

## 9. 안전한 업데이트와 롤백

1. PostgreSQL dump와 `.env`/VAPID 별도 백업을 확인합니다.
2. 변경 commit/tag를 기록합니다.
3. 외부에서 검증·고정한 새 image digest를 `.env`에 기록하고 pull합니다.
4. E2 profile의 `config --quiet`를 실행합니다.
5. E2 profile을 `up -d --no-build`로 재생성합니다.
6. health, logs, 카운터 복원, 한 기기 Push를 smoke test합니다.

```bash
sudo docker compose -f docker-compose.e2-micro.yml pull
sudo docker compose -f docker-compose.e2-micro.yml config --quiet
sudo docker compose -f docker-compose.e2-micro.yml up -d --no-build
sudo docker compose -f docker-compose.e2-micro.yml ps
sudo docker compose -f docker-compose.e2-micro.yml logs --since=10m --tail=200
```

PostgreSQL major image tag를 바꾸는 것은 일반 앱 업데이트가 아닙니다. 별도의 dump/restore 또는 공식 upgrade 절차가 필요합니다. `docker compose down -v`, `docker volume rm`, `/var/lib/docker` 삭제는 데이터와 인증서 상태를 지우므로 사용하지 않습니다.

롤백할 때도 DB migration의 역호환성을 먼저 판단합니다. 코드만 이전 tag로 되돌려도 새 schema를 읽지 못할 수 있습니다.

## 10. 문제 해결

### PWA가 설치되지 않음

- `https://` 공인 인증서인지 확인합니다.
- manifest와 192/512 아이콘 응답, `display: standalone`, Service Worker 등록 오류를 브라우저 개발자 도구에서 확인합니다.
- iOS에서는 일반 탭의 install prompt를 기대하지 말고 공유 메뉴로 홈 화면에 추가합니다.

### iOS 알림 버튼이 지원 안 됨으로 표시

- OS가 16.4 이상인지, 홈 화면에 추가한 앱 아이콘으로 열었는지 확인합니다.
- manifest가 standalone/fullscreen web app으로 열리도록 구성됐는지 확인합니다.
- 이미 거부했다면 iOS 설정의 해당 웹 앱 알림 권한을 사용자가 직접 변경해야 합니다.

### 구독은 됐지만 알림이 없음

- `APP_PUSH_ENABLED=true`, VAPID public/private가 같은 쌍인지 확인합니다.
- VM 시간이 NTP로 동기화됐는지 확인합니다.
- backend log에서 HTTP status를 확인하되 endpoint/key/token 원문은 출력하지 않습니다.
- egress TCP 443과 endpoint allowlist를 확인합니다. 기본 목록은 `fcm.googleapis.com`, `jmt17.google.com`, `.push.apple.com`, `.push.services.mozilla.com`, `.notify.windows.com`입니다.
- provider 응답 지연이 반복되면 `APP_PUSH_REQUEST_TIMEOUT`과 `PUSH_TRANSPORT_ERROR` 기록을 확인합니다. timeout은 양수로 두고 `APP_PUSH_TTL`보다 충분히 짧게 설정해 재시도 여유를 남깁니다. claim lease는 timeout에 5초를 더하되 event 만료 시각을 넘지 않게 서버가 계산합니다.
- 404/410이면 구독이 만료된 것이므로 앱에서 해제 후 다시 구독합니다.
- 집중 모드, OS 알림 설정, 배터리 최적화, 네트워크 상태를 확인합니다.

### 5개보다 적거나 여러 개가 한꺼번에 도착함

이는 즉시 서버 버그라고 단정할 수 없습니다. DB의 예정/생성/전송 접수 수, Service Worker 수신, OS 표시 수를 분리해 봅니다. RFC 8030의 201 응답은 push service 접수일 뿐 기기 전달을 뜻하지 않고 TTL 내에서 지연될 수 있습니다: [RFC 8030](https://www.rfc-editor.org/rfc/rfc8030.html#section-5).

그 다음 Web Push `Topic`이 전송되지 않았는지, Service Worker tag가 실제 구현의 `notification-<notificationId>`처럼 매번 고유한지 확인합니다. 같은 Topic/tag는 누적 요구와 충돌합니다.

### Caddy 인증서 발급 실패

```bash
sudo docker compose -f docker-compose.e2-micro.yml logs --tail=200 caddy
dig +short A pwa.example.com
curl -I http://pwa.example.com
```

DNS가 다른 IP를 가리키는지, 잘못된 AAAA가 있는지, TCP 80/443이 OCI와 host firewall 양쪽에서 열렸는지, `APP_DOMAIN`에 scheme/path를 넣지 않았는지 확인합니다.

### 컨테이너가 unhealthy/restart loop

```bash
sudo docker compose -f docker-compose.e2-micro.yml ps
sudo docker compose -f docker-compose.e2-micro.yml logs --tail=200 postgres backend frontend
sudo docker stats --no-stream
free -h
sudo swapon --show
df -h
```

DB credentials 불일치, migration 실패, 누락된 필수 env, `linux/amd64` image 문제, 메모리/디스크 부족을 확인합니다. `docker inspect`의 memory/memory-swap 제한도 Compose 값과 일치하는지 확인합니다. 커널 log의 OOM 기록이 있거나 swap 입출력이 지속되면 동시 테스트 수를 줄이고, 서버에서 build·package update를 실행하지 않습니다. `.env` 값을 log나 지원 게시물에 붙이지 않습니다.

### 비용이 발생하거나 VM이 사라짐

- OCI Console에서 shape, home region, boot/block volume 모두 `Always Free eligible`인지 확인합니다.
- 계정이 PAYG라면 Always Free 한도를 넘은 resource와 유료 public/service 항목을 확인합니다.
- idle reclaim 또는 capacity 상황은 애플리케이션으로 보장할 수 없습니다. 백업으로 다른 VM/유료 서비스에 복구합니다.

## 배포 완료 체크리스트

- [ ] 비용 정책을 다시 읽고 home region/shape/volume의 Always Free 표시를 캡처했다.
- [ ] shape가 정확히 `VM.Standard.E2.1.Micro`이고 다른 E2/E3/Standard shape가 아님을 확인했다.
- [ ] 도메인 A/AAAA가 정확하며 TCP 80/443만 공개했다.
- [ ] SSH 22는 관리 IP로 제한했다.
- [ ] 2 GB swap을 만들고 재부팅 후에도 활성화되는지 확인했다.
- [ ] `.env` 권한이 600이고 Git에 추적되지 않는다.
- [ ] frontend/backend를 외부에서 `linux/amd64`로 빌드하고 image digest를 `.env`에 고정했다.
- [ ] 서버에서는 `docker-compose.e2-micro.yml`과 `--no-build`만 사용했다.
- [ ] `docker stats`로 네 서비스가 메모리 상한 안에서 안정적인지 확인했다.
- [ ] DB/접근 코드/token pepper/VAPID private key가 모두 강한 임의값이며 pepper는 최소 32바이트다.
- [ ] Caddy 인증서와 HTTP→HTTPS redirect가 정상이다.
- [ ] backend의 대상 브라우저 push service HTTPS outbound가 가능하다.
- [ ] iOS 홈 화면 설치 전 첫 세션을 만들지 않았다.
- [ ] Android/iOS 각각 카운터 재실행 복원과 5개 알림/수신 ACK를 기록했다.
- [ ] DB dump를 별도 위치로 복사하고 복원 연습을 했다.
- [ ] 무료 서비스 회수/정책 변경 시 이동할 계획이 있다.
