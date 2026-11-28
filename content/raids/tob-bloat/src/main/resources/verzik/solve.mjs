// A scale's Verzik setup: the chart template's setup with the Verz Solver's best P1 chart (as cli/solve.mjs picks it).
// VERZIKSIM=<sim> node solve.mjs <scale> -> cfgs<scale>.json
import { readFileSync, writeFileSync } from 'node:fs';
const TEAM = Number(process.argv[2] || 5);
const SIM = process.env.VERZIKSIM || new URL('../../../../../../../../verzikSim.JS/', import.meta.url).pathname;
const { readXlsx } = await import(`${SIM}/engine/xlsx.js`);
const { parse_chart } = await import(`${SIM}/engine/sim.js`);
const { makeContext, solve, simP1, chartKey } = await import(`${SIM}/engine/solver.js`);
const cfgs = await parse_chart(await readXlsx(readFileSync(`${SIM}/verzik_chart_template.xlsx`)), TEAM, null, 'A');
const ctx = makeContext(cfgs, TEAM, { transfers: true });
const res = solve(ctx, { beam: 150, slots: 16 });
const finals = res.tops.slice(0, 8);
for (const f of finals) f.sim = simP1(cfgs, TEAM, f.built.acts, 1000, 'solver', f.plan.start);
finals.sort((x, y) => x.sim.cvar - y.sim.cvar || x.sim.median - y.sim.median);
const best = finals[0];
const out = cfgs.map((c, k) => ({ ...c, actions: best.built.acts[k], startSpec: best.plan.start ? best.plan.start[k] : c.startSpec }));
writeFileSync(`cfgs${TEAM}.json`, JSON.stringify(out));
console.log('median', best.sim.median, 'start', best.plan.start, 'actions', out.map((c) => Object.keys(c.actions).length));
