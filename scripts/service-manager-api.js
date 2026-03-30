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

// ── Service Registry ────────────────────────────────────────────────
const SERVICES = [
  // Core
  { name: 'discovery-server', container: 'serban-discovery-server-1', port: 8761, healthPath: '/actuator/health', category: 'core' },
  { name: 'api-gateway', container: 'serban-api-gateway-1', port: 8080, healthPath: '/actuator/health', category: 'core' },
  // Auth & Security
  { name: 'auth-service', container: 'serban-auth-service-1', port: 8088, healthPath: '/actuator/health', category: 'auth' },
  { name: 'keycloak', container: 'serban-keycloak-1', port: 8081, healthPath: '/realms/master', category: 'auth' },
  { name: 'vault', container: 'backend-vault-1', port: 8200, healthPath: '/v1/sys/health', category: 'auth' },
  { name: 'vault-unseal', container: 'serban-vault-unseal-1', port: null, healthPath: null, category: 'auth' },
  // Business
  { name: 'user-service', container: 'serban-user-service-1', port: 8089, healthPath: '/actuator/health', category: 'business' },
  { name: 'permission-service', container: 'serban-permission-service-1', port: 8090, healthPath: '/actuator/health', category: 'business', deprecated: true },
  { name: 'openfga', container: 'serban-openfga-1', port: 4000, healthPath: '/healthz', category: 'auth' },
  { name: 'variant-service', container: 'serban-variant-service-1', port: 8091, healthPath: '/actuator/health', category: 'business' },
  { name: 'core-data-service', container: 'serban-core-data-service-1', port: 8092, healthPath: '/actuator/health', category: 'business' },
  { name: 'report-service', container: 'serban-report-service-1', port: 8095, healthPath: '/actuator/health', category: 'business' },
  // Data
  { name: 'postgres-db', container: 'serban-postgres-db-1', port: 5432, healthPath: null, category: 'data' },
  { name: 'pgvector', container: 'pgvector_local', port: 5433, healthPath: null, category: 'data' },
  // Observability
  { name: 'loki', container: 'serban-loki-1', port: 3100, healthPath: '/ready', category: 'observability' },
  { name: 'tempo', container: 'serban-tempo-1', port: 3200, healthPath: '/ready', category: 'observability' },
  { name: 'prometheus', container: 'observability-prometheus', port: 9090, healthPath: '/-/healthy', category: 'observability' },
  { name: 'grafana', container: 'observability-grafana', port: 3010, healthPath: '/api/health', category: 'observability' },
  { name: 'promtail', container: 'serban-promtail-1', port: null, healthPath: null, category: 'observability' },
  // Frontend MFEs (process-based, not Docker)
  { name: 'mfe-shell', container: null, port: 3000, healthPath: '/', category: 'frontend', type: 'process' },
  { name: 'mfe-suggestions', container: null, port: 3001, healthPath: '/', category: 'frontend', type: 'process' },
  { name: 'mfe-ethic', container: null, port: 3002, healthPath: '/', category: 'frontend', type: 'process' },
  { name: 'mfe-users', container: null, port: 3004, healthPath: '/', category: 'frontend', type: 'process' },
  { name: 'mfe-access', container: null, port: 3005, healthPath: '/', category: 'frontend', type: 'process' },
  { name: 'mfe-audit', container: null, port: 3006, healthPath: '/', category: 'frontend', type: 'process' },
  { name: 'mfe-reporting', container: null, port: 3007, healthPath: '/', category: 'frontend', type: 'process' },
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
    return {
      id: info.Id.slice(0, 12),
      status: state.Running ? 'running' : state.Status,
      running: state.Running,
      startedAt: state.StartedAt,
      health: state.Health ? state.Health.Status : null,
    };
  } catch (err) {
    if (err.statusCode === 404) {
      return { id: null, status: 'not_found', running: false, startedAt: null, health: null };
    }
    return { id: null, status: 'error', running: false, startedAt: null, health: null, error: err.message };
  }
}

function checkHealth(port, path, timeoutMs = 5000) {
  return new Promise((resolve) => {
    if (!port || !path) {
      resolve({ status: 'no_healthcheck', responseTime: null, details: null });
      return;
    }
    const start = Date.now();
    const req = http.get(`http://127.0.0.1:${port}${path}`, { timeout: timeoutMs }, (res) => {
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
          ? await checkHealth(svc.port, svc.healthPath)
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
      const [containerInfo, healthInfo] = await Promise.all([
        getContainerInfo(svc.container),
        checkHealth(svc.port, svc.healthPath),
      ]);
      return {
        name: svc.name,
        container: svc.container,
        port: svc.port,
        category: svc.category,
        type: 'docker',
        containerId: containerInfo.id,
        containerStatus: containerInfo.status,
        running: containerInfo.running,
        startedAt: containerInfo.startedAt,
        uptime: formatUptime(containerInfo.startedAt),
        dockerHealth: containerInfo.health,
        health: healthInfo.status,
        responseTime: healthInfo.responseTime,
      };
    }),
  );
  res.json({ services: results, timestamp: new Date().toISOString() });
});

// GET /api/services/:name/health — detailed health
app.get('/api/services/:name/health', async (req, res) => {
  const svc = findService(req.params.name);
  if (!svc) return res.status(404).json({ error: 'Service not found' });

  const healthInfo = await checkHealth(svc.port, svc.healthPath);
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
    const container = docker.getContainer(svc.container);
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
    const container = docker.getContainer(svc.container);
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
    const container = docker.getContainer(svc.container);
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
        const container = docker.getContainer(svc.container);
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
    const container = docker.getContainer(svc.container);
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
app.listen(PORT, () => {
  console.log(`[service-manager] API listening on http://localhost:${PORT}`);
  console.log(`[service-manager] Managing ${SERVICES.length} services`);
});
