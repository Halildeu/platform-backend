import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const fields = ['teamsAppId', 'botAppId', 'callbackBaseUrl', 'privacyUrl', 'termsUrl'];
const guid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
function httpsUrl(value, field, originOnly = false) {
  let url;
  try { url = new URL(value); } catch { throw new Error(`Invalid ${field}`); }
  if (typeof value !== 'string' || url.protocol !== 'https:' || url.username || url.password
      || url.hash || url.search || !url.hostname.includes('.')
      || /[{}]/.test(value) || url.hostname.endsWith('.invalid') || url.hostname.endsWith('.localhost')
      || /^(localhost|127\.|0\.|\[)/i.test(url.hostname)
      || (originOnly && (url.pathname !== '/' || url.port))) throw new Error(`Invalid ${field}`);
  return url;
}

export function createManifest(config) {
  if (!config || Array.isArray(config) || typeof config !== 'object'
      || Object.keys(config).some(key => !fields.includes(key)) || fields.some(key => !config[key]))
    throw new Error('Exactly the five non-secret configuration fields are required');
  for (const field of ['teamsAppId', 'botAppId']) {
    if (typeof config[field] !== 'string' || !guid.test(config[field])
        || config[field] === '00000000-0000-0000-0000-000000000000') throw new Error(`Invalid ${field}`);
  }
  const callback = httpsUrl(config.callbackBaseUrl, 'callbackBaseUrl', true);
  const privacy = httpsUrl(config.privacyUrl, 'privacyUrl');
  const terms = httpsUrl(config.termsUrl, 'termsUrl');
  const manifest = JSON.parse(readFileSync(new URL('manifest.template.json', import.meta.url), 'utf8'));
  manifest.id = config.teamsAppId;
  manifest.bots[0].botId = config.botAppId;
  manifest.developer.websiteUrl = callback.origin;
  manifest.developer.privacyUrl = privacy.href;
  manifest.developer.termsOfUseUrl = terms.href;
  manifest.validDomains = [callback.hostname];
  if (/\{\{[^}]+\}\}/.test(JSON.stringify(manifest))) throw new Error('Unresolved manifest placeholder');
  return manifest;
}

// The output must be a new directory: an older package is never silently overwritten.
export function writePackage(config, outputDirectory) {
  const manifest = Buffer.from(JSON.stringify(createManifest(config), null, 2) + '\n');
  const files = new Map([
    ['manifest.json', manifest],
    ['color.png', readFileSync(new URL('color.png', import.meta.url))],
    ['outline.png', readFileSync(new URL('outline.png', import.meta.url))],
  ]);
  mkdirSync(outputDirectory);
  for (const [name, bytes] of files) writeFileSync(resolve(outputDirectory, name), bytes, { flag: 'wx' });
  return [...files].map(([name, bytes]) => `${createHash('sha256').update(bytes).digest('hex')}  ${name}`).join('\n');
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    if (process.argv.length !== 4) throw new Error('Usage: node package.mjs approved-config.json NEW_OUTPUT_DIRECTORY');
    const config = JSON.parse(readFileSync(process.argv[2], 'utf8'));
    console.log(writePackage(config, resolve(process.argv[3])));
    console.log('Package files created. Tenant/schema validation and live acceptance remain required.');
  } catch {
    // Do not echo a supplied value, path or JSON: operator input might contain a secret by mistake.
    console.error('Package not completed. Check the five approved non-secret fields and use a new output directory.');
    process.exitCode = 1;
  }
}
