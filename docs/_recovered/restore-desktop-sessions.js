// Register existing CLI transcripts with the Claude Code desktop app by cloning
// a real session record and re-pointing cliSessionId at each transcript.
// Idempotent: skips anything the app already knows about. Safe to re-run.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const SESS_DIR = path.join(
  process.env.APPDATA,
  'Claude', 'claude-code-sessions',
  '0029ff21-ba8e-4421-a69e-cacd90d88eff',
  '5fc076b0-b736-4311-a34f-b5b71c883cf4'
);
const PROJECTS = 'C:\\Users\\wasil\\.claude\\projects';
const CAR = 'C:\\Users\\wasil\\Dev\\Car_Parking';
const DEV = 'C:\\Users\\wasil\\Dev';

const ENTRIES = [
  // Car_Parking — the Handoff app
  { id: '3fd0b40f-7a7f-4e52-8609-7495c3b21a35', cwd: CAR, proj: 'C--Users-wasil-Dev-Car-Parking', title: 'Handoff v0.5 → v0.7.1, Amsterdam garages' },
  { id: '875258db-9cdd-47e1-9d7f-fc5c31cfe957', cwd: CAR, proj: 'C--Users-wasil-Dev-Car-Parking', title: 'Handoff v0.4 → v0.6' },
  { id: '0465ea0f-89f8-448a-9d97-93e465bd0f0c', cwd: CAR, proj: 'C--Users-wasil-Dev-Car-Parking', title: 'Handoff v0.3.3 release' },
  { id: '83384767-32be-477d-b2f8-855793c17d13', cwd: CAR, proj: 'C--Users-wasil-Dev-Car-Parking', title: 'Handoff v0.3.3 → v0.4' },
  { id: '40a7abc7-51b6-4557-89c5-9dd2c0973f87', cwd: CAR, proj: 'C--Users-wasil-Dev-Car-Parking', title: 'Handoff phase 2, free parking zones' },
  { id: '1c491631-8efd-4d35-80a9-13477bd24c7d', cwd: CAR, proj: 'C--Users-wasil-Dev-Car-Parking', title: 'Handoff phase 1–2 MVP' },
  // Dev — the two webdev projects
  { id: '13a9018e-df91-4045-8ed0-b2e2df184475', cwd: DEV, proj: 'C--Users-wasil-Dev', title: 'Asian wholesale store + webdev skill' },
  { id: '25cc11b6-a419-4d36-8d6e-5fef1bdb846b', cwd: DEV, proj: 'C--Users-wasil-Dev', title: 'zar-mumio shop' },
];

// Use the largest real record as the template so the schema matches exactly.
const templateFile = fs
  .readdirSync(SESS_DIR)
  .filter(f => f.startsWith('local_') && f.endsWith('.json'))
  .map(f => ({ f, size: fs.statSync(path.join(SESS_DIR, f)).size }))
  .sort((a, b) => b.size - a.size)[0].f;
const template = JSON.parse(fs.readFileSync(path.join(SESS_DIR, templateFile), 'utf8'));
console.log(`template: ${templateFile}`);

const known = new Set(
  fs.readdirSync(SESS_DIR)
    .filter(f => f.startsWith('local_'))
    .map(f => JSON.parse(fs.readFileSync(path.join(SESS_DIR, f), 'utf8')).cliSessionId)
);

let made = 0;
for (const e of ENTRIES) {
  if (known.has(e.id)) { console.log(`= already registered: ${e.id.slice(0, 8)}  "${e.title}"`); continue; }

  const jsonl = path.join(PROJECTS, e.proj, `${e.id}.jsonl`);
  if (!fs.existsSync(jsonl)) { console.log(`! no transcript: ${e.id.slice(0, 8)}`); continue; }

  const st = fs.statSync(jsonl);
  const created = Math.floor(st.birthtimeMs || st.mtimeMs);
  const activity = Math.floor(st.mtimeMs);
  const sessionId = `local_${crypto.randomUUID()}`;

  fs.writeFileSync(
    path.join(SESS_DIR, `${sessionId}.json`),
    JSON.stringify({
      ...template,
      sessionId,
      cliSessionId: e.id,
      cwd: e.cwd,
      originCwd: e.cwd,
      createdAt: created,
      lastActivityAt: activity,
      lastFocusedAt: activity,
      isArchived: false,
      title: e.title,
      titleSource: 'custom',
    }),
    'utf8'
  );
  console.log(`+ ${e.id.slice(0, 8)}  "${e.title}"  (${(st.size / 1048576).toFixed(1)} MB)`);
  made++;
}
console.log(`\ncreated ${made} record(s)`);
