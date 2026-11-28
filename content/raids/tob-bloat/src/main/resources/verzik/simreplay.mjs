// verzikSim's raids as replays the server plays like Blert's (VerzikReplayRoom): what the sim decides - every attack
// (who, what, when, its damage, its target), her attacks, her hitpoints, the phase ticks, the crabs it models, the
// players' hitpoints, what they drink and eat, their spec transfers - played out where Blert's teams stand. A Blert raid
// of the same scale, time-warped phase by phase onto the sim's, gives each player's spots (P1 tile, P2 swing and rest
// tiles, their place round her in P3), her P3 walk, the pillars, nylocas and tornadoes the sim doesn't model; the moves
// follow the sim's attacks: in P2 a player steps in to swing and straight back out (her bounce), at reds to their crab,
// in P3 beside her to swing. The Dawnbringer goes down where its holder stands, the next holder already on that tile.
// node simreplay.mjs <blert.json.gz> <defs dir> <out.json.gz> <scale>=<verzikN.json.gz> ...
import { readFileSync, writeFileSync } from 'node:fs';
import { gunzipSync, gzipSync } from 'node:zlib';

const [blertFile, defsDir, outFile, ...inputs] = process.argv.slice(2);
const blert = JSON.parse(gunzipSync(readFileSync(blertFile)));
const defs = Object.fromEntries(JSON.parse(readFileSync(`${defsDir}/attack_definitions.json`, 'utf8')).map((a) => [a.name, a]));

const P1 = 8370, T12 = 8371, P2 = 8372, T23 = 8373, P3 = 8374, BAT = 8375, RED = 8385, PURPLE = 8384, PILLAR = 8379;
const P1_HP = { 2: 1500, 3: 1500, 4: 1750, 5: 2000 };
const P2_HP = { 2: 2625, 3: 2625, 4: 3062, 5: 3500 };
const PURPLE_HP = 180, PURPLE_FLIGHT = 6, P1_DEATH_GAP = 4, P2_DEATH_GAP = 2, BAT_GAP = 2;
// sim code -> Blert attack name and weapon
const CODES = {
  S: ['SCYTHE', null], '3t': ['EYE_OF_AYAK_AUTO', 31113], D: ['DAWN_SPEC', 22516], Daw: ['DAWN_AUTO', 22516], A: ['DAWN_AUTO', 22516],
  E: ['EYE_OF_AYAK_AUTO', 31113], BP: ['BLOWPIPE', 12926], B: ['BLOWPIPE', 12926], C: ['CLAW_SPEC', 13652], Cs: ['CLAW_SCRATCH', 13652],
  Ch: ['CHALLY_SPEC', 23987], Sh: ['SHADOW', 27275], Z: ['ZCB_SPEC', 26374], T: ['SANG', 22323], SuB: ['SULPHUR_BLADES', 29084],
  EM: ['ELDER_MAUL', 21003], Zt: ['ZORYAS_TOME_AUTO', 30634],
};
const HER = {
  P1: { Attack: 'P1_AUTO' },
  P2: { Attack: 'P2_CABBAGE', Lightning: 'P2_ZAP', 'Purple crab': 'P2_PURPLE', 'Blood heal': 'P2_MAGE' },
  P3: { Ranged: 'P3_RANGE', Magic: 'P3_MAGE', Melee: 'P3_MELEE', 'Green ball': 'P3_BALL' },
};
const SCYTHES = new Set([22325, 25736, 25739]);

/** The middle tile beside each side of a footprint, south, west, north, east (her turn round during webs). */
const SIDES = [
  (f) => [f.x + (f.size >> 1), f.y - 1],
  (f) => [f.x - 1, f.y + (f.size >> 1)],
  (f) => [f.x + (f.size >> 1), f.y + f.size],
  (f) => [f.x + f.size, f.y + (f.size >> 1)],
];

