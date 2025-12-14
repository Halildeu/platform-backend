#!/usr/bin/env node
/**
 * Permission registry doğrulayıcı.
 *
 * - Registry JSON dosyasının temel alanlarını denetler.
 * - Frontend manifest ve permission constant dosyalarındaki anahtarların registry’de olduğunu garanti eder.
 */
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, '..', '..');
const registryDir = path.join(repoRoot, 'docs', '05-governance', 'permission-registry');
const registryFile = path.join(registryDir, 'permissions.registry.json');

function readJson(filePath) {
  if (!fs.existsSync(filePath)) {
    throw new Error(`Registry dosyası bulunamadı: ${filePath}`);
  }
  const raw = fs.readFileSync(filePath, 'utf8');
  try {
    return JSON.parse(raw);
  } catch (error) {
    throw new Error(`Registry JSON parse edilemedi (${filePath}): ${error.message}`);
  }
}

function collectFiles(dir, acceptFn, accumulator = []) {
  if (!fs.existsSync(dir)) {
    return accumulator;
  }
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  for (const entry of entries) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      collectFiles(fullPath, acceptFn, accumulator);
      continue;
    }
    if (entry.isFile() && acceptFn(fullPath)) {
      accumulator.push(fullPath);
    }
  }
  return accumulator;
}

function resolveFrontendRoot() {
  const envCandidate = process.env.PERMISSION_REGISTRY_FRONTEND_PATH;
  const candidates = [];
  if (envCandidate) {
    candidates.push(path.resolve(repoRoot, envCandidate));
  }
  candidates.push(path.join(repoRoot, 'frontend', 'frontend'));
  candidates.push(path.join(repoRoot, 'frontend'));
  candidates.push(path.resolve(repoRoot, '..', 'frontend'));
  for (const candidate of candidates) {
    if (!candidate) continue;
    if (fs.existsSync(candidate) && fs.existsSync(path.join(candidate, 'apps'))) {
      return candidate;
    }
  }
  throw new Error(
    'Frontend repo yolu bulunamadı. PERMISSION_REGISTRY_FRONTEND_PATH env değeri ile manuel olarak tanımlayabilirsiniz.'
  );
}

function validateRegistryStructure(registry) {
  if (!registry || typeof registry !== 'object') {
    throw new Error('Registry içeriği nesne olmalı.');
  }
  if (!registry.permissions || !Array.isArray(registry.permissions) || registry.permissions.length === 0) {
    throw new Error('Registry içerisinde permissions alanı bulunmalı ve en az bir kayıt içermeli.');
  }
  const keySet = new Set();
  const keyPattern = /^[A-Z0-9_:-]+$/;
  registry.permissions.forEach((permission, index) => {
    if (!permission || typeof permission !== 'object') {
      throw new Error(`permissions[${index}] nesne olmalıdır.`);
    }
    const { key, status, owner, module, description } = permission;
    if (typeof key !== 'string' || !keyPattern.test(key)) {
      throw new Error(`permissions[${index}] geçersiz key değeri: ${key}`);
    }
    if (keySet.has(key)) {
      throw new Error(`permissions[${index}] anahtar çift kaydedilmiş: ${key}`);
    }
    keySet.add(key);
    if (!['active', 'deprecated'].includes(status)) {
      throw new Error(`permissions[${index}] status alanı 'active' veya 'deprecated' olmalı.`);
    }
    if (typeof owner !== 'string' || owner.length === 0) {
      throw new Error(`permissions[${index}] owner alanı zorunludur.`);
    }
    if (typeof module !== 'string' || module.length === 0) {
      throw new Error(`permissions[${index}] module alanı zorunludur.`);
    }
    if (typeof description !== 'string' || description.length === 0) {
      throw new Error(`permissions[${index}] description alanı boş olamaz.`);
    }
  });
  return keySet;
}

function extractManifestPermissions(filePath) {
  const content = fs.readFileSync(filePath, 'utf8');
  const blockRegex = /requiredPermissions\s*:\s*\[([\s\S]*?)\]/gm;
  const values = [];
  for (const match of content.matchAll(blockRegex)) {
    const block = match[1];
    for (const literal of block.matchAll(/['"]([^'"]+)['"]/g)) {
      values.push({ file: filePath, value: literal[1].trim() });
    }
  }
  return values;
}

function extractPermissionConstants(filePath) {
  const content = fs.readFileSync(filePath, 'utf8');
  const pairRegex = /[A-Z0-9_]+\s*:\s*['"]([^'"]+)['"]/g;
  const values = [];
  for (const match of content.matchAll(pairRegex)) {
    values.push({ file: filePath, value: match[1].trim() });
  }
  return values;
}

function main() {
  try {
    const registry = readJson(registryFile);
    const registryKeys = validateRegistryStructure(registry);
    const frontendRoot = resolveFrontendRoot();

    const manifestFiles = collectFiles(frontendRoot, (file) => file.includes('.manifest.'));
    const manifestValues = manifestFiles.flatMap(extractManifestPermissions);

    const constantsFiles = collectFiles(frontendRoot, (file) =>
      file.endsWith('permissions.constants.ts') || file.endsWith('permissions.constants.js')
    );
    const constantValues = constantsFiles.flatMap(extractPermissionConstants);

    const unknownManifestPermissions = manifestValues.filter((item) => item.value && !registryKeys.has(item.value));
    const unknownConstantPermissions = constantValues.filter((item) => item.value && !registryKeys.has(item.value));

    if (unknownManifestPermissions.length > 0 || unknownConstantPermissions.length > 0) {
      console.error('❌ Permission registry doğrulaması başarısız:');
      if (unknownManifestPermissions.length > 0) {
        console.error('  Manifest dosyalarında registry dışı izinler bulundu:');
        unknownManifestPermissions.forEach((item) => {
          console.error(`   • ${item.value} (${path.relative(repoRoot, item.file)})`);
        });
      }
      if (unknownConstantPermissions.length > 0) {
        console.error('  Permission constant dosyalarında registry dışı izinler bulundu:');
        unknownConstantPermissions.forEach((item) => {
          console.error(`   • ${item.value} (${path.relative(repoRoot, item.file)})`);
        });
      }
      process.exit(1);
    }

    console.log(
      `✅ Permission registry senkron: ${registryKeys.size} kayıt, ${manifestFiles.length} manifest ve ${constantsFiles.length} permission constant dosyası kontrol edildi.`
    );
  } catch (error) {
    console.error(`❌ Permission registry doğrulaması hata verdi: ${error.message}`);
    process.exit(1);
  }
}

main();
