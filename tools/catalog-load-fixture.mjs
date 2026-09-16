import fs from 'node:fs';

const output = process.argv[2];
if (!output) throw new Error('Usage: node tools/catalog-load-fixture.mjs <output.sql>');

const revision = 'f109dca2-8b28-4e09-8114-beebc2bd3ea2';
const q = (value) => `'${String(value).replaceAll("'", "''")}'`;
const sql = [];
const push = (statement) => sql.push(`${statement};`);

push('SET search_path TO public');
push("UPDATE ticket_guide SET map_id = NULL, place_id = NULL, pin_id = NULL, map_version = NULL WHERE id = 1");

for (let index = 1; index <= 7; index += 1) {
  const mapId = index === 1 ? 'map-overview' : `map-area-${index - 1}`;
  const kind = index === 1 ? 'OVERVIEW' : 'AREA';
  const version = index === 1 ? 'overview-v1' : 'map-v1';
  push(`INSERT INTO maps (festival_revision_id, id, kind, sort_rank, current_version) VALUES (${q(revision)}, ${q(mapId)}, ${q(kind)}, ${index}, ${q(version)})`);
  push(`INSERT INTO map_asset_versions (festival_revision_id, map_id, version, image_url, image_alt, image_width, image_height) VALUES (${q(revision)}, ${q(mapId)}, ${q(version)}, ${q(`/assets/load/${mapId}.png`)}, ${q(`Synthetic ${mapId}`)}, 1600, 900)`);
  push(`INSERT INTO map_translations (festival_revision_id, map_id, locale, name) VALUES (${q(revision)}, ${q(mapId)}, 'ko', ${q(`Synthetic ${mapId}`)})`);
}

for (let area = 1; area <= 6; area += 1) {
  push(`INSERT INTO map_areas (festival_revision_id, id, target_map_id) VALUES (${q(revision)}, ${q(`area-${area}`)}, ${q(`map-area-${area}`)})`);
  push(`INSERT INTO map_pins (festival_revision_id, map_id, map_version, id, category, x, y, place_id, area_id) VALUES (${q(revision)}, 'map-overview', 'overview-v1', ${q(`overview-area-pin-${area}`)}, 'area', ${area / 7}, 0.2, NULL, ${q(`area-${area}`)})`);
  push(`INSERT INTO map_pin_translations (festival_revision_id, map_id, map_version, pin_id, locale, label) VALUES (${q(revision)}, 'map-overview', 'overview-v1', ${q(`overview-area-pin-${area}`)}, 'ko', ${q(`Area ${area}`)})`);
}

for (let index = 1; index <= 100; index += 1) {
  const n = String(index).padStart(3, '0');
  const spaceId = `space-${n}`;
  const placeId = `place-${spaceId}`;
  const area = ((index - 1) % 6) + 1;
  const mapId = `map-area-${area}`;
  const pinId = `pin-${spaceId}`;
  const category = ['BOOTH', 'PUB', 'FLEA_MARKET'][(index - 1) % 3];
  push(`INSERT INTO spaces (festival_revision_id, id, category, image_url, image_width, image_height) VALUES (${q(revision)}, ${q(spaceId)}, ${q(category)}, ${q(`/assets/load/${spaceId}.png`)}, 400, 300)`);
  push(`INSERT INTO space_translations (festival_revision_id, space_id, locale, name, image_alt, location_text, experience_text) VALUES (${q(revision)}, ${q(spaceId)}, 'ko', ${q(`Synthetic ${spaceId}`)}, ${q(`Synthetic image ${spaceId}`)}, ${q(`Area ${area}`)}, 'Synthetic load fixture')`);
  push(`INSERT INTO space_sort_orders (festival_revision_id, locale, space_id, sort_rank) VALUES (${q(revision)}, 'ko', ${q(spaceId)}, ${index})`);
  push(`INSERT INTO places (festival_revision_id, id, kind, space_id) VALUES (${q(revision)}, ${q(placeId)}, 'SPACE', ${q(spaceId)})`);
  push(`INSERT INTO place_translations (festival_revision_id, place_id, locale, name, location_text) VALUES (${q(revision)}, ${q(placeId)}, 'ko', ${q(`Synthetic ${spaceId}`)}, ${q(`Area ${area}`)})`);
  const coordinate = (index % 10) / 10 + 0.01;
  push(`INSERT INTO map_pins (festival_revision_id, map_id, map_version, id, category, x, y, place_id, area_id) VALUES (${q(revision)}, ${q(mapId)}, 'map-v1', ${q(pinId)}, 'booth', ${coordinate}, ${coordinate}, ${q(placeId)}, NULL)`);
  push(`INSERT INTO map_pin_translations (festival_revision_id, map_id, map_version, pin_id, locale, label) VALUES (${q(revision)}, ${q(mapId)}, 'map-v1', ${q(pinId)}, 'ko', ${q(`Synthetic ${spaceId}`)})`);
  const overviewPinId = `overview-${pinId}`;
  push(`INSERT INTO map_pins (festival_revision_id, map_id, map_version, id, category, x, y, place_id, area_id) VALUES (${q(revision)}, 'map-overview', 'overview-v1', ${q(overviewPinId)}, 'booth', ${coordinate}, ${1 - coordinate}, ${q(placeId)}, NULL)`);
  push(`INSERT INTO map_pin_translations (festival_revision_id, map_id, map_version, pin_id, locale, label) VALUES (${q(revision)}, 'map-overview', 'overview-v1', ${q(overviewPinId)}, 'ko', ${q(`Overview ${spaceId}`)})`);
  push(`INSERT INTO space_map_targets (festival_revision_id, space_id, map_id, map_version, pin_id, place_id) VALUES (${q(revision)}, ${q(spaceId)}, ${q(mapId)}, 'map-v1', ${q(pinId)}, ${q(placeId)})`);
}

push(`INSERT INTO places (festival_revision_id, id, kind, space_id) VALUES (${q(revision)}, 'place-ticket-zone', 'FACILITY', NULL)`);
push(`INSERT INTO place_translations (festival_revision_id, place_id, locale, name, location_text) VALUES (${q(revision)}, 'place-ticket-zone', 'ko', 'Synthetic ticket zone', 'Overview')`);
push(`INSERT INTO map_pins (festival_revision_id, map_id, map_version, id, category, x, y, place_id, area_id) VALUES (${q(revision)}, 'map-overview', 'overview-v1', 'pin-ticket-zone', 'ticket', 0.5, 0.8, 'place-ticket-zone', NULL)`);
push(`INSERT INTO map_pin_translations (festival_revision_id, map_id, map_version, pin_id, locale, label) VALUES (${q(revision)}, 'map-overview', 'overview-v1', 'pin-ticket-zone', 'ko', 'Synthetic ticket zone')`);
push("UPDATE ticket_guide SET map_id = 'map-overview', place_id = 'place-ticket-zone', pin_id = 'pin-ticket-zone', map_version = 'overview-v1' WHERE id = 1");

fs.writeFileSync(output, `${sql.join('\n')}\n`, 'utf8');
console.log(JSON.stringify({ output, spaces: 100, maps: 7, places: 101, pins: 207, ticketZone: true }));
