#!/bin/sh
set -eu

umask 077

if [ -e .env ]; then
  echo "Refusing to overwrite existing .env" >&2
  exit 1
fi

vapid_json="$(docker run --rm node:24-alpine npx --yes web-push generate-vapid-keys --json)"
vapid_public="$(printf '%s' "$vapid_json" | sed -n 's/.*"publicKey":"\([^"]*\)".*/\1/p')"
vapid_private="$(printf '%s' "$vapid_json" | sed -n 's/.*"privateKey":"\([^"]*\)".*/\1/p')"

if [ -z "$vapid_public" ] || [ -z "$vapid_private" ]; then
  echo "Failed to generate VAPID keys" >&2
  exit 1
fi

postgres_password="$(openssl rand -hex 32)"
test_access_code="$(openssl rand -hex 8)"
token_pepper="$(openssl rand -hex 32)"

{
  printf '%s\n' 'APP_DOMAIN=espero-pwa-test.duckdns.org'
  printf '%s\n' 'BACKEND_IMAGE=espero-pwa-backend:15e0cb6'
  printf '%s\n' 'FRONTEND_IMAGE=espero-pwa-frontend:15e0cb6'
  printf '%s\n' 'POSTGRES_IMAGE=postgres:16-alpine'
  printf '%s\n' 'CADDY_IMAGE=caddy:2-alpine'
  printf '%s\n' 'APP_ALLOWED_ORIGINS=https://espero-pwa-test.duckdns.org'
  printf '%s\n' 'POSTGRES_DB=espero_push'
  printf '%s\n' 'POSTGRES_USER=espero_push'
  printf 'POSTGRES_PASSWORD=%s\n' "$postgres_password"
  printf 'TEST_ACCESS_CODE=%s\n' "$test_access_code"
  printf 'APP_TOKEN_PEPPER=%s\n' "$token_pepper"
  printf 'APP_VAPID_PUBLIC_KEY=%s\n' "$vapid_public"
  printf 'APP_VAPID_PRIVATE_KEY=%s\n' "$vapid_private"
  printf '%s\n' 'APP_VAPID_SUBJECT=https://espero-pwa-test.duckdns.org'
  printf '%s\n' 'APP_SESSION_TTL=P7D'
  printf '%s\n' 'APP_SESSION_CLEANUP_DELAY=PT1H'
  printf '%s\n' 'APP_PUSH_ENABLED=true'
  printf '%s\n' 'APP_CLOCK_ZONE=Asia/Seoul'
  printf '%s\n' 'APP_COOKIE_SECURE=true'
  printf '%s\n' 'APP_PUSH_ALLOWED_ENDPOINT_HOST_SUFFIXES=fcm.googleapis.com,jmt17.google.com,.push.apple.com,.push.services.mozilla.com,.notify.windows.com'
  printf '%s\n' 'APP_ACCESS_MAX_FAILURES=5'
  printf '%s\n' 'APP_ACCESS_FAILURE_WINDOW=PT15M'
  printf '%s\n' 'APP_ACK_MAX_REQUESTS=60'
  printf '%s\n' 'APP_ACK_RATE_WINDOW=PT1M'
  printf '%s\n' 'APP_PUSH_TTL=PT70S'
  printf '%s\n' 'APP_PUSH_MAX_ATTEMPTS=3'
  printf '%s\n' 'APP_PUSH_REQUEST_TIMEOUT=PT15S'
  printf '%s\n' 'APP_PUSH_WORKER_DELAY=PT5S'
  printf '%s\n' 'APP_CLOCK_SCHEDULER_DELAY=PT5S'
  printf '%s\n' 'APP_SCHEDULER_POOL_SIZE=2'
  printf '%s\n' 'SPRING_DATASOURCE_MAXIMUM_POOL_SIZE=4'
  printf '%s\n' 'SPRING_DATASOURCE_MINIMUM_IDLE=1'
} > .env

chmod 600 .env
printf 'TEST_ACCESS_CODE=%s\n' "$test_access_code"
