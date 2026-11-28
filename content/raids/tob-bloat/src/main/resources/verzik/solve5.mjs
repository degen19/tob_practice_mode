// The 5s Verzik setup: the chart template's 5-man setup with the Verz Solver's best P1 chart (as cli/solve.mjs picks it).
import { readFileSync, writeFileSync } from 'node:fs';
const SIM = process.env.VERZIKSIM || new URL('../../../../../../../../verzikSim.JS/', import.meta.url).pathname;
const { readXlsx } = await import(`${SIM}/engine/xlsx.js`);
const { parse_chart } = await import(`${SIM}/engine/sim.js`);
const { makeContext, solve, simP1, chartKey } = await import(`${SIM}/engine/solver.js`);
const cfgs = await parse_chart(await readXlsx(readFileSync(`${SIM}/verzik_chart_template.xlsx`)), 5, null, 'A');
const ctx = makeContext(cfgs, 5, { transfers: true });
const res = solve(ctx, { beam: 150, slots: 16 });
const finals = res.tops.slice(0, 8);
for (const f of finals) f.sim = simP1(cfgs, 5, f.built.acts, 1000, 'solver', f.plan.start);
finals.sort((x, y) => x.sim.cvar - y.sim.cvar || x.sim.median - y.sim.median);
const best = finals[0];
const out = cfgs.map((c, k) => ({ ...c, actions: best.built.acts[k], startSpec: best.plan.start ? best.plan.start[k] : c.startSpec }));
writeFileSync('cfgs5.json', JSON.stringify(out));
console.log('median', best.sim.median, 'start', best.plan.start, 'actions', out.map((c) => Object.keys(c.actions).length));
