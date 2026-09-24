// Original microphone mark. No external assets or branding dependencies.
import { deflateSync } from 'node:zlib';
import { writeFileSync } from 'node:fs';
function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const body = Buffer.concat([Buffer.from(type), data]);
  const size = Buffer.alloc(4); size.writeUInt32BE(data.length);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body));
  return Buffer.concat([size, body, crc]);
}
for (const [size, file, background] of [[32, 'outline.png', [0, 0, 0, 0]], [192, 'color.png', [36, 69, 168, 255]]]) {
  const raw = Buffer.alloc(size * (1 + size * 4));
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
    const px = (x + 0.5) * 32 / size, py = (y + 0.5) * 32 / size;
    const capsule = px >= 12 && px <= 20 && py >= 5 && py <= 19
      && (py >= 9 && py <= 15 || (px - 16) ** 2 + (py - (py < 9 ? 9 : 15)) ** 2 <= 16);
    const arc = py >= 14 && py <= 23 && ((px - 16) ** 2 + (py - 14) ** 2 >= 49)
      && ((px - 16) ** 2 + (py - 14) ** 2 <= 81);
    const stem = px >= 15 && px <= 17 && py >= 22 && py <= 27;
    const foot = px >= 11 && px <= 21 && py >= 26 && py <= 28;
    raw.set(capsule || arc || stem || foot ? [255, 255, 255, 255] : background, y * (1 + size * 4) + 1 + x * 4);
  }
  const header = Buffer.alloc(13); header.writeUInt32BE(size); header.writeUInt32BE(size, 4); header[8] = 8; header[9] = 6;
  writeFileSync(new URL(file, import.meta.url), Buffer.concat([Buffer.from('89504e470d0a1a0a', 'hex'), chunk('IHDR', header), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]));
}
