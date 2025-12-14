#!/usr/bin/env node
/**
 * Canonical permission registry (docs/05-governance/permission-registry/permissions.registry.json)
 * dosyasını okuyup frontend Access MFE için tip güvenli bir modül üretir.
 *
 * Kullanım:
 *   node scripts/permissions/generate-frontend-permission-registry.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, '..', '..');

const registryPath = path.join(
  repoRoot,
  'docs',
  '05-governance',
  'permission-registry',
  'permissions.registry.json',
);

const frontendAccessDataDir = path.join(
  repoRoot,
  'frontend',
  'frontend',
  'apps',
  'mfe-access',
  'src',
  'data',
);
const outputPath = path.join(frontendAccessDataDir, 'permissionRegistry.generated.ts');

function readRegistry() {
  if (!fs.existsSync(registryPath)) {
    throw new Error(`Registry dosyası bulunamadı: ${registryPath}`);
  }
  const raw = fs.readFileSync(registryPath, 'utf8');
  const parsed = JSON.parse(raw);
  if (!parsed || typeof parsed !== 'object' || !Array.isArray(parsed.permissions)) {
    throw new Error('Registry formatı beklenen yapıda değil (permissions alanı bulunamadı).');
  }
  return parsed;
}

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true });
}

function buildFileContents(registry) {
  const header = `// AUTO-GENERATED FILE. DO NOT EDIT MANUALLY.\n// Kaynak: docs/05-governance/permission-registry/permissions.registry.json\n\n`;
  const typeDef = `export type PermissionRegistryEntry = {
  key: string;
  status: 'active' | 'deprecated';
  owner: string;
  module: string;
  description: string;
  sunsetAt?: string | null;
  deprecationReason?: string | null;
  tags?: string[];
  notes?: string | null;
};\n\n`;
  const versionConst = `export const permissionRegistryVersion = '${registry.version ?? 'unknown'}';
export const permissionRegistryGeneratedAt = '${registry.generatedAt ?? ''}';\n\n`;
  const data = JSON.stringify(registry.permissions, null, 2);
  const body = `export const permissionRegistry: PermissionRegistryEntry[] = ${data} as const;\n`;
  return header + typeDef + versionConst + body;
}

function main() {
  try {
    const registry = readRegistry();
    ensureDir(frontendAccessDataDir);
    const contents = buildFileContents(registry);
    fs.writeFileSync(outputPath, contents, 'utf8');
    console.log(
      `✅ Permission registry TypeScript modülü üretildi: ${path.relative(
        repoRoot,
        outputPath,
      )}`,
    );
  } catch (error) {
    console.error(`❌ Permission registry modülü üretilemedi: ${error.message}`);
    process.exit(1);
  }
}

main();