/** Which way round her a template player went during webs: 1 south-west-north-east, -1 the other way. */
function webTurn(tpl, p, from) {
  const v = tpl.npcs.find((x) => x.track.some((s) => s[1] === P3)).track;
  const sideOf = (t) => {
    let f = v[0]; for (const s of v) { if (s[0] > t) break; f = s; }
    const dx = tpl.tiles[p][2 * t] - f[2], dy = tpl.tiles[p][2 * t + 1] - f[3];
    return dy < 0 ? 0 : dx < 0 ? 1 : dy > 6 ? 2 : dx > 6 ? 3 : -1;
  };
  let turn = 0, last = -1;
  for (const a of tpl.attacks) {
    if (a[1] !== p || a[0] < from || a[0] > from + WEB_TICKS) continue;
    const s = sideOf(a[0]);
    if (s < 0) continue;
    if (last >= 0 && s !== last) turn += ((s - last + 4) % 4) === 1 ? 1 : ((s - last + 4) % 4) === 3 ? -1 : 0;
    last = s;
  }
  return turn < 0 ? -1 : 1;
}

const NYLOS = new Set([8381, 8382, 8383]);
const HER_SWINGS = new Set(['Attack', 'Lightning', 'Purple crab', 'Blood heal']);
const PURPLE_WINDOW = 16, LEAD = 2, WEB_TICKS = 44;
const MELEE = new Set(['SCYTHE', 'CLAW_SCRATCH', 'CLAW_SPEC', 'CHALLY_SPEC', 'ELDER_MAUL', 'SULPHUR_BLADES']);
const DAWN_DROP_AFTER = 2, DAWN_PICK_AFTER = 1, TRANSFER_ANIM = 4411, TRANSFER_GFX = 734;

/** The tile beside a footprint (not diagonal) nearest [from]. */
function beside(from, f) {
  let best = null, bd = Infinity;
  for (let k = 0; k < f.size; k++) {
    for (const [x, y] of [[f.x + k, f.y - 1], [f.x + k, f.y + f.size], [f.x - 1, f.y + k], [f.x + f.size, f.y + k]]) {
      const d = Math.max(Math.abs(x - from[0]), Math.abs(y - from[1]));
      if (d < bd) { bd = d; best = [x, y]; }
    }
  }
  return best;
}

/** Whether a melee swing from [tile] reaches a footprint: beside it, not diagonal (a halberd: two tiles). */
function reaches(tile, f, halberd) {
  const dx = tile[0] < f.x ? f.x - tile[0] : tile[0] >= f.x + f.size ? tile[0] - (f.x + f.size - 1) : 0;
  const dy = tile[1] < f.y ? f.y - tile[1] : tile[1] >= f.y + f.size ? tile[1] - (f.y + f.size - 1) : 0;
  return halberd ? dx + dy > 0 && Math.max(dx, dy) <= 2 : dx + dy === 1;
}

/** One tile further out from a footprint than [tile] (out of her reach). */
function away(tile, f) {
  const cx = f.x + (f.size - 1) / 2, cy = f.y + (f.size - 1) / 2;
  const dx = Math.abs(tile[0] - cx) >= Math.abs(tile[1] - cy) ? Math.sign(tile[0] - cx) : 0;
  const dy = dx === 0 ? Math.sign(tile[1] - cy) : 0;
  return [tile[0] + dx, tile[1] + dy];
}

const firstOf = (track, type) => track.find((s) => s[1] === type)?.[0];
const at = (x, k) => x.marks.find((m) => m[1] === k)?.[0];

/** A Blert raid's phase ticks, in the order the sim's are taken. */
function blertPhases(r) {
  const v = r.npcs.find((n) => n.track.some((s) => s[1] === P2)).track;
  const reds = Math.min(...r.npcs.filter((n) => n.track[0][1] === RED).map((n) => n.spawn));
  return { p1dies: firstOf(v, T12) - P1_DEATH_GAP, p2: firstOf(v, P2), reds: Number.isFinite(reds) ? reds : null, p2dies: firstOf(v, T23) - P2_DEATH_GAP, p3: firstOf(v, P3), end: r.end };
}

/** Piecewise-linear sim tick -> template tick through the phase ticks both have. */
function warp(simP, blertP, simEnd) {
  const keys = ['p1dies', 'p2', 'reds', 'p2dies', 'p3'];
  const xs = [0], ys = [0];
  for (const k of keys) if (simP[k] != null && blertP[k] != null && simP[k] > xs.at(-1) && blertP[k] > ys.at(-1)) { xs.push(simP[k]); ys.push(blertP[k]); }
  if (simEnd > xs.at(-1)) { xs.push(simEnd); ys.push(Math.max(ys.at(-1) + 1, Math.min(blertP.end, ys.at(-1) + (simEnd - xs.at(-2) || 1) * ((blertP.end - ys.at(-1)) / Math.max(1, blertP.end - ys.at(-1)))))); }
  return (t) => {
    if (t <= 0) return 0;
    for (let k = 1; k < xs.length; k++) if (t <= xs[k]) return Math.round(ys[k - 1] + (t - xs[k - 1]) * (ys[k] - ys[k - 1]) / Math.max(1, xs[k] - xs[k - 1]));
    return Math.min(blertP.end, Math.round(ys.at(-1) + (t - xs.at(-1))));
  };
}

