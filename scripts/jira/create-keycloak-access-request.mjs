#!/usr/bin/env node

import { appendFile, mkdir, readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const PROJECT_ROOT = resolve(__dirname, '..', '..');
const CONFIG_PATH = join(PROJECT_ROOT, 'config', 'jira-access-request.json');
const ENV_PATH = join(PROJECT_ROOT, '.env');
const LOG_DIR = join(PROJECT_ROOT, 'logs');
const LOG_FILE = join(LOG_DIR, 'jira-access-request.log');

const SUPPORTED_ENVS = ['stage', 'production'];
const SUPPORTED_ROLES = ['kc-admin', 'kc-support', 'security-auditor'];
const SUPPORTED_TYPES = ['permanent', 'break-glass'];
const MAX_BREAK_GLASS_HOURS = 4;

export function parseArgs(argv = process.argv.slice(2)) {
  const result = {
    env: undefined,
    role: undefined,
    type: undefined,
    start: undefined,
    end: undefined,
    reason: undefined,
    change: undefined,
    assignee: undefined,
    dryRun: false,
  };

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    const next = argv[i + 1];
    switch (arg) {
      case '--env':
        result.env = next;
        i += 1;
        break;
      case '--role':
        result.role = next;
        i += 1;
        break;
      case '--type':
        result.type = next;
        i += 1;
        break;
      case '--start':
        result.start = next;
        i += 1;
        break;
      case '--end':
        result.end = next;
        i += 1;
        break;
      case '--reason':
        result.reason = next;
        i += 1;
        break;
      case '--change':
        result.change = next;
        i += 1;
        break;
      case '--assignee':
        result.assignee = next;
        i += 1;
        break;
      case '--dry-run':
        result.dryRun = true;
        break;
      default:
        throw new Error(`Bilinmeyen argüman: ${arg}`);
    }
  }

  return result;
}

function parseEnv(content) {
  const result = {};
  const lines = content.split(/\r?\n/);
  for (const line of lines) {
    if (!line || line.trim().startsWith('#')) continue;
    const [key, ...rest] = line.split('=');
    if (!key) continue;
    const value = rest.join('=').trim();
    result[key.trim()] = value.replace(/^"(.*)"$/, '$1').replace(/^'(.*)'$/, '$1');
  }
  return result;
}

export function applyDefaults(args, defaults = {}) {
  const output = { ...args };
  if (!output.assignee && defaults.defaultAssignee) {
    output.assignee = defaults.defaultAssignee;
  }
  if (!output.change && defaults.defaultChange) {
    output.change = defaults.defaultChange;
  }
  return output;
}

export function validateRequest(params) {
  const errors = [];

  if (!params.env || !SUPPORTED_ENVS.includes(params.env)) {
    errors.push(`'--env' değeri ${SUPPORTED_ENVS.join(', ')} olmalı.`);
  }

  if (!params.role || !SUPPORTED_ROLES.includes(params.role)) {
    errors.push(`'--role' değeri ${SUPPORTED_ROLES.join(', ')} olmalı.`);
  }

  if (!params.type || !SUPPORTED_TYPES.includes(params.type)) {
    errors.push(`'--type' değeri ${SUPPORTED_TYPES.join(', ')} olmalı.`);
  }

  if (!params.start || !params.end) {
    errors.push("'--start' ve '--end' zorunlu.");
  } else {
    const startDate = new Date(params.start);
    const endDate = new Date(params.end);
    if (Number.isNaN(startDate.getTime()) || Number.isNaN(endDate.getTime())) {
      errors.push('Tarih formatı ISO-8601 olmalı.');
    } else if (startDate >= endDate) {
      errors.push("'--start' değeri '--end' değerinden küçük olmalı.");
    } else if (params.type === 'break-glass') {
      const diffHours = (endDate - startDate) / (1000 * 60 * 60);
      if (diffHours > MAX_BREAK_GLASS_HOURS) {
        errors.push(`Break-glass erişimi en fazla ${MAX_BREAK_GLASS_HOURS} saat olabilir.`);
      }
    }
  }

  if (!params.reason || params.reason.length < 10) {
    errors.push("'--reason' en az 10 karakter olmalı.");
  }

  if (errors.length > 0) {
    const error = new Error(errors.join(' '));
    error.name = 'ValidationError';
    throw error;
  }
}

export function buildPayload(params, envConfig) {
  const summary = `Keycloak Access - ${params.env} - ${params.role}`;
  const descriptionLines = [
    params.reason,
    `Environment: ${params.env}`,
    `Role: ${params.role}`,
    `Access Type: ${params.type}`,
    `Start: ${params.start}`,
    `End: ${params.end}`,
    `Related: ${params.change || 'N/A'}`,
  ];

  const payload = {
    fields: {
      project: { key: envConfig.projectKey || 'SECURITY' },
      summary,
      issuetype: { name: envConfig.issueType || 'Task' },
      description: descriptionLines.join('\n'),
      assignee: params.assignee ? { name: params.assignee.replace(/^@/, '') } : undefined,
      customfield_env: params.env,
      customfield_access_type: params.type,
      customfield_role: params.role,
      customfield_start: params.start,
      customfield_end: params.end,
      customfield_related: params.change || 'N/A',
    },
  };

  if (!payload.fields.assignee) {
    delete payload.fields.assignee;
  }

  return payload;
}

