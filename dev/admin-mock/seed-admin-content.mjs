// Seeds fictional notices and goods through the admin API for frontend development.
//
//   ADMIN_BASE_URL=https://... ADMIN_ORIGIN=http://localhost:3001 \
//   ADMIN_USERNAME=... ADMIN_PASSWORD=... node dev/admin-mock/seed-admin-content.mjs
//
// ADMIN_ORIGIN must equal the server's ADMIN_ALLOWED_ORIGIN. Items whose Korean
// title or name already exists are skipped, so re-running adds nothing twice.
// Every title starts with "[목]" so real content can be told apart and removed.
import { deflateSync } from 'node:zlib';
import { randomUUID } from 'node:crypto';

const base = required('ADMIN_BASE_URL').replace(/\/$/, '');
const origin = required('ADMIN_ORIGIN');
const dryRun = process.argv.includes('--dry-run');

const NOTICES = [
  { type: 'GENERAL', ko: ['[목] 축제 첫날 입장 안내', '[목] 정문은 11시에 열립니다. 실제 공지가 아닙니다.'],
    en: ['[Mock] Entry on day one', '[Mock] The main gate opens at 11:00. Not a real notice.'],
    links: [{ url: 'https://example.invalid/mock/notice-entry', labels: { ko: '[목] 자세히 보기', en: '[Mock] Details', 'zh-Hans': null, ja: null } }] },
  { type: 'GENERAL', ko: ['[목] 우천 시 공연 일정 변경', '[목] 비가 오면 공연이 체육관으로 옮겨집니다.'],
    en: ['[Mock] Rain schedule', '[Mock] Shows move to the gym if it rains.'], links: [] },
  { type: 'LOST_FOUND', ko: ['[목] 분실물 보관 안내', '[목] 분실물은 총학생회 부스에서 보관합니다.'],
    en: ['[Mock] Lost and found', '[Mock] Lost items are kept at the student council booth.'], links: [] },
];

const COLORS = [['네이비', 'Navy'], ['화이트', 'White']];
const SIZES = [['M', 'M'], ['L', 'L']];
const GOODS = [
  { mode: 'SINGLE', ko: ['[목] 축제 키링', '[목] 가상 상품입니다.'], en: ['[Mock] Festival keyring', '[Mock] Not a real product.'],
    price: 5000, color: [42, 91, 211] },
  { mode: 'SINGLE', ko: ['[목] 응원 타월', null], en: ['[Mock] Cheering towel', null], price: 12000, color: [232, 96, 60] },
  { mode: 'OPTIONS', ko: ['[목] 축제 티셔츠', '[목] 색상과 사이즈를 고르는 예시입니다.'],
    en: ['[Mock] Festival T-shirt', '[Mock] Example with colors and sizes.'], price: 20000, color: [30, 140, 90] },
];

const token = dryRun ? null : await login();
const existingNotices = new Set(dryRun ? [] : (await call('GET', '/api/v2/admin/notices')).data.items.map(n => n.title ?? n.translations?.ko?.title));
const existingGoods = new Set(dryRun ? [] : (await call('GET', '/api/v2/admin/products')).data.items.map(g => g.translations?.ko?.name ?? g.name));

for (const notice of NOTICES) {
  if (existingNotices.has(notice.ko[0])) { console.log(`skip notice: ${notice.ko[0]}`); continue; }
  const body = { type: notice.type, translations: { ko: { title: notice.ko[0], body: notice.ko[1] }, en: { title: notice.en[0], body: notice.en[1] } },
    links: notice.links, templateId: null };
  if (dryRun) { console.log('would create notice', JSON.stringify(body)); continue; }
  await call('POST', '/api/v2/admin/notices', body);
  console.log(`created notice: ${notice.ko[0]}`);
}

for (const goods of GOODS) {
  if (existingGoods.has(goods.ko[0])) { console.log(`skip goods: ${goods.ko[0]}`); continue; }
  const alt = { ko: `${goods.ko[0]} 사진`, en: `${goods.en[0]} photo`, 'zh-Hans': null, ja: null };
  const mediaId = dryRun ? randomUUID() : await uploadImage(solidPng(1024, 1024, goods.color));
  const colors = goods.mode === 'OPTIONS' ? COLORS.map(([ko, en]) => ({ id: randomUUID(), translations: { ko: { name: ko }, en: { name: en }, 'zh-Hans': null, ja: null } })) : [];
  const sizes = goods.mode === 'OPTIONS' ? SIZES.map(([ko, en]) => ({ id: randomUUID(), translations: { ko: { label: ko }, en: { label: en }, 'zh-Hans': null, ja: null } })) : [];
  // Navy comes in M and L, white only in L, so one combination is missing on purpose.
  const options = goods.mode === 'OPTIONS'
    ? [[0, 0], [0, 1], [1, 1]].map(([c, s]) => ({ colorId: colors[c].id, sizeId: sizes[s].id }))
    : [];
  const body = {
    optionMode: goods.mode,
    translations: { ko: { name: goods.ko[0], description: goods.ko[1] }, en: { name: goods.en[0], description: goods.en[1] }, 'zh-Hans': null, ja: null },
    price: { amount: goods.price, currency: 'KRW' },
    images: [{ mediaId, alt }], colors, sizes, options,
  };
  if (dryRun) { console.log('would create goods', JSON.stringify(body)); continue; }
  await call('POST', '/api/v2/admin/products', body);
  console.log(`created goods: ${goods.ko[0]}`);
}

async function login() {
  const response = await fetch(`${base}/api/v2/admin/sessions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Origin: origin },
    body: JSON.stringify({ username: required('ADMIN_USERNAME'), password: required('ADMIN_PASSWORD') }),
  });
  const data = await json(response, 'login');
  return data.data.accessToken;
}

async function uploadImage(png) {
  const form = new FormData();
  form.append('file', new Blob([png], { type: 'image/png' }), 'mock.png');
  const response = await fetch(`${base}/api/v2/admin/media/goods-images`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, Origin: origin, 'Idempotency-Key': randomUUID() },
    body: form,
  });
  return (await json(response, 'image upload')).data.mediaId;
}

async function call(method, path, body) {
  const headers = { Authorization: `Bearer ${token}`, Origin: origin };
  if (body !== undefined) Object.assign(headers, { 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() });
  const response = await fetch(`${base}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  return json(response, `${method} ${path}`);
}

async function json(response, what) {
  const text = await response.text();
  if (!response.ok) {
    let code = '';
    try { code = JSON.parse(text).error?.code ?? ''; } catch { /* not JSON */ }
    throw new Error(`${what} failed: HTTP ${response.status} ${code}`);
  }
  return JSON.parse(text);
}

function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required`);
  return value;
}

/** A plain RGB PNG; the goods image store only accepts sides of 1024 to 4096 px. */
function solidPng(width, height, [r, g, b]) {
  const row = Buffer.alloc(1 + width * 3);
  for (let x = 0; x < width; x++) row.set([r, g, b], 1 + x * 3);
  const raw = Buffer.concat(Array.from({ length: height }, () => row));
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header.set([8, 2, 0, 0, 0], 8);
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', header), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0)),
  ]);
}

function chunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([length, body, crc]);
}

function crc32(buffer) {
  let crc = ~0;
  for (const byte of buffer) {
    crc ^= byte;
    for (let k = 0; k < 8; k++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
  }
  return ~crc >>> 0;
}