/** The template with its players reordered: its P3 tank and its purple blowpiper as the sim's tank and purple DC. */
function remap(tpl, sim) {
  const n = tpl.players, perm = [...Array(n).keys()];
  const modeOf = (xs) => { const c = new Map(); for (const x of xs) c.set(x, (c.get(x) || 0) + 1); return [...c.entries()].sort((a, b) => b[1] - a[1])[0]?.[0]; };
  const purpleIdx = new Set(tpl.npcs.map((x, i) => (x.track[0][1] === PURPLE ? i : -1)).filter((i) => i >= 0));
  const tplTank = modeOf(tpl.her.filter((h) => h[2].startsWith('P3_') && h[3] >= 0).map((h) => h[3]));
  const tplPipe = modeOf(tpl.attacks.filter((a) => a[2] === 'BLOWPIPE' && purpleIdx.has(a[5])).map((a) => a[1]));
  const put = (simIdx, tplIdx) => {
    if (simIdx == null || simIdx < 0 || tplIdx == null || simIdx >= n) return;
    const at = perm.indexOf(tplIdx);
    [perm[simIdx], perm[at]] = [perm[at], perm[simIdx]];
  };
  put(sim.tank, tplTank);
  if (sim.purpleDc !== sim.tank) put(sim.purpleDc, tplPipe);
  const inv = perm.map((_, k) => perm.indexOf(k));
  return {
    ...tpl,
    tiles: perm.map((k) => tpl.tiles[k]), prayers: perm.map((k) => tpl.prayers[k]), gear: perm.map((k) => tpl.gear[k]),
    attacks: tpl.attacks.map((a) => [a[0], inv[a[1]], ...a.slice(2)]),
    her: tpl.her.map((h) => [h[0], h[1], h[2], h[3] >= 0 && h[3] < n ? inv[h[3]] : h[3]]),
  };
}

