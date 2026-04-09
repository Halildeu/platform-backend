/**
 * Service Manager API — lightweight Express server for Docker container management.
 * Provides health checks, start/stop/restart, bulk actions, and log streaming.
 *
 * Usage: node scripts/service-manager-api.js [--port 8795]
 */

const express = require('express');
const Docker = require('dockerode');
const http = require('http');
const path = require('path');
const { execSync, spawn } = require('child_process');

const app = express();
app.use((_req, res, next) => {
  res.header('Access-Control-Allow-Origin', '*');
  res.header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.header('Access-Control-Allow-Headers', 'Content-Type');
  if (_req.method === 'OPTIONS') return res.sendStatus(204);
  next();
});
app.use(express.json());

const docker = new Docker({ socketPath: '/var/run/docker.sock' });

const PORT = parseInt(process.env.SERVICE_MANAGER_PORT || '8795', 10);
const PROJECT_PREFIX = process.env.COMPOSE_PROJECT_NAME || process.env.SERVICE_MANAGER_PROJECT || '';

// ── Dynamic Service Discovery ───────────────────────────────────────
// Auto-detect container prefix from running containers
let detectedPrefix = '';
async function detectPrefix() {
  try {
    const containers = await docker.listContainers({ all: true });
    // Find most common prefix pattern: xxx-service-name-1
    const prefixes = {};
    for (const c of containers) {
      const name = (c.Names[0] || '').replace(/^\//, '');
      const match = name.match(/^(.+?)-(discovery-server|api-gateway|postgres-db|keycloak|vault)-\d+$/);
      if (match) {
        prefixes[match[1]] = (prefixes[match[1]] || 0) + 1;
      }
    }
    const sorted = Object.entries(prefixes).sort((a, b) => b[1] - a[1]);
    if (sorted.length > 0) {
      detectedPrefix = sorted[0][0];
      console.log(`[service-manager] Auto-detected project prefix: "${detectedPrefix}"`);
    }
  } catch (e) {
    console.warn('[service-manager] Could not auto-detect prefix:', e.message);
  }
}

function resolveContainer(baseName) {
  const prefix = PROJECT_PREFIX || detectedPrefix || 'platform';
  // Special cases
  if (baseName === 'pgvector') return 'pgvector_local';
  return `${prefix}-${baseName}-1`;
}

// ── Service Registry ────────────────────────────────────────────────
const SERVICES = [
  // Core
  { name: 'discovery-server', container: null /* auto: discovery-server-1 */, port: 8761, healthPath: '/actuator/health', category: 'core' },
  { name: 'api-gateway', container: null /* auto: api-gateway-1 */, port: 8080, healthPath: '/actuator/health', category: 'core' },
  // Auth & Security
  { name: 'auth-service', container: null /* auto: auth-service-1 */, port: 8088, healthPath: '/actuator/health', category: 'auth' },
  { name: 'keycloak', container: null /* auto: keycloak-1 */, port: 8081, healthPath: '/realms/master', category: 'auth' },
  { name: 'vault', container: null /* auto: vault-1 */, port: 8200, healthPath: '/v1/sys/health', category: 'auth' },
  { name: 'vault-unseal', container: null /* auto: vault-unseal-1 */, port: null, healthPath: null, category: 'auth' },
  // Business
  { name: 'user-service', container: null /* auto: user-service-1 */, port: 8089, healthPath: '/actuator/health', category: 'business' },
  { name: 'permission-service', container: null /* auto: permission-service-1 */, port: 8090, healthPath: '/actuator/health', category: 'auth', deprecated: true },
  // OpenFGA (Zanzibar authorization engine)
  { name: 'openfga', container: null /* auto: openfga-1 */, port: 4000, healthPath: '/healthz', category: 'auth' },
  // openfga-migrate: one-time migration, exits after completion — not monitored
  // openfga-playground: deprecated, 127.0.0.1 bind — use fga.dev instead
  { name: 'variant-service', container: null /* auto: variant-service-1 */, port: 8091, healthPath: '/actuator/health', category: 'business' },
  { name: 'core-data-service', container: null /* auto: core-data-service-1 */, port: 8092, healthPath: '/actuator/health', category: 'business' },
  { name: 'report-service', container: null /* auto: report-service-1 */, port: 8095, healthPath: '/actuator/health', category: 'business' },
  { name: 'schema-service', container: null /* auto: schema-service-1 */, port: 8096, healthPath: '/actuator/health', category: 'data' },
  // Data
  { name: 'postgres-db', container: null /* auto: postgres-db-1 */, port: 5432, healthPath: null, category: 'data' },
  // pgvector: lokal dev only
  // Observability
  { name: 'loki', container: null /* auto: loki-1 */, port: 3100, healthPath: '/ready', category: 'observability' },
  { name: 'tempo', container: null /* auto: tempo-1 */, port: 3200, healthPath: '/ready', category: 'observability' },
  { name: 'prometheus', container: null /* auto: prometheus */, port: 9090, healthPath: '/-/healthy', category: 'observability' },
  { name: 'grafana', container: null /* auto: grafana */, port: 3010, healthPath: '/api/health', category: 'observability' },
  { name: 'promtail', container: null /* auto: promtail-1 */, port: null, healthPath: null, category: 'observability' },
  // Frontend MFEs (process-based, not Docker)
  // MFE'ler canlıda statik serve — dev server yok, sadece lokal'de process olarak çalışır
];

// ── Helpers ──────────────────────────────────────────────────────────

function findService(name) {
  return SERVICES.find((s) => s.name === name);
}

function isProcessService(svc) {
  return svc.type === 'process';
}

function getProcessInfo(port) {
  try {
    const pid = execSync(`lsof -ti:${port} -sTCP:LISTEN 2>/dev/null`, { encoding: 'utf-8' }).trim();
    if (!pid) return { pid: null, running: false, status: 'stopped' };
    // Get process start time for uptime
    const startRaw = execSync(`ps -p ${pid.split('\n')[0]} -o lstart= 2>/dev/null`, { encoding: 'utf-8' }).trim();
    return {
      pid: pid.split('\n')[0],
      running: true,
      status: 'running',
      startedAt: startRaw ? new Date(startRaw).toISOString() : null,
    };
  } catch {
    return { pid: null, running: false, status: 'stopped' };
  }
}

async function getContainerInfo(containerName) {
  try {
    const container = docker.getContainer(containerName);
    const info = await container.inspect();
    const state = info.State;

    // Get resource stats (CPU + memory)
    let rssMb = null;
    let cpu = null;
    if (state.Running) {
      try {
        const stats = await new Promise((resolve, reject) => {
          container.stats({ stream: false }, (err, data) => err ? reject(err) : resolve(data));
        });
        // Memory: usage in bytes → MB
        const memUsage = stats.memory_stats?.usage || 0;
        const memCache = stats.memory_stats?.stats?.cache || stats.memory_stats?.stats?.inactive_file || 0;
        rssMb = Math.round((memUsage - memCache) / 1024 / 1024);
        // CPU: delta usage / delta system × 100
        const cpuDelta = (stats.cpu_stats?.cpu_usage?.total_usage || 0) - (stats.precpu_stats?.cpu_usage?.total_usage || 0);
        const sysDelta = (stats.cpu_stats?.system_cpu_usage || 0) - (stats.precpu_stats?.system_cpu_usage || 0);
        const numCpus = stats.cpu_stats?.online_cpus || 1;
        cpu = sysDelta > 0 ? Math.round((cpuDelta / sysDelta) * numCpus * 1000) / 10 : 0;
      } catch { /* stats not available */ }
    }

    return {
      id: info.Id.slice(0, 12),
      status: state.Running ? 'running' : state.Status,
      running: state.Running,
      startedAt: state.StartedAt,
      health: state.Health ? state.Health.Status : null,
      rssMb,
      cpu,
    };
  } catch (err) {
    if (err.statusCode === 404) {
      return { id: null, status: 'not_found', running: false, startedAt: null, health: null, rssMb: null, cpu: null };
    }
    return { id: null, status: 'error', running: false, startedAt: null, health: null, rssMb: null, cpu: null, error: err.message };
  }
}

function checkHealth(port, path, host, timeoutMs = 5000) {
  return new Promise((resolve) => {
    if (!port || !path) {
      resolve({ status: 'no_healthcheck', responseTime: null, details: null });
      return;
    }
    const target = host || '127.0.0.1';
    const start = Date.now();
    const req = http.get(`http://${target}:${port}${path}`, { timeout: timeoutMs }, (res) => {
      const elapsed = Date.now() - start;
      let body = '';
      res.on('data', (chunk) => { body += chunk; });
      res.on('end', () => {
        let details = null;
        try { details = JSON.parse(body); } catch { /* non-JSON health response */ }
        resolve({
          status: res.statusCode < 400 ? 'UP' : 'DOWN',
          responseTime: elapsed,
          httpStatus: res.statusCode,
          details,
        });
      });
    });
    req.on('error', () => {
      resolve({ status: 'DOWN', responseTime: Date.now() - start, details: null });
    });
    req.on('timeout', () => {
      req.destroy();
      resolve({ status: 'TIMEOUT', responseTime: timeoutMs, details: null });
    });
  });
}

function formatUptime(startedAt) {
  if (!startedAt) return null;
  const ms = Date.now() - new Date(startedAt).getTime();
  if (ms < 0) return null;
  const seconds = Math.floor(ms / 1000);
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  const days = Math.floor(hours / 24);
  if (days > 0) return `${days}d ${hours % 24}h`;
  if (hours > 0) return `${hours}h ${minutes % 60}m`;
  if (minutes > 0) return `${minutes}m ${seconds % 60}s`;
  return `${seconds}s`;
}

// ── Routes ───────────────────────────────────────────────────────────

// GET /api/services — list all services with status
app.get('/api/services', async (_req, res) => {
  const results = await Promise.all(
    SERVICES.map(async (svc) => {
      if (isProcessService(svc)) {
        const procInfo = getProcessInfo(svc.port);
        const healthInfo = procInfo.running
          ? await checkHealth(svc.port, svc.healthPath, svc.name)
          : { status: 'DOWN', responseTime: null };
        return {
          name: svc.name,
          container: null,
          port: svc.port,
          category: svc.category,
          type: 'process',
          containerId: procInfo.pid ? `pid:${procInfo.pid}` : null,
          containerStatus: procInfo.status,
          running: procInfo.running,
          startedAt: procInfo.startedAt,
          uptime: formatUptime(procInfo.startedAt),
          dockerHealth: null,
          health: healthInfo.status,
          responseTime: healthInfo.responseTime,
        };
      }
      const containerName = svc.container || resolveContainer(svc.name);
      const containerInfo = await getContainerInfo(containerName);
      // Use Docker's own health check status instead of HTTP probe
      const health = containerInfo.health === 'healthy' ? 'UP'
        : containerInfo.running ? (containerInfo.health || 'UP')
        : 'DOWN';
      return {
        name: svc.name,
        container: containerName,
        port: svc.port,
        category: svc.category,
        type: 'docker',
        containerId: containerInfo.id,
        containerStatus: containerInfo.status,
        running: containerInfo.running,
        startedAt: containerInfo.startedAt,
        uptime: formatUptime(containerInfo.startedAt),
        dockerHealth: containerInfo.health,
        health,
        responseTime: null,
        rssMb: containerInfo.rssMb,
        cpu: containerInfo.cpu,
      };
    }),
  );
  res.json({ services: results, timestamp: new Date().toISOString() });
});

// GET /api/services/:name/health — detailed health
app.get('/api/services/:name/health', async (req, res) => {
  const svc = findService(req.params.name);
  if (!svc) return res.status(404).json({ error: 'Service not found' });

  const healthInfo = await checkHealth(svc.port, svc.healthPath, svc.name);
  res.json({ name: svc.name, ...healthInfo });
});

// POST /api/services/:name/start
app.post('/api/services/:name/start', async (req, res) => {
  const svc = findService(req.params.name);
  if (!svc) return res.status(404).json({ error: 'Service not found' });

  if (isProcessService(svc)) {
    // Check if already running
    try {
      const pid = execSync(`lsof -ti:${svc.port} -sTCP:LISTEN 2>/dev/null`, { encoding: 'utf-8' }).trim();
      if (pid) return res.json({ ok: true, action: 'start', name: svc.name, note: 'already running' });
    } catch { /* not running */ }

    // Start frontend MFE via npm start in its app directory
    const webRoot = path.resolve(__dirname, '../../web');
    const appDir = path.join(webRoot, 'apps', svc.name);
    try {
      const env = {
        ...process.env,
        AUTH_MODE: 'permitAll',
        SHELL_SKIP_REMOTE_SERVICES: 'true',
        SHELL_ENABLE_SUGGESTIONS_REMOTE: 'false',
        SHELL_ENABLE_ETHIC_REMOTE: 'false',
      };
      const child = spawn('npm', ['start'], { cwd: appDir, env, detached: true, stdio: 'ignore' });
      child.unref();
      return res.json({ ok: true, action: 'start', name: svc.name, pid: child.pid });
    } catch (err) {
      return res.status(500).json({ ok: false, error: err.message });
    }
  }

  try {
    const container = docker.getContainer((svc.container || resolveContainer(svc.name)));
    await container.start();
    res.json({ ok: true, action: 'start', name: svc.name });
  } catch (err) {
    if (err.statusCode === 304) {
      return res.json({ ok: true, action: 'start', name: svc.name, note: 'already running' });
    }
    res.status(500).json({ ok: false, error: err.message });
  }
});

// POST /api/services/:name/stop
app.post('/api/services/:name/stop', async (req, res) => {
  const svc = findService(req.params.name);
  if (!svc) return res.status(404).json({ error: 'Service not found' });

  if (isProcessService(svc)) {
    try {
      const pid = execSync(`lsof -ti:${svc.port} -sTCP:LISTEN 2>/dev/null`, { encoding: 'utf-8' }).trim();
      if (!pid) return res.json({ ok: true, action: 'stop', name: svc.name, note: 'already stopped' });
      execSync(`kill ${pid.split('\n')[0]}`);
      return res.json({ ok: true, action: 'stop', name: svc.name });
    } catch (err) {
      return res.status(500).json({ ok: false, error: err.message });
    }
  }

  try {
    const container = docker.getContainer((svc.container || resolveContainer(svc.name)));
    await container.stop({ t: 10 });
    res.json({ ok: true, action: 'stop', name: svc.name });
  } catch (err) {
    if (err.statusCode === 304) {
      return res.json({ ok: true, action: 'stop', name: svc.name, note: 'already stopped' });
    }
    res.status(500).json({ ok: false, error: err.message });
  }
});

// POST /api/services/:name/restart
app.post('/api/services/:name/restart', async (req, res) => {
  const svc = findService(req.params.name);
  if (!svc) return res.status(404).json({ error: 'Service not found' });

  if (isProcessService(svc)) {
    return res.json({ ok: false, action: 'restart', name: svc.name, note: 'Process services must be restarted via npm scripts' });
  }

  try {
    const container = docker.getContainer((svc.container || resolveContainer(svc.name)));
    await container.restart({ t: 10 });
    res.json({ ok: true, action: 'restart', name: svc.name });
  } catch (err) {
    res.status(500).json({ ok: false, error: err.message });
  }
});

// POST /api/services/bulk-action — { action: "start"|"stop"|"restart", services: [...] }
app.post('/api/services/bulk-action', async (req, res) => {
  const { action, services: names } = req.body;
  if (!['start', 'stop', 'restart'].includes(action)) {
    return res.status(400).json({ error: 'Invalid action. Use start, stop, or restart.' });
  }

  const targets = names
    ? SERVICES.filter((s) => names.includes(s.name))
    : SERVICES;

  const results = await Promise.all(
    targets.map(async (svc) => {
      if (isProcessService(svc)) {
        if (action === 'stop') {
          try {
            const pid = execSync(`lsof -ti:${svc.port} -sTCP:LISTEN 2>/dev/null`, { encoding: 'utf-8' }).trim();
            if (!pid) return { name: svc.name, ok: true, note: 'already stopped' };
            execSync(`kill ${pid.split('\n')[0]}`);
            return { name: svc.name, ok: true };
          } catch (err) {
            return { name: svc.name, ok: false, error: err.message };
          }
        }
        return { name: svc.name, ok: false, note: 'Process services: use npm scripts for start/restart' };
      }
      try {
        const container = docker.getContainer((svc.container || resolveContainer(svc.name)));
        if (action === 'start') await container.start();
        else if (action === 'stop') await container.stop({ t: 10 });
        else await container.restart({ t: 10 });
        return { name: svc.name, ok: true };
      } catch (err) {
        if (err.statusCode === 304) return { name: svc.name, ok: true, note: `already ${action === 'stop' ? 'stopped' : 'running'}` };
        return { name: svc.name, ok: false, error: err.message };
      }
    }),
  );
  res.json({ action, results });
});

// GET /api/services/:name/logs?tail=100
app.get('/api/services/:name/logs', async (req, res) => {
  const svc = findService(req.params.name);
  if (!svc) return res.status(404).json({ error: 'Service not found' });

  const tail = parseInt(req.query.tail || '100', 10);

  if (isProcessService(svc)) {
    return res.json({ name: svc.name, logs: 'Process services: logs are in the terminal where npm run dev:* was started.', tail });
  }

  try {
    const container = docker.getContainer((svc.container || resolveContainer(svc.name)));
    const logs = await container.logs({
      stdout: true,
      stderr: true,
      tail,
      timestamps: true,
    });
    const text = logs.toString('utf-8');
    res.json({ name: svc.name, logs: text, tail });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ── Start ────────────────────────────────────────────────────────────
detectPrefix().then(() => {
  app.listen(PORT, '0.0.0.0', () => {
    console.log(`[service-manager] API listening on http://localhost:${PORT}`);
    console.log(`[service-manager] Managing ${SERVICES.length} services (prefix: ${PROJECT_PREFIX || detectedPrefix || 'auto'})`);
  });
});
