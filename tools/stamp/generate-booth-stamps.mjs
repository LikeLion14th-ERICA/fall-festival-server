#!/usr/bin/env node
// Generates a random stamp token for every booth, writes the QR links that
// carry the raw tokens, and merges the booths and token SHA-256 hashes into a
// catalog manifest for Catalog CLI import (STAMP-001, V27).
//
// The links file holds the raw tokens and must stay private: anyone with a
// link can collect that booth's stamp. The manifest holds only hashes.
//
//   node tools/stamp/generate-booth-stamps.mjs \
//     --booths ops/stamp/booths.json \
//     --manifest-in ops/stamp/out/published.json \
//     --manifest-out ops/stamp/out/manifest-with-stamps.json \
//     --links-out ops/stamp/out/stamp-qr-links.csv \
//     [--base-url https://festival.likelionerica.com/stamps] [--daily] [--rotate] [--force]

import { createHash, randomBytes } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { pathToFileURL } from 'node:url';

export const DEFAULT_BASE_URL = 'https://festival.likelionerica.com/stamps';
const BOOTH_ID = /^[a-z0-9][a-z0-9-]{0,63}$/;

/** 128 random bits, URL-safe: 22 characters. */
export function newToken() {
  return randomBytes(16).toString('base64url');
}

export function sha256(value) {
  return createHash('sha256').update(value, 'utf8').digest('hex');
}

export function stampUrl(baseUrl, token) {
  const url = new URL(baseUrl);
  url.searchParams.set('b', token);
  return url.toString();
}

export function validateBooths(booths) {
  if (!Array.isArray(booths) || booths.length === 0) {
    throw new Error('booths must be a non-empty JSON array of {"id", "name"}');
  }
  const ids = new Set();
  for (const booth of booths) {
    if (!booth || typeof booth.id !== 'string' || !BOOTH_ID.test(booth.id)) {
      throw new Error(`invalid booth id: ${JSON.stringify(booth?.id)}`);
    }
    if (typeof booth.name !== 'string' || booth.name.trim() === '') {
      throw new Error(`booth ${booth.id} needs a name`);
    }
    if (ids.has(booth.id)) {
      throw new Error(`duplicate booth id: ${booth.id}`);
    }
    ids.add(booth.id);
  }
}

/**
 * Returns the merged manifest and the link rows. With daily tokens every booth
 * gets one token per festival day, so a photographed QR works only that day.
 */
export function generate({ booths, manifest, baseUrl = DEFAULT_BASE_URL, daily = false, token = newToken }) {
  validateBooths(booths);
  const days = daily ? (manifest.festivalDays ?? []).map((day) => day.festivalDate).sort() : [null];
  if (days.length === 0) {
    throw new Error('--daily needs festivalDays in the manifest');
  }
  const stampBooths = booths.map((booth, index) => ({ id: booth.id, name: booth.name.trim(), sortOrder: index + 1 }));
  const stampBoothTokens = [];
  const links = [];
  for (const booth of stampBooths) {
    for (const validDate of days) {
      const raw = token();
      stampBoothTokens.push({ boothId: booth.id, validDate, tokenSha256: sha256(raw) });
      links.push({ boothId: booth.id, boothName: booth.name, validDate, url: stampUrl(baseUrl, raw) });
    }
  }
  return { manifest: { ...manifest, stampBooths, stampBoothTokens }, links };
}

export function linksCsv(links) {
  const quote = (value) => `"${String(value ?? '').replaceAll('"', '""')}"`;
  const rows = links.map((link) => [link.boothId, link.boothName, link.validDate ?? '전체 기간', link.url].map(quote).join(','));
  // A byte-order mark so spreadsheet apps read the Korean names as UTF-8.
  return '﻿' + ['boothId,boothName,validDate,url', ...rows].join('\r\n') + '\r\n';
}

function parseArgs(argv) {
  const options = { baseUrl: DEFAULT_BASE_URL, daily: false, rotate: false, force: false };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    const next = () => {
      const value = argv[index + 1];
      if (value === undefined || value.startsWith('--')) throw new Error(`${arg} needs a value`);
      index += 1;
      return value;
    };
    switch (arg) {
      case '--booths': options.booths = next(); break;
      case '--manifest-in': options.manifestIn = next(); break;
      case '--manifest-out': options.manifestOut = next(); break;
      case '--links-out': options.linksOut = next(); break;
      case '--base-url': options.baseUrl = next(); break;
      case '--daily': options.daily = true; break;
      case '--rotate': options.rotate = true; break;
      case '--force': options.force = true; break;
      default: throw new Error(`unknown option: ${arg}`);
    }
  }
  for (const required of ['booths', 'manifestIn', 'manifestOut', 'linksOut']) {
    if (!options[required]) throw new Error(`--${required.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)} is required`);
  }
  return options;
}

function main() {
  const options = parseArgs(process.argv.slice(2));
  const readJson = (path) => JSON.parse(readFileSync(path, 'utf8').replace(/^﻿/, ''));
  const booths = readJson(options.booths);
  const manifest = readJson(options.manifestIn);
  if ((manifest.stampBoothTokens ?? []).length > 0 && !options.rotate) {
    throw new Error('the manifest already has booth tokens. New tokens invalidate every printed QR; pass --rotate to replace them.');
  }
  if (existsSync(options.linksOut) && !options.force) {
    throw new Error(`${options.linksOut} exists. It may hold links that are already printed; pass --force to overwrite.`);
  }
  new URL(options.baseUrl);
  const result = generate({ booths, manifest, baseUrl: options.baseUrl, daily: options.daily });
  for (const path of [options.manifestOut, options.linksOut]) mkdirSync(dirname(path), { recursive: true });
  writeFileSync(options.manifestOut, JSON.stringify(result.manifest, null, 2) + '\n', 'utf8');
  writeFileSync(options.linksOut, linksCsv(result.links), { encoding: 'utf8', mode: 0o600 });
  console.log(`booths: ${result.manifest.stampBooths.length}, tokens: ${result.manifest.stampBoothTokens.length}`);
  console.log(`manifest (hashes only): ${options.manifestOut}`);
  console.log(`QR links (secret, do not commit or share): ${options.linksOut}`);
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  try {
    main();
  } catch (error) {
    console.error(`generate-booth-stamps: ${error.message}`);
    process.exit(1);
  }
}