function convert(sim, tpl0, scale) {
  const tpl = remap(tpl0, sim);
  const end = sim.end ?? Math.max(...sim.hp.map((h) => h[0]), ...sim.atk.map((a) => a[3] ?? a[0])) + 1;
  const sp = { p1dies: at(sim, 'P1 dies'), p2: at(sim, 'P2'), reds: at(sim, 'Reds') ?? null, p2dies: at(sim, 'P2 dies') ?? null, p3: at(sim, 'P3') ?? null };
  const bp = blertPhases(tpl);
  const w = warp(sp, bp, end);
  const n = tpl.players;
  const warped = Array.from({ length: n }, (_, p) => Array.from({ length: end + 1 }, (_, t) => { const k = Math.min(w(t), tpl.end); return [tpl.tiles[p][2 * k], tpl.tiles[p][2 * k + 1]]; }));
  const prayers = tpl.prayers.map((list) => { const out = []; for (let t = 0; t <= end; t++) { const k = w(t); const h = list.filter((e) => e[0] <= k).at(-1)?.[1] ?? ''; if (!out.length || out.at(-1)[1] !== h) out.push([t, h]); } return out; });
  // her: one NPC through her forms, on the template's tiles, with the sim's hitpoints
  const vt = tpl.npcs.find((x) => x.track.some((s) => s[1] === P2)).track;
  const herTile = (t) => { const k = w(t); let s = vt[0]; for (const r of vt) { if (r[0] > k) break; s = r; } return [s[2], s[3]]; };
  const hpAt = (t) => { let h = null; for (const r of sim.hp) { if (r[0] > t) break; h = r; } return h; };
  const formAt = (t) => {
    if (t < sp.p1dies + P1_DEATH_GAP) return P1;
    if (t < sp.p2) return T12;
    if (sp.p2dies == null || t < sp.p2dies + P2_DEATH_GAP) return P2;
    if (sp.p3 == null || t < sp.p3) return T23;
    if (sim.end != null && t >= sim.end + BAT_GAP) return BAT;
    return P3;
  };
  const her = [];
  for (let t = 0; t <= end + (sim.end != null ? BAT_GAP : 0); t++) {
    const form = formAt(t), [x, y] = herTile(t), h = hpAt(t);
    const base = form === P1 ? P1_HP[scale] : form === T12 ? 0 : P2_HP[scale];
    const later = h && ((form === P1 && h[2] !== 'P1') || (form === P2 && h[2] === 'P3'));
    const hp = form === T12 || form === BAT || later ? 0 : h ? Math.max(0, h[1]) : base;
    const row = [t, form, x, y, Math.min(hp, base), base];
    if (!her.length || her.at(-1).slice(1).join() !== row.slice(1).join()) her.push(row);
  }
  const npcs = [{ spawn: 0, death: null, track: her }];
  const index = { '': 0 };

  // her purple and what comes with it, tick for tick as the template played it: the nylocas she sends with it and
  // where the players go for it (Boak, dodging, the blowpipe)
  const simThrow = sim.boss.find((b) => b[1] === 'Purple crab')?.[0];
  const tplThrow = tpl.her.find((h) => h[2] === 'P2_PURPLE')?.[0];
  const window = (() => {
    if (simThrow == null || tplThrow == null) return null;
    let last = tplThrow + PURPLE_WINDOW;
    for (const x of tpl.npcs) {
      const type = x.track[0][1];
      if (x.spawn >= tplThrow && x.spawn <= tplThrow + PURPLE_WINDOW && (type === PURPLE || NYLOS.has(type)) && x.death != null) last = Math.max(last, x.death + 1);
    }
    const len = Math.min(last - tplThrow, (sp.reds ?? sp.p2dies ?? end) - simThrow);
    return len > 0 ? { from: simThrow, to: simThrow + len, shift: simThrow - tplThrow } : null;
  })();
  const inWindow = (k) => window && k >= window.from - window.shift && k < window.to - window.shift;

  // what the sim doesn't model, warped from the template: pillars, nylocas, tornadoes
  const back = (k) => { let t = 0; while (t < end && w(t + 1) <= k) t++; return t; };
  for (const x of tpl.npcs) {
    const type = x.track[0][1];
    if ([P1, P2, P3, T12, T23, BAT, 8369, 14796, RED, PURPLE].includes(type)) continue;
    if (inWindow(x.spawn)) {
      const sh = window.shift, death = x.death == null ? window.to : Math.min(end, x.death + sh);
      npcs.push({ spawn: x.spawn + sh, death, track: x.track.map((s) => [s[0] + sh, ...s.slice(1)]).filter((s) => s[0] <= death) });
      continue;
    }
    const spawn = back(x.spawn);
    if (spawn >= end) continue;
    const track = x.track.map((s) => [Math.max(spawn, back(s[0])), ...s.slice(1)]).filter((s, i, a) => i === 0 || s[0] !== a[i - 1][0]);
    npcs.push({ spawn, death: x.death == null ? null : Math.min(end, Math.max(spawn + 1, back(x.death))), track });
  }

  // the crabs the sim models: reds (west / east) and the purple, their hitpoints from the sim's hits
  const hits = (key) => sim.atk.filter((a) => a[5] === key && a[3] != null).map((a) => [a[3], a[4]]).sort((a, b) => a[0] - b[0]);
  const crab = (key, type, spawnT, tile, hp0, popAt) => {
    const hs = hits(key).filter((h) => h[0] >= spawnT);
    let hp = hp0, death = popAt;
    const track = [[spawnT, type, tile[0], tile[1], hp, hp0]];
    for (const [t, d] of hs) { hp = Math.max(0, hp - d); track.push([t, type, tile[0], tile[1], hp, hp0]); if (hp === 0) { death = t; break; } }
    index[key] = npcs.length;
    npcs.push({ spawn: spawnT, death: Math.min(death, end), track });
  };
  const tplRed = tpl.npcs.filter((x) => x.track[0][1] === RED).sort((a, b) => a.spawn - b.spawn || a.track[0][2] - b.track[0][2]);
  const hx = vt.find((s) => s[1] === P2)[2] + 1;
  if (sp.reds != null) {
    const crabHp = Math.floor(200 * P2_HP[scale] / 3500);
    const west = tplRed.find((x) => x.track[0][2] < hx) ?? { track: [[0, RED, hx - 4, 4314]] };
    const east = tplRed.find((x) => x.track[0][2] >= hx) ?? { track: [[0, RED, hx + 4, 4314]] };
    const popAt = sp.p2dies != null ? sp.p2dies + P2_DEATH_GAP : end;
    if (sim.atk.some((a) => a[5] === 'W') || scale >= 3) crab('W', RED, sp.reds, west.track[0].slice(2, 4), crabHp, popAt);
    if (sim.atk.some((a) => a[5] === 'E') || scale >= 3) crab('E', RED, sp.reds, east.track[0].slice(2, 4), crabHp, popAt);
  }
  const throw_ = sim.boss.find((b) => b[1] === 'Purple crab');
  if (throw_) {
    const tplP = tpl.npcs.find((x) => x.track[0][1] === PURPLE);
    crab('P', PURPLE, throw_[0] + PURPLE_FLIGHT, tplP ? tplP.track[0].slice(2, 4) : [hx + 3, 4316], PURPLE_HP, sp.reds ?? end);
  }

  // their attacks and gear: the sim's attacks, each in the weapon it's made with
  const scythe = tpl.gear.map((g) => g.find((e) => e[1] === 4 && SCYTHES.has(e[2]))?.[2] ?? 22325);
  const gear = tpl.gear.map((g) => g.filter((e) => e[0] === 0 && e[1] !== 4));
  const wielding = Array(n).fill(-1);
  const wield = (t, i, weapon) => { if (wielding[i] === weapon) return; gear[i].push([t, 4, weapon]); gear[i].push([t, 6, -1]); wielding[i] = weapon; };
  const attacks = [], dawns = [];
  const acts = [...sim.atk].filter((a) => a[1] < n).sort((a, b) => a[0] - b[0]).map(([t, i, code, , dmg, key]) => {
    const c = code === 'C' && t < sp.p2 ? CODES.Cs : CODES[code];
    return c ? { t, i, name: c[0], weapon: c[1] ?? scythe[i], dmg, key } : null;
  }).filter(Boolean);

  // the Dawnbringer: each holder's run of specs, handed over on the floor where the first holder stands
  const dawnRuns = [];
  for (const a of acts) if (a.t < sp.p1dies && (a.name === 'DAWN_SPEC' || a.name === 'DAWN_AUTO')) {
    const run = dawnRuns.at(-1);
    if (run && run.i === a.i) run.last = a.t; else dawnRuns.push({ i: a.i, first: a.t, last: a.t });
  }
  const drops = [];
  dawnRuns.forEach((run, k) => {
    const next = dawnRuns[k + 1];
    run.from = k === 0 ? 0 : run.pick;
    if (next) {
      run.drop = Math.min(run.last + DAWN_DROP_AFTER, next.first - 2);
      next.pick = Math.max(run.drop + 1, Math.min(next.first - 1, run.drop + DAWN_PICK_AFTER));
    } else run.drop = sp.p1dies;
  });

  // where each player stands: their spots from the template, and the moves the sim's attacks call for
  const mode = (p, from, to, pick = () => true) => {
    const c = new Map();
    for (let k = Math.max(0, from); k < Math.min(to, tpl.end); k++) if (pick(k)) { const key = `${tpl.tiles[p][2 * k]},${tpl.tiles[p][2 * k + 1]}`; c.set(key, (c.get(key) || 0) + 1); }
    const best = [...c.entries()].sort((a, b) => b[1] - a[1])[0];
    return best ? best[0].split(',').map(Number) : null;
  };
  const tplSwings = (p) => new Set(tpl.attacks.filter((a) => a[1] === p).map((a) => a[0]));
  const desired = Array.from({ length: n }, (_, p) => warped[p].map((xy) => [...xy]));
  const melee = (name) => MELEE.has(name);
  const footprint = (t) => { const [x, y] = herTile(t); const form = formAt(t); return { x, y, size: form === P1 || form === T12 ? 5 : form === P2 || form === BAT ? 3 : 7 }; };
  const crabFoot = (key, t) => { const c = npcs[index[key]]; if (!c) return null; const s0 = c.track[0]; return { x: s0[2], y: s0[3], size: s0[1] === PURPLE ? 3 : 2 }; };
  const herHits = new Set(sim.boss.filter((b) => HER_SWINGS.has(b[1])).map((b) => b[0]));
  const dawnTile = dawnRuns.length ? [footprint(0).x + 2, footprint(0).y - 1] : null;
  for (let p = 0; p < n; p++) {
    const swings = tplSwings(p);
    const mine = acts.filter((a) => a.i === p);
    // P1: their tile, off Verzik's centre tile unless they still have a Dawnbringer turn to come
    const f1 = footprint(0);
    let base1 = mode(p, 1, bp.p1dies) ?? warped[p][0];
    if (mine.some((a) => a.t < sp.p1dies && melee(a.name)) && !reaches(base1, f1, false)) base1 = beside(base1, f1);
    if (dawnTile && base1[0] === dawnTile[0] && base1[1] === dawnTile[1]) base1 = beside([dawnTile[0] - 1, dawnTile[1]], f1);
    const lastDawn = dawnRuns.filter((r) => r.i === p).reduce((m, r) => Math.max(m, r.drop), -1);
    for (let t = 0; t < Math.min(sp.p1dies, end + 1); t++) desired[p][t] = t <= lastDawn ? [...dawnTile] : [...base1];
    // P2 before reds: beside her, a step out only on the tick she attacks (her bounce) unless they swing on it
    const f2 = { x: vt.find((r) => r[1] === P2)[2], y: vt.find((r) => r[1] === P2)[3], size: 3 };
    const near2 = mode(p, bp.p2, bp.reds ?? bp.p2dies, (k) => swings.has(k)) ?? mode(p, bp.p2, bp.reds ?? bp.p2dies) ?? [f2.x + 1, f2.y - 2];
    const swing2 = beside(near2, f2), rest2 = away(swing2, f2);
    const p2end = sp.reds ?? sp.p2dies ?? end + 1;
    const swingsAt = new Set(mine.map((a) => a.t));
    for (let t = sp.p2; t < Math.min(p2end, end + 1); t++) desired[p][t] = herHits.has(t) && !swingsAt.has(t) ? [...rest2] : [...swing2];
    if (window) for (let t = window.from; t < Math.min(window.to, end + 1); t++) {
      const k = t - window.shift;
      if (k >= 0 && k <= tpl.end) desired[p][t] = [tpl.tiles[p][2 * k], tpl.tiles[p][2 * k + 1]];
    }
    // reds: beside what they swing at next
    if (sp.reds != null) {
      const redsEnd = sp.p2dies ?? end + 1;
      let at = [...rest2];
      const swingsIn = mine.filter((a) => a.t >= sp.reds && a.t < redsEnd);
      for (let t = sp.reds; t < Math.min(redsEnd, end + 1); t++) {
        const a = swingsIn.find((x) => x.t >= t);
        if (a && melee(a.name)) at = beside(at, a.key ? crabFoot(a.key, a.t) ?? f2 : f2);
        desired[p][t] = [...at];
      }
    }
    // P3: their place round her (as the template stood relative to her), beside her on a melee swing
    if (sp.p3 != null) {
      for (let t = sp.p2dies ?? sp.p3; t <= end; t++) {
        const k = Math.min(w(t), tpl.end), [hx3, hy3] = herTile(t);
        let vk = vt[0]; for (const r of vt) { if (r[0] > k) break; vk = r; }
        desired[p][t] = [hx3 + tpl.tiles[p][2 * k] - vk[2], hy3 + tpl.tiles[p][2 * k + 1] - vk[3]];
      }
      for (const a of mine) if (a.t >= sp.p3 && a.t <= end && melee(a.name)) desired[p][a.t] = beside(desired[p][a.t], footprint(a.t));
    }
  }
  // webs: each swing from the middle tile of a side of her, straight off round to the next side's middle after it
  // (Blert: 3,324 of 3,515 web swings from a side's middle, 5 ticks apart, mostly south-west-north-east)
  const websAt = at(sim, 'Webs');
  const spinning = (t) => websAt != null && t >= websAt && t <= websAt + WEB_TICKS;
  if (websAt != null) {
    const tplWebs = tpl.her.find((h) => h[2] === 'P3_WEBS')?.[0];
    for (let p = 0; p < n; p++) {
      const swings = acts.filter((a) => a.i === p && melee(a.name) && !a.key && spinning(a.t) && a.t <= end);
      if (!swings.length) continue;
      const f = footprint(swings[0].t);
      const mid = (side) => SIDES[side](f);
      const [x0, y0] = desired[p][websAt];
      let at0 = 0, bd = Infinity;
      SIDES.forEach((_, k) => { const [x, y] = mid(k); const d = Math.abs(x - x0) + Math.abs(y - y0); if (d < bd) { bd = d; at0 = k; } });
      const dir = tplWebs != null ? webTurn(tpl, p, tplWebs) : 1;
      let k = 0;
      for (let t = websAt; t <= Math.min(end, websAt + WEB_TICKS); t++) {
        while (k < swings.length && swings[k].t < t) k++;
        if (k >= swings.length) break;
        desired[p][t] = mid((((at0 + dir * k) % 4) + 4) % 4);
      }
    }
  }
  // nobody under her (P2 on): a tile inside her goes to the nearest beside her
  for (let p = 0; p < n; p++) for (let t = sp.p1dies; t <= end; t++) {
    const f = footprint(t), [x, y] = desired[p][t];
    if (x >= f.x && x < f.x + f.size && y >= f.y && y < f.y + f.size) desired[p][t] = beside(desired[p][t], f);
  }
  // every melee swing from a tile that reaches what it hits
  for (const a of acts) {
    if (a.t > end || !melee(a.name)) continue;
    const f = a.key ? crabFoot(a.key, a.t) ?? footprint(a.t) : footprint(a.t);
    if (!reaches(desired[a.i][a.t], f, a.name === 'CHALLY_SPEC')) desired[a.i][a.t] = beside(desired[a.i][a.t], f);
  }
  // moves: two tiles a tick at most (running); a tile they must be on (a swing, the Dawnbringer) is set off for early
  // enough to reach it
  const must = desired.map(() => Array(end + 1).fill(false));
  for (const a of acts) if (a.i < n && a.t <= end) must[a.i][a.t] = true;
  for (const run of dawnRuns) for (let t = 0; t <= Math.min(run.drop, sp.p1dies - 1, end); t++) must[run.i][t] = true;
  const reach = desired.map(() => new Map());
  for (const a of acts) if (a.t <= end && melee(a.name)) reach[a.i].set(a.t, [a.key ? crabFoot(a.key, a.t) ?? footprint(a.t) : footprint(a.t), a.name === 'CHALLY_SPEC']);
  desired.forEach((row, p) => {
    for (let t = end; t >= 1; t--) {
      if (!must[p][t]) continue;
      const [x1, y1] = row[t], [x0, y0] = row[t - 1];
      if (Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)) <= LEAD) continue;
      row[t - 1] = [x1 + Math.max(-LEAD, Math.min(LEAD, x0 - x1)), y1 + Math.max(-LEAD, Math.min(LEAD, y0 - y1))];
      must[p][t - 1] = true;
    }
  });
  // each tick's step: the one (of up to two tiles) that ends nearest where they're going, never through her while she
  // spins webs (her tiles block them then)
  const inside = (x, y, f) => x >= f.x && x < f.x + f.size && y >= f.y && y < f.y + f.size;
  const tiles = desired.map((row, p) => {
    const out = [row[0]];
    for (let t = 1; t <= end; t++) {
      const hit = reach[p].get(t);
      const [x0, y0] = out[t - 1], [x1, y1] = row[t];
      const f = spinning(t) && !inside(x0, y0, footprint(t)) ? footprint(t) : null;
      let best = [x0, y0], bd = Infinity;
      for (let dx = -2; dx <= 2; dx++) for (let dy = -2; dy <= 2; dy++) {
        const x = x0 + dx, y = y0 + dy;
        if (f && (inside(x, y, f) || inside(x0 + Math.sign(dx), y0 + Math.sign(dy), f))) continue;
        const d = (hit && !reaches([x, y], hit[0], hit[1]) ? 100000 : 0) + Math.max(Math.abs(x1 - x), Math.abs(y1 - y)) * 100 + (Math.abs(x1 - x) + Math.abs(y1 - y)) * 10 + Math.abs(dx) + Math.abs(dy);
        if (d < bd) { bd = d; best = [x, y]; }
      }
      out.push(best);
    }
    return out.flat();
  });

  // gear: the Dawnbringer from its pick-up to its drop (gone when P1 dies), each attack's weapon
  for (const run of dawnRuns) {
    wield(run.pick ?? 0, run.i, 22516);
    if (run.pick != null) drops.push([run.pick, dawnTile[0], dawnTile[1], 0]);
    const end1 = Math.min(run.drop, sp.p1dies);
    drops.push(...(run.drop < sp.p1dies ? [[run.drop, dawnTile[0], dawnTile[1], 1]] : []));
    gear[run.i].push([end1, 4, scythe[run.i]]);
    wielding[run.i] = scythe[run.i];
  }
  for (const a of acts) {
    if (a.weapon === 22516 && a.t < sp.p1dies) { attacks.push([a.t, a.i, a.name, defs[a.name]?.animationIds?.[0] ?? -1, a.weapon, index[a.key] ?? 0]); if (a.name === 'DAWN_SPEC') dawns.push([a.t, a.i, a.dmg]); continue; }
    wield(a.t, a.i, a.weapon);
    attacks.push([a.t, a.i, a.name, defs[a.name]?.animationIds?.[0] ?? -1, a.weapon, index[a.key] ?? 0]);
  }
  gear.forEach((g) => g.sort((a, b) => a[0] - b[0]));

  // their hitpoints, drinks and food, spec transfers (the sim's log)
  const hp = Array.from({ length: n }, () => Array(end + 1).fill(-1));
  const uses = [], spells = [];
  const last = Array(n).fill(99);
  const byTick = Array.from({ length: n }, () => new Map());
  for (const [t, i, kind, v] of sim.ev ?? []) {
    if (i >= n || t > end) continue;
    if (kind === 'hit' || kind === 'brew' || kind === 'shark') byTick[i].set(t, Math.max(0, v));
    if (kind === 'brew' || kind === 'restore' || kind === 'surge' || kind === 'shark') uses.push([t, i, kind]);
    if (kind === 'transfer' && v >= 0) spells.push([t, i, 'SPEC_TRANSFER', TRANSFER_ANIM, TRANSFER_GFX, v]);
  }
  for (let i = 0; i < n; i++) for (let t = 0; t <= end; t++) { if (byTick[i].has(t)) last[i] = byTick[i].get(t); hp[i][t] = last[i]; }
  const herAttacks = [];
  for (const [t, kind, target] of sim.boss) {
    const phase = t < sp.p2 ? 'P1' : sp.p3 != null && t >= sp.p3 ? 'P3' : 'P2';
    const name = HER[phase][kind];
    if (name) herAttacks.push([t, 0, name, target >= 0 ? target : phase === 'P3' ? sim.tank ?? -1 : target]);
  }
  const webs = at(sim, 'Webs');
  if (webs != null) herAttacks.push([webs, 0, 'P3_WEBS', -1]);
  herAttacks.sort((a, b) => a[0] - b[0]);
  const marks = [[sp.p2, 'P2']];
  if (sp.p3 != null) marks.push([sp.p3, 'P3']);
  return {
    uuid: `sim-${sim.seed}`, scale, end, players: n, tiles, hp, gear, prayers,
    drops: drops.sort((a, b) => a[0] - b[0]), dawns, yellows: [], npcs, attacks, spells, uses, her: herAttacks, marks, failed: sim.end == null,
  };
}

const out = {};
for (const arg of inputs) {
  const [scale, file] = arg.split('=');
  const sims = JSON.parse(gunzipSync(readFileSync(file)));
  const tpls = blert[scale].filter((r) => r.players === Number(scale));
  const raids = sims.map((s, k) => convert(s, tpls[k % tpls.length], Number(scale)));
  out[scale] = raids.sort((a, b) => (a.failed - b.failed) || (a.end - b.end));
  const ok = raids.filter((r) => !r.failed).map((r) => r.end);
  console.log(`${scale}s: ${raids.length} raids, ${ok.length} kills, median ${ok[ok.length >> 1]}`);
}
const blob = gzipSync(JSON.stringify(out), { level: 9 });
writeFileSync(outFile, blob);
console.log(outFile, blob.length, 'bytes');
