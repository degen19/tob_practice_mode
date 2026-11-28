// The 5s Verzik raids for the server: each raid's timeline (attacks, her events, HP, phase points) from the sim's own
// log, through the Show raid trace. node vexport.mjs N out.json
import { readFileSync, writeFileSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
const SIM = process.env.VERZIKSIM || new URL('../../../../../../../../verzikSim.JS/', import.meta.url).pathname;
const { runOneLogged } = await import(`${SIM}/engine/optimize.js`);
const { Rng } = await import(`${SIM}/engine/rng.js`);
const { verzTrace } = await import(`${SIM}/verzraid.js`);
const cfgs = JSON.parse(readFileSync('cfgs5.json', 'utf8'));
const n = Number(process.argv[2] || 300), raids = [];
for (let k = 1; k <= n; k++) {
  const seed = `v${k}`, r = runOneLogged(cfgs, 5, new Rng(seed));
  const tr = verzTrace(r.log, cfgs.map((c) => c.name), cfgs);
  const marks = [], atk = [], boss = [], hp = [];
  let lastHp = null;
  for (const e of tr) {
    if (e.mark) marks.push([e.t, e.mark]);
    else if (e.code && e.i != null) atk.push([e.t, e.i, e.code, e.landed ?? null, e.dmg ?? 0, e.crab ? (/West/.test(e.target) ? 'W' : 'E') : /purple/i.test(e.target || '') ? 'P' : '']);
    else if (e.boss) { const m = /-> (P\d)/.exec(e.tip || ''); boss.push([e.t, e.boss, m ? Number(m[1].slice(1)) - 1 : -1]); }
    else if (e.hp != null && e.phase) { if (e.hp !== lastHp) { hp.push([e.t, e.hp, e.phase]); lastHp = e.hp; } }
  }
  raids.push({ seed, end: r.end ?? null, marks, atk, boss, hp });
}
const out = process.argv[3] || 'verzik5.json.gz';
writeFileSync(out, gzipSync(JSON.stringify(raids)));
const ok = raids.filter((x) => x.end != null).map((x) => x.end).sort((a, b) => a - b);
console.log('raids', n, 'ok', ok.length, 'median', ok[ok.length >> 1], 'bytes', gzipSync(JSON.stringify(raids)).length);
