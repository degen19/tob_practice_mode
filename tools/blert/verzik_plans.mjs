// Blert's top teams' P1s as verzikSim plans, simulated, then the best of them optimized with the sim's own plan search.
// Each recorded P1 becomes a plan: its Dawn specs in order (holder, recorded tick), with the surges and spec transfers
// Blert can't see added greedily until each Dawn fits on its tick (or as close as the team's spec allows).
// VERZIKSIM=<sim> node verzik_plans.mjs <blert.json.gz> <cfgs dir> [runs] [out.json]
import { readFileSync, writeFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
const SIM = process.env.VERZIKSIM || new URL('../../../verzikSim.JS/', import.meta.url).pathname;
const { makeContext, placeRotation, buildChart, simP1, planNeighbors } = await import(`${SIM}/engine/solver.js`);

const [file, dir, runsArg, outFile] = process.argv.slice(2);
const runs = Number(runsArg || 300);
const data = JSON.parse(gunzipSync(readFileSync(file)));
const median = (a) => { const s = [...a].sort((x, y) => x - y); return s[s.length >> 1]; };
const clone = (p) => ({ start: [...p.start], slots: p.slots.map((x) => ({ ...x })) });

/** How far the plan's Dawns land after their recorded ticks; Dawns that can't be placed at all count 1000 each. */
function lateness(ctx, plan) {
  for (let k = plan.slots.length; k > 0; k--) {
    const r = placeRotation(ctx, { start: plan.start, slots: plan.slots.slice(0, k) });
    if (!r.ok) continue;
    let late = 0;
    r.dawnTicks.forEach(([t], j) => { late += Math.max(0, t - plan.slots[j].at); });
    return 1000 * (plan.slots.length - k) + late;
  }
  return 1000 * (plan.slots.length + 1);
}

/** Surges and transfers added one at a time, each time the one that brings the Dawns closest to their ticks. */
function fit(ctx, plan) {
  let best = plan, score = lateness(ctx, plan);
  const n = ctx.players.length;
  for (let round = 0; round < 2 * plan.slots.length + n && score > 0; round++) {
    let next = null, nextScore = score;
    best.slots.forEach((s, j) => {
      const cands = [];
      if (!best.slots.some((x) => x.h === s.h && x.surge)) { const q = clone(best); q.slots[j].surge = true; cands.push(q); }
      if (s.st == null) for (let g = 0; g < n; g++) if (g !== s.h && !best.slots.some((x) => x.st === g)) { const q = clone(best); q.slots[j].st = g; cands.push(q); }
      for (const q of cands) { const sc = lateness(ctx, q); if (sc < nextScore) { next = q; nextScore = sc; } }
    });
    for (let k = 0; k < n; k++) if (best.start[k] === 100) { const q = clone(best); q.start[k] = 95; const sc = lateness(ctx, q); if (sc < nextScore) { next = q; nextScore = sc; } }
    if (!next) break;
    best = next; score = nextScore;
  }
  if (score >= 1000) {
    const placed = best.slots.length - Math.floor(score / 1000);
    best = { start: best.start, slots: best.slots.slice(0, Math.max(1, placed)) };
  }
  return { plan: best, late: score };
}

function blertPlan(r, p1) {
  const slots = r.attacks.filter(([t, , name]) => name === 'DAWN_SPEC' && t < p1).map(([t, i]) => ({ h: i, st: null, surge: false, at: t }));
  return { start: Array(r.players).fill(100), slots };
}

function run(ctx, cfgs, scale, plan, seed) {
  const built = buildChart(ctx, plan);
  if (!built.ok) return null;
  const sim = simP1(cfgs, scale, built.acts, runs, seed, plan.start);
  return { ...sim, acts: built.acts };
}

/** The sim's own plan search from a start plan: one change at a time (planNeighbors), kept while the fastest 10% improve. */
function improve(ctx, cfgs, scale, plan, base) {
  let best = { plan, sim: base };
  for (let round = 0; round < 6; round++) {
    let found = null;
    for (const q of planNeighbors(ctx, best.plan, { perms: false })) {
      const s = run(ctx, cfgs, scale, q, 'improve');
      if (s && s.killed >= best.sim.killed && s.cvar < (found?.sim.cvar ?? best.sim.cvar) - 0.05) found = { plan: q, sim: s };
    }
    if (!found) break;
    best = found;
  }
  return best;
}

const out = {};
for (const scale of [2, 3, 4, 5]) {
  const cfgs = JSON.parse(readFileSync(`${dir}/cfgs${scale}.json`, 'utf8'));
  const ctx = makeContext(cfgs, scale, { transfers: true });
  const solver = simP1(cfgs, scale, cfgs.map((c) => c.actions), runs, 'solver', cfgs.map((c) => (Number(c.startSpec) === 95 ? 95 : 100)));
  const rows = [];
  for (const r of data[scale]) {
    const p1 = r.marks.find((m) => m[1] === 'P2')?.[0];
    if (p1 == null || r.players !== scale) continue;
    const { plan, late } = fit(ctx, blertPlan(r, p1));
    const sim = run(ctx, cfgs, scale, plan, `blert-${r.uuid}`);
    if (sim) rows.push({ uuid: r.uuid, real: p1, dawns: plan.slots.length, late, plan, sim });
  }
  rows.sort((a, b) => a.sim.cvar - b.sim.cvar);
  const top = rows[0];
  const better = improve(ctx, cfgs, scale, top.plan, top.sim);
  console.log(`${scale}s: Blert real P1 median ${median(rows.map((x) => x.real))} (fastest ${Math.min(...rows.map((x) => x.real))});`
    + ` their rotations in the sim: median ${median(rows.map((x) => x.sim.median))}, ${median(rows.map((x) => x.dawns))} Dawns, sim-real ${median(rows.map((x) => x.sim.median - x.real))};`
    + ` best rotation ${top.sim.median} (fast10 ${top.sim.cvar.toFixed(1)}), optimized ${better.sim.median} (fast10 ${better.sim.cvar.toFixed(1)});`
    + ` the solver's chart ${solver.median} (fast10 ${solver.cvar.toFixed(1)})`);
  out[scale] = { best: { uuid: top.uuid, plan: top.plan, median: top.sim.median }, optimized: { plan: better.plan, median: better.sim.median, cvar: better.sim.cvar, acts: better.sim.acts }, solver: { median: solver.median, cvar: solver.cvar } };
}
if (outFile) writeFileSync(outFile, JSON.stringify(out));
