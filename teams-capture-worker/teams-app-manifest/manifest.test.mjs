import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { inflateSync } from 'node:zlib';

const manifest = JSON.parse(readFileSync(new URL('manifest.template.json', import.meta.url)));
test('template declares matching schema and required presentation fields', () => {
  assert.equal(manifest.$schema, `https://developer.microsoft.com/json-schemas/teams/v${manifest.manifestVersion}/MicrosoftTeams.schema.json`);
  assert.match(manifest.accentColor, /^#[0-9A-Fa-f]{6}$/);
  assert.equal(manifest.bots[0].supportsCalling, true);
  for (const field of ['websiteUrl', 'privacyUrl', 'termsOfUseUrl']) assert.ok(manifest.developer[field]);
  // Operator-owned values remain explicit: do not accidentally publish a test fixture.
  assert.equal(manifest.id, '{{TEAMS_APP_ID}}');
  assert.equal(manifest.bots[0].botId, '{{BOT_APP_ID}}');
  assert.deepEqual(manifest.validDomains, ['{{PUBLIC_CALLBACK_DOMAIN}}']);
});
for (const [kind, size] of [['outline', 32], ['color', 192]]) {
  test(`${kind} icon is a complete RGBA PNG of the required dimensions`, () => {
    const bytes = readFileSync(new URL(manifest.icons[kind], import.meta.url));
    assert.equal(bytes.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
    assert.equal(bytes.readUInt32BE(16), size); assert.equal(bytes.readUInt32BE(20), size);
    assert.equal(bytes[24], 8); assert.equal(bytes[25], 6);
    const data = [];
    for (let offset = 8; offset < bytes.length;) {
      const length = bytes.readUInt32BE(offset);
      const type = bytes.toString('ascii', offset + 4, offset + 8);
      if (type === 'IDAT') data.push(bytes.subarray(offset + 8, offset + 8 + length));
      offset += length + 12;
      assert.ok(offset <= bytes.length);
    }
    const pixels = inflateSync(Buffer.concat(data));
    assert.equal(pixels.length, size * (1 + size * 4));
    let transparent = 0, opaque = 0;
    for (let y = 0; y < size; y++) {
      assert.equal(pixels[y * (1 + size * 4)], 0);
      for (let x = 0; x < size; x++) {
        const offset = y * (1 + size * 4) + 1 + x * 4;
        const alpha = pixels[offset + 3];
        if (alpha === 0) transparent++; else opaque++;
        if (kind === 'outline' && alpha) assert.deepEqual([...pixels.subarray(offset, offset + 4)], [255, 255, 255, 255]);
      }
    }
    assert.ok(opaque > 0);
    if (kind === 'outline') assert.ok(transparent > 0); else assert.equal(transparent, 0);
  });
}
