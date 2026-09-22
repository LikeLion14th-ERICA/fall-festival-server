import assert from 'node:assert/strict';
import { test } from 'node:test';
import { generate, linksCsv, sha256, stampUrl, validateBooths } from './generate-booth-stamps.mjs';

const booths = [{ id: 'likelion', name: '멋사 부스' }, { id: 'photo', name: '포토부스' }];
const manifest = { festivalDays: [{ festivalDate: '2026-09-30' }, { festivalDate: '2026-09-29' }], spaces: [] };

test('gives every booth a distinct 128-bit token and keeps only hashes in the manifest', () => {
  const { manifest: merged, links } = generate({ booths, manifest });
  assert.deepEqual(merged.stampBooths, [
    { id: 'likelion', name: '멋사 부스', sortOrder: 1 },
    { id: 'photo', name: '포토부스', sortOrder: 2 },
  ]);
  assert.equal(merged.stampBoothTokens.length, 2);
  assert.equal(new Set(links.map((link) => link.url)).size, 2);
  for (const [index, link] of links.entries()) {
    const token = new URL(link.url).searchParams.get('b');
    assert.match(token, /^[A-Za-z0-9_-]{22}$/);
    assert.equal(merged.stampBoothTokens[index].tokenSha256, sha256(token));
    assert.equal(merged.stampBoothTokens[index].validDate, null);
    assert.ok(!JSON.stringify(merged).includes(token));
  }
  assert.deepEqual(merged.spaces, [], 'the rest of the manifest is kept');
});

test('daily tokens cover each festival day in order', () => {
  const { manifest: merged } = generate({ booths, manifest, daily: true });
  assert.deepEqual(
    merged.stampBoothTokens.map((token) => `${token.boothId}:${token.validDate}`),
    ['likelion:2026-09-29', 'likelion:2026-09-30', 'photo:2026-09-29', 'photo:2026-09-30'],
  );
});

test('builds the link on the configured page and quotes the CSV', () => {
  assert.equal(stampUrl('https://festival.likelionerica.com/stamps', 'abc'), 'https://festival.likelionerica.com/stamps?b=abc');
  const csv = linksCsv([{ boothId: 'a', boothName: '이름 "따옴표"', validDate: null, url: 'https://x/stamps?b=t' }]);
  assert.ok(csv.startsWith('﻿boothId,boothName,validDate,url\r\n'));
  assert.ok(csv.includes('"이름 ""따옴표"""'));
  assert.ok(csv.includes('"전체 기간"'));
});

test('rejects booth lists the catalog would refuse', () => {
  assert.throws(() => validateBooths([]), /non-empty/);
  assert.throws(() => validateBooths([{ id: 'Bad Id', name: 'x' }]), /invalid booth id/);
  assert.throws(() => validateBooths([{ id: 'a', name: ' ' }]), /needs a name/);
  assert.throws(() => validateBooths([{ id: 'a', name: 'x' }, { id: 'a', name: 'y' }]), /duplicate/);
});
