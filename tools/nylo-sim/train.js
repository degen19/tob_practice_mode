'use strict';
const { Worker } = require('worker_threads');
const fs = require('fs');
const { Room } = require('./sim');
const { LinearPolicy, FEATURES, NF: NFA, NP, POST_FEATURES } = require('./policy');
const NF = NFA + NP; // one role's block: the attack weights, then the lane-cover weights
const { unflat } = require('./es');
const args = Object.fromEntries(process.argv.slice(2).map((a) => a.split('=')));
const POP = +(args.pop || 40), SIGMA = +(args.sigma || 0.15), LR = +(args.lr || 0.03), SEEDS = +(args.seeds || 16), GENS = +(args.gens || 400);
const OUT = args.out || 'best.json';
const DIM = 9 * NF;
function hand() {
  const w = new Float64Array(NF);
  const set = (k, v) => { w[FEATURES.indexOf(k)] = v; };
  set('bias', 0.5); set('own', 2); set('kills', 2); set('nVic', 0.5); set('walk', -0.7); set('swap', -0.3); set('life', 1); set('aggro', 1); set('biting', 0.5);
  set('stall', 0.5); set('standsAtCheck', 2); set('mustLast', 1.5); set('bigSplitStall', -2); set('nWrong', -0.6); set('claimed', -1); set('speed', -1);
  const f = new Float64Array(DIM); for (let r = 0; r < 9; r++) f.set(w, r * NF); return f;
}
function load(file) {
  const j = JSON.parse(fs.readFileSync(file)); const { WEAPON_NAMES } = require('./sim');
  const oldNames = [...j.features, ...WEAPON_NAMES.slice(0, (j.nfa || j.nf) - j.features.length), ...(j.post || [])], oldNf = j.nf, names = [...FEATURES, ...WEAPON_NAMES, ...POST_FEATURES];
  const f = new Float64Array(DIM);
  const blocks = j.phases ? 9 : 3; // an older single-phase file seeds all three phases of a role
  for (let b = 0; b < 9; b++) { const src = blocks === 9 ? b : Math.floor(b / 3); names.forEach((nm, i) => { const k = oldNames.indexOf(nm); if (k >= 0) f[b * NF + i] = j.theta[src * oldNf + k]; else if (nm === 'pbias') f[b * NF + i] = -1; }); }
  return f;
}
let theta = args.init ? load(args.init) : hand();
let rngS = 12345; const rnd = () => { rngS ^= rngS << 13; rngS >>>= 0; rngS ^= rngS >>> 17; rngS ^= rngS << 5; rngS >>>= 0; return rngS / 4294967296; };
const gauss = () => Math.sqrt(-2 * Math.log(rnd() + 1e-12)) * Math.cos(2 * Math.PI * rnd());
const workers = [0, 1].map(() => new Worker(__dirname + '/es.js'));
function evalAll(cands, seeds) {
  const half = Math.ceil(cands.length / 2);
  const parts = [cands.slice(0, half), cands.slice(half)];
  return Promise.all(parts.map((p, i) => new Promise((res) => { workers[i].once('message', res); workers[i].postMessage({ cands: p.map((c) => Array.from(c)), seeds, nf: NF }); }))).then((r) => r.flat());
}
const clock = (tk) => { const s = tk * 0.6; return `${Math.floor(s / 60)}:${(s % 60).toFixed(1).padStart(4, '0')}`; };
function spread(b) { b = [...b].sort((x, y) => x - y); const pct = (k) => `${(100 * b.filter((x) => x === k).length / b.length).toFixed(0)}%`; return `2:50 ${pct(284)} 2:52 ${pct(288)} 2:55 ${pct(292)} 2:57 ${pct(296)} 3:00 ${pct(300)} slower ${(100 * b.filter((x) => x > 300).length / b.length).toFixed(0)}% median ${clock(b[b.length >> 1])}`; }
const VAL = Array.from({ length: 200 }, (_, i) => 900000 + i);
let m = new Float64Array(DIM), v = new Float64Array(DIM), bestVal = 1e9;
(async () => {
  for (let g = 1; g <= GENS; g++) {
    const eps = [], cands = [];
    for (let k = 0; k < POP / 2; k++) { const e = new Float64Array(DIM); for (let i = 0; i < DIM; i++) e[i] = gauss(); eps.push(e); for (const sgn of [1, -1]) { const c = new Float64Array(DIM); for (let i = 0; i < DIM; i++) c[i] = theta[i] + sgn * SIGMA * e[i]; cands.push(c); } }
    const seeds = Array.from({ length: SEEDS }, () => Math.floor(rnd() * 1e9));
    const res = await evalAll(cands, seeds);
    const fit = res.map((r) => -(r.gone + 0.5 * r.boss));
    const order = fit.map((f, i) => [f, i]).sort((a, b) => a[0] - b[0]);
    const rank = new Float64Array(fit.length); order.forEach(([, i], k) => { rank[i] = k / (fit.length - 1) - 0.5; });
    const grad = new Float64Array(DIM);
    for (let k = 0; k < eps.length; k++) { const a = rank[2 * k] - rank[2 * k + 1]; for (let i = 0; i < DIM; i++) grad[i] += a * eps[k][i]; }
    for (let i = 0; i < DIM; i++) { grad[i] /= eps.length * SIGMA; m[i] = 0.9 * m[i] + 0.1 * grad[i]; v[i] = 0.999 * v[i] + 0.001 * grad[i] * grad[i]; theta[i] += LR * (m[i] / (1 - 0.9 ** g)) / (Math.sqrt(v[i] / (1 - 0.999 ** g)) + 1e-8) - 0.0005 * theta[i]; }
    const meanBoss = res.reduce((a, r) => a + r.boss, 0) / res.length, meanGone = res.reduce((a, r) => a + r.gone, 0) / res.length;
    let line = `gen ${g} pop ${spread(res.flatMap((r) => r.bosses))} gone ${meanGone.toFixed(1)}`;
    {
      const [r] = await evalAll([theta], VAL).then((x) => x);
      const hist = {}; for (const b of [...r.bosses].sort((x, y) => x - y)) hist[b] = (hist[b] || 0) + 1;
      line += ` | VAL ${spread(r.bosses)} boss ${r.boss.toFixed(2)} gone ${r.gone.toFixed(1)} stalls ${r.stalls.toFixed(2)} ${JSON.stringify(hist)}`;
      if (r.boss < bestVal) { bestVal = r.boss; fs.writeFileSync(OUT, JSON.stringify({ gen: g, val: r, theta: Array.from(theta), features: FEATURES, nf: NF, nfa: NFA, post: POST_FEATURES, phases: 3 })); line += ' *saved'; }
    }
    console.log(line);
  }
  process.exit(0);
})();