export async function ensureLogDir() {
  if (!existsSync(LOG_DIR)) {
    await mkdir(LOG_DIR, { recursive: true });
  }
}

export async function logRequest(entry) {
  await ensureLogDir();
  const timestamp = new Date().toISOString();
  const line = `[${timestamp}] ${entry}\n`;
  await appendFile(LOG_FILE, line, { encoding: 'utf8' });
}

export async function submitRequest(payload, params, envVars, options = {}) {
  const baseUrl = envVars.JIRA_BASE_URL;
  const username = envVars.JIRA_USERNAME;
  const apiToken = envVars.JIRA_API_TOKEN;
  const siemWebhook = envVars.SIEM_WEBHOOK_URL;
  const siemAuth = envVars.SIEM_WEBHOOK_AUTH;

  if (!baseUrl || !username || !apiToken) {
    throw new Error('JIRA_BASE_URL, JIRA_USERNAME ve JIRA_API_TOKEN tanımlı olmalı.');
  }

  const url = `${baseUrl.replace(/\/$/, '')}/rest/api/3/issue`;
  const auth = Buffer.from(`${username}:${apiToken}`).toString('base64');

  const response = await fetch(url, {
    method: 'POST',
    headers: {
      Authorization: `Basic ${auth}`,
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(`JIRA isteği başarısız oldu (${response.status} ${response.statusText}): ${body}`);
  }

  const data = await response.json();
  const logMessage = `Keycloak access request created | ticket=${data.key} | env=${params.env} | role=${params.role} | type=${params.type}`;
  await logRequest(logMessage);
  await sendSiemEvent({
    siemWebhook,
    siemAuth,
    ticketKey: data.key,
    params,
  });

  if (!options.silent) {
    console.log(logMessage);
    console.log(`Ticket URL: ${data.self ?? `${baseUrl}/browse/${data.key}`}`);
  }

  return data;
}

async function loadDefaults() {
  let defaults = {};
  if (existsSync(CONFIG_PATH)) {
    const raw = await readFile(CONFIG_PATH, 'utf8');
    defaults = raw.trim().length ? JSON.parse(raw) : {};
  }
  return defaults;
}

async function loadEnvVars() {
  const envVars = { ...process.env };
  if (existsSync(ENV_PATH)) {
    const content = await readFile(ENV_PATH, 'utf8');
    Object.assign(envVars, parseEnv(content));
  }
  return envVars;
}

function showUsage(errorMessage) {
  if (errorMessage) {
    console.error(`Hata: ${errorMessage}\n`);
  }
  console.log(`Kullanım:
  node scripts/jira/create-keycloak-access-request.mjs --env <stage|production> --role <role> --type <permanent|break-glass> --start <ISO-date> --end <ISO-date> --reason "<text>" [--change SEC-123] [--assignee @user] [--dry-run]`);
}

async function main() {
  try {
    const defaults = await loadDefaults();
    const envVars = await loadEnvVars();
    const args = parseArgs(process.argv.slice(2));
    const params = applyDefaults(args, defaults);
    validateRequest(params);
    const payload = buildPayload(params, {
      projectKey: envVars.JIRA_PROJECT_KEY,
      issueType: envVars.JIRA_ISSUE_TYPE,
    });

    if (params.dryRun) {
      console.log(JSON.stringify(payload, null, 2));
      return;
    }

    await submitRequest(payload, params, envVars);
  } catch (error) {
    if (error.name === 'ValidationError') {
      showUsage(error.message);
      process.exitCode = 1;
      return;
    }
    console.error(`❌ İşlem başarısız: ${error.message}`);
    process.exitCode = 1;
  }
}

const isCliInvocation = (() => {
  try {
    const candidate = process.argv[1] ?? '';
    if (!candidate) {
      return false;
    }
    return import.meta.url === pathToFileURL(candidate).href;
  } catch {
    return false;
  }
})();

if (isCliInvocation) {
  main();
}

async function sendSiemEvent({ siemWebhook, siemAuth, ticketKey, params }) {
  if (!siemWebhook) {
    return;
  }
  try {
    const body = {
      eventType: 'keycloak_access_request_created',
      ticket: ticketKey,
      environment: params.env,
      role: params.role,
      accessType: params.type,
      start: params.start,
      end: params.end,
      change: params.change || null,
      assignee: params.assignee || null,
      timestamp: new Date().toISOString(),
    };
    const headers = {
      'Content-Type': 'application/json',
    };
    if (siemAuth) {
      headers.Authorization = siemAuth;
    }
    const response = await fetch(siemWebhook, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
    });
    if (!response.ok) {
      const text = await response.text();
      console.warn(`⚠️  SIEM webhook isteği başarısız oldu (${response.status} ${response.statusText}): ${text}`);
    }
  } catch (error) {
    console.warn(`⚠️  SIEM webhook gönderimi sırasında hata oluştu: ${error.message}`);
  }
}
