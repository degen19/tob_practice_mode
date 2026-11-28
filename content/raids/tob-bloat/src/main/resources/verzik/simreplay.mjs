// verzikSim's raids as replays the server plays like Blert's (VerzikReplayRoom): what the sim decides - every attack
// (who, what, when, its damage, its target), her attacks, her hitpoints, the phase ticks, the crabs it models - with
// where everyone stands taken from a Blert raid of the same scale, time-warped phase by phase onto the sim's phases
// (players' tiles and overheads, her P3 walk, the pillars, nylocas and tornadoes the sim doesn't model).
// node simreplay.mjs <blert.json.gz> <defs dir> <out.json.gz> <scale>=<verzikN.json.gz> ...
import { readFileSync, writeFileSync } from 'node:fs';
import { gunzipSync, gzipSync } from 'node:zlib';

const [blertFile, defsDir, outFile, ...inputs] = process.argv.slice(2);
const blert = JSON.parse(gunzipSync(readFileSync(blertFile)));
const defs = Object.fromEntries(JSON.parse(readFileSync(`${defsDir}/attack_definitions.json`, 'utf8')).map((a) => [a.name, a]));

const P1 = 8370, T12 = 8371, P2 = 8372, T23 = 8373, P3 = 8374, BAT = 8375, RED = 8385, PURPLE = 8384, PILLAR = 8379;
const P1_HP = { 2: 1500, 3: 1500, 4: 1750, 5: 2000 };
const P2_HP = { 2: 2625, 3: 2625, 4: 3062, 5: 3500 };
const PURPLE_HP = 180, PURPLE_FLIGHT = 6, RED_POP = 40, P1_DEATH_GAP = 4, P2_DEATH_GAP = 2, BAT_GAP = 2;
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

function convert(sim, tpl, scale) {
  const end = sim.end ?? Math.max(...sim.hp.map((h) => h[0]), ...sim.atk.map((a) => a[3] ?? a[0])) + 1;
  const sp = { p1dies: at(sim, 'P1 dies'), p2: at(sim, 'P2'), reds: at(sim, 'Reds') ?? null, p2dies: at(sim, 'P2 dies') ?? null, p3: at(sim, 'P3') ?? null };
  const bp = blertPhases(tpl);
  const w = warp(sp, bp, end);
  const n = tpl.players;
  const tiles = Array.from({ length: n }, (_, p) => { const row = []; for (let t = 0; t <= end; t++) { const k = Math.min(w(t), tpl.end); row.push(tpl.tiles[p][2 * k], tpl.tiles[p][2 * k + 1]); } return row; });
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
    const hp = form === T12 || form === BAT ? 0 : h ? Math.max(0, h[1]) : base;
    const row = [t, form, x, y, Math.min(hp, base), base];
    if (!her.length || her.at(-1).slice(1).join() !== row.slice(1).join()) her.push(row);
  }
  const npcs = [{ spawn: 0, death: null, track: her }];
  const index = { '': 0 };

  // what the sim doesn't model, warped from the template: pillars, nylocas, tornadoes
  const back = (k) => { let t = 0; while (t < end && w(t + 1) <= k) t++; return t; };
  for (const x of tpl.npcs) {
    const type = x.track[0][1];
    if ([P1, P2, P3, T12, T23, BAT, 8369, 14796, RED, PURPLE].includes(type)) continue;
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
    if (sim.atk.some((a) => a[5] === 'W') || scale >= 3) crab('W', RED, sp.reds, west.track[0].slice(2, 4), crabHp, sp.reds + RED_POP);
    if (sim.atk.some((a) => a[5] === 'E') || scale >= 3) crab('E', RED, sp.reds, east.track[0].slice(2, 4), crabHp, sp.reds + RED_POP);
  }
  const throw_ = sim.boss.find((b) => b[1] === 'Purple crab');
  if (throw_) {
    const tplP = tpl.npcs.find((x) => x.track[0][1] === PURPLE);
    crab('P', PURPLE, throw_[0] + PURPLE_FLIGHT, tplP ? tplP.track[0].slice(2, 4) : [hx + 3, 4316], PURPLE_HP, sp.reds ?? end);
  }

  // their attacks and gear: the sim's attacks, each in the weapon it's made with
  const scythe = tpl.gear.map((g) => g.find((e) => e[1] === 4 && SCYTHES.has(e[2]))?.[2] ?? 22325);
  const gear = tpl.gear.map((g) => g.filter((e) => e[0] === 0));
  const wielding = gear.map((g) => g.find((e) => e[1] === 4)?.[2] ?? -1);
  const attacks = [], dawns = [];
  for (const [t, i, code, , dmg, key] of [...sim.atk].sort((a, b) => a[0] - b[0])) {
    const c = code === 'C' && t < sp.p2 ? CODES.Cs : CODES[code];
    if (!c || i >= n) continue;
    const [name, weapon0] = c;
    const weapon = weapon0 ?? scythe[i];
    if (wielding[i] !== weapon) { gear[i].push([t, 4, weapon]); gear[i].push([t, 6, -1]); wielding[i] = weapon; }
    attacks.push([t, i, name, defs[name]?.animationIds?.[0] ?? -1, weapon, index[key] ?? 0]);
    if (name === 'DAWN_SPEC') dawns.push([t, i, dmg]);
  }
  const herAttacks = [];
  for (const [t, kind, target] of sim.boss) {
    const phase = t < sp.p2 ? 'P1' : sp.p3 != null && t >= sp.p3 ? 'P3' : 'P2';
    const name = HER[phase][kind];
    if (name) herAttacks.push([t, 0, name, target]);
  }
  const webs = at(sim, 'Webs');
  if (webs != null) herAttacks.push([webs, 0, 'P3_WEBS', -1]);
  herAttacks.sort((a, b) => a[0] - b[0]);
  const marks = [[sp.p2, 'P2']];
  if (sp.p3 != null) marks.push([sp.p3, 'P3']);
  return {
    uuid: `sim-${sim.seed}`, scale, end, players: n, tiles, hp: Array.from({ length: n }, () => Array(end + 1).fill(-1)), gear, prayers,
    drops: [], dawns, yellows: [], npcs, attacks, spells: [], her: herAttacks, marks, failed: sim.end == null,
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
