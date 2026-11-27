'use strict';
// Evolution strategies (OpenAI-style, antithetic, rank-shaped) over the linear policy's weights, two worker threads.
const { Worker, isMainThread, parentPort, workerData } = require('worker_threads');
const fs = require('fs');
const path = require('path');
if (!isMainThread) {
  const { Room } = require('./sim');
  const { LinearPolicy } = require('./policy');
  parentPort.on('message', (job) => {
    const out = job.cands.map((flat) => {
      const pol = new LinearPolicy(unflat(Float64Array.from(flat), job.nf));
      let gone = 0, boss = 0, stalls = 0;
      for (const s of job.seeds) { const r = new Room(s, pol).run(); gone += r.gone < 0 ? 400 : r.gone; boss += r.boss; stalls += r.stalls; }
      return { gone: gone / job.seeds.length, boss: boss / job.seeds.length, stalls: stalls / job.seeds.length };
    });
    parentPort.postMessage(out);
  });
  return;
}
function unflat(flat, nf) { // nf: one role-and-phase block; G0 G1 G2 R0 R1 R2 M0 M1 M2
  const o = {}; let k = 0;
  for (const r of ['G', 'R', 'M']) for (let ph = 0; ph < 3; ph++) { o[r + ph] = flat.subarray(k * nf, (k + 1) * nf); k++; }
  return o;
}
module.exports = { unflat };
