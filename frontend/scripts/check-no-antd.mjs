import { execSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

const root = new URL('../', import.meta.url);

function run(cmd) {
  return execSync(cmd, { encoding: 'utf8', cwd: new URL('.', root) });
}

const tracked = run("git ls-files '*.ts' '*.tsx' '*.js' '*.jsx'").split('\n').filter(Boolean);

const allowListPrefixes = [
  'packages/ui-kit/src/legacy/',
  'apps/mfe-reporting/src/components/entity-grid/EntityGridTemplate.tsx',
];

const forbiddenPatterns = [/from ['"]antd['"]/, /from ['"]@ant-design\/icons['"]/];

const violations = [];

for (const file of tracked) {
  if (allowListPrefixes.some((prefix) => file.startsWith(prefix))) {
    continue;
  }
  const content = readFileSync(new URL(file, root), 'utf8');
  if (forbiddenPatterns.some((re) => re.test(content))) {
    violations.push(file);
  }
}

if (violations.length > 0) {
  // eslint-disable-next-line no-console
  console.error(
    'Ant Design importları yasak. Lütfen aşağıdaki dosyalardan `antd` / `@ant-design/icons` bağımlılığını kaldırın veya FE-TAIL-05 kapsamına alın:\n',
    violations.map((f) => ` - ${f}`).join('\n'),
  );
  process.exit(1);
}

