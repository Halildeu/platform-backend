#!/usr/bin/env node
import { promises as fs } from 'fs';
import path from 'path';

const repoRoot = process.cwd();
const storyDir = path.join(repoRoot, 'docs/05-governance/02-stories');
const acceptanceDir = path.join(repoRoot, 'docs/05-governance/07-acceptance');
const projectFlowPath = path.join(repoRoot, 'docs/05-governance/PROJECT_FLOW.md');

const ignoreStoryFiles = new Set(['MP-STORY-FORMAT.md']);

async function loadText(p) {
  try {
    return await fs.readFile(p, 'utf8');
  } catch (err) {
    return null;
  }
}

async function main() {
  const projectFlow = (await loadText(projectFlowPath)) ?? '';
  const acceptanceFiles = (await fs.readdir(acceptanceDir)).filter(f => f.endsWith('.md'));

  const storyFiles = (await fs.readdir(storyDir)).filter(f => f.endsWith('.md') && !ignoreStoryFiles.has(f));

  const warnings = [];

  for (const file of storyFiles) {
    const id = file.replace(/\.md$/, '');
    const storyPath = path.join(storyDir, file);
    const storyText = await loadText(storyPath);
    if (!storyText) {
      warnings.push(`Story okunamadı: ${file}`);
      continue;
    }

    if (!/Story\s+Priority:\s*\d+/i.test(storyText)) {
      warnings.push(`${id}: Story Priority eksik`);
    }

    const acceptanceMatch = acceptanceFiles.find(f => f.startsWith(id));
    if (!acceptanceMatch) {
      warnings.push(`${id}: Acceptance dosyası yok (docs/05-governance/07-acceptance/${id}*.md)`);
    } else {
      const accText = await loadText(path.join(acceptanceDir, acceptanceMatch));
      if (accText && !accText.includes(id)) {
        warnings.push(`${id}: Acceptance dosyasında Story ID yer almıyor (${acceptanceMatch})`);
      }
    }

    if (!projectFlow.includes(id)) {
      warnings.push(`${id}: PROJECT_FLOW.md içinde bulunamadı`);
    }
  }

  if (warnings.length === 0) {
    console.log('✅ Governance link kontrolü: sorun yok');
    return;
  }

  console.warn('⚠ Governance link uyarıları:');
  for (const w of warnings) console.warn(`- ${w}`);
  process.exitCode = 1;
}

main().catch(err => {
  console.error('Hata:', err);
  process.exitCode = 1;
});
