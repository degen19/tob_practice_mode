// A scale's Verzik raids: each raid's timeline (attacks, her events, HP, phase points) from the sim's own log, through
// the Show raid trace, for simreplay.mjs. VERZIKSIM=<sim> node vexport.mjs <scale> [N] [out.json.gz] (reads cfgs<scale>.json)
import { readFileSync, writeFileSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
const TEAM = Number(process.argv[2] || 5);
const SIM = process.env.VERZIKSIM || new URL('../../../../../../../../verzikSim.JS/', import.meta.url).pathname;
const { runOneLogged } = await import(`${SIM}/engine/optimize.js`);
const { Rng } = await import(`${SIM}/engine/rng.js`);
const { verzTrace } = await import(`${SIM}/verzraid.js`);
const cfgs = JSON.parse(readFileSync(`cfgs${TEAM}.json`, 'utf8'));
const n = Number(process.argv[3] || 300), raids = [];
for (let k = 1; k <= n; k++) {
  const seed = `v${k}`, r = runOneLogged(cfgs, TEAM, new Rng(seed));
  const tr = verzTrace(r.log, cfgs.map((c) => c.name), cfgs);
  const marks = [], atk = [], boss = [], hp = [];
  let lastHp = null;
  for (const e of tr) {
    if (e.mark) marks.push([e.t, e.mark]);
    else if (e.code && e.i != null) atk.push([e.t, e.i, e.code, e.landed ?? null, e.dmg ?? 0, e.crab ? (/West/.test(e.target) ? 'W' : 'E') : /purple/i.test(e.target || '') ? 'P' : '']);
    else if (e.boss) { const m = /-> (P\d)/.exec(e.tip || ''); boss.push([e.t, e.boss, m ? Number(m[1].slice(1)) - 1 : -1]); }
    else if (e.hp != null && e.phase) { if (e.hp !== lastHp) { hp.push([e.t, e.hp, e.phase]); lastHp = e.hp; } }
  }
  // the players' side from the sim's log: hitpoints after each hit or heal, and what they drink, eat and transfer
  const names = cfgs.map((c, i) => c.name || `P${i + 1}`), ev = [];
  for (const line of r.log.map(String)) {
    const m = /^t\s*(\d+) (\S+?): (.*)$/.exec(line);
    if (!m) continue;
    const t = Number(m[1]), i = names.indexOf(m[2]), what = m[3];
    if (i < 0) continue;
    let x;
    if ((x = /^takes (\d+)(?: from \S+)? -> (-?\d+) HP/.exec(what))) ev.push([t, i, 'hit', Number(x[2])]);
    else if ((x = /^brew (\d+) -> (\d+) HP/.exec(what))) ev.push([t, i, 'brew', Number(x[2])]);
    else if ((x = /^(?:eats? )?shark (\d+) -> (\d+)/.exec(what))) ev.push([t, i, 'shark', Number(x[2])]);
    else if (/^super restore/.test(what)) ev.push([t, i, 'restore', -1]);
    else if (/^surge /.test(what)) ev.push([t, i, 'surge', -1]);
    else if ((x = /^spec transfer -> (\S+)/.exec(what))) ev.push([t, i, 'transfer', names.indexOf(x[1])]);
  }
  raids.push({ seed, end: r.end ?? null, marks, atk, boss, hp, ev });
}
const out = process.argv[4] || `verzik${TEAM}.json.gz`;
writeFileSync(out, gzipSync(JSON.stringify(raids)));
const ok = raids.filter((x) => x.end != null).map((x) => x.end).sort((a, b) => a - b);
console.log('raids', n, 'ok', ok.length, 'median', ok[ok.length >> 1], 'bytes', gzipSync(JSON.stringify(raids)).length);
