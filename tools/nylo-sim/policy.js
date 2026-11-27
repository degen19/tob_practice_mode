'use strict';
const { WEAPONS, WEAPON_NAMES, KITS, ROLES, dist, cheb, overlaps, STAND, CAP_LATE, MOUTHS, WAVES, PREFIRES } = require('./sim');
const DMG = require('./dmg.json');

// P(one hit of a weapon kills hp left), from the measured damage
const KILLP = {};
function killP(w, big, hp) {
  const key = `${w}${big ? 'B' : 's'}${hp}`;
  if (key in KILLP) return KILLP[key];
  const d = (WEAPONS[w].dmg || w) + (big ? 'B' : 's');
  const arr = DMG[d] || DMG[(WEAPONS[w].dmg || w) + 's'] || DMG[(WEAPONS[w].dmg || w) + 'B'] || DMG.crossbowss;
  let k = 0;
  if (WEAPONS[w].hitsplats) { // a spec's hitsplats together: sampled
    let r = 7; const rnd = () => { r = (r * 1103515245 + 12345) & 0x7fffffff; return r / 0x7fffffff; };
    for (let i = 0; i < 4000; i++) { let s = 0; for (let j = 0; j < WEAPONS[w].hitsplats; j++) s += arr[Math.floor(rnd() * arr.length)]; if (s >= hp) k++; }
    return (KILLP[key] = k / 4000);
  }
  for (const v of arr) if ((w === 'scythe' && big ? v + (v >> 1) + (v >> 2) : v) >= hp) k++;
  return (KILLP[key] = k / arr.length);
}

const FEATURES = ['bias', 'own', 'nVic', 'bigVic', 'nWrong', 'big', 'aggro', 'biting', 'life', 'walk', 'swap', 'same', 'stall', 'standsAtCheck', 'cleanup', 'cleanGone', 'speed', 'kills', 'inLane', 'claimed', 'bigSplitStall', 'wave', 'young', 'split', 'crowd', 'mustLast', 'bigLate', 'endRank', 'endGap', 'ownKills', 'lateWave', 'cleanBig', 'aggroLate', 'onlyMe', 'oldness', 'oldLate'];
const NF = FEATURES.length + WEAPON_NAMES.length;
const POST_FEATURES = ['pbias', 'untilWave', 'ownLane', 'allLane', 'bigLane', 'postDist', 'othersThere', 'mine', 'pwave', 'pcrowd'];
const NP = POST_FEATURES.length;
const LANES = ['W', 'S', 'E'];
/** The tile a role covers a lane's mouth from: Blert's for the next wave if it's by that mouth, else the mouth. */
function postTile(room, role, lane) {
  const m = MOUTHS[lane];
  const b = PREFIRES[Math.min(room.wave, PREFIRES.length - 1)][ROLES.indexOf(role)];
  return cheb(b[0], b[1], m[0], m[1]) <= 3 ? b : m;
}

function features(room, p, w, n, vic, wrong, ctx) {
  const W = WEAPONS[w];
  const f = new Float64Array(NF);
  const life = room.lifeLeft(n);
  let kills = 0; for (const v of vic) kills += killP(w, v.big, v.hp - v.pending);
  f[0] = 1;
  f[1] = n.style === p.role ? 1 : 0;
  f[2] = vic.length;
  f[3] = vic.filter((v) => v.big).length;
  f[4] = wrong.length;
  f[5] = n.big ? 1 : 0;
  f[6] = n.aggro ? 1 : 0;
  f[7] = n.biting ? 1 : 0;
  f[8] = Math.max(0, Math.min(1, life / 52));
  f[9] = Math.max(0, dist(p.x, p.z, n) - W.range) / 2;
  f[10] = p.set !== W.set ? 1 : 0;
  f[11] = p.last === n ? 1 : 0;
  f[12] = ctx.pressure;
  f[13] = ctx.pressure > 0 && life > ctx.untilCheck ? 1 : 0;
  f[14] = ctx.cleanup;
  f[15] = ctx.cleanup ? (life + (n.big ? 52 : 0)) / 52 : 0;
  f[16] = W.speed / 5;
  f[17] = kills;
  f[18] = overlaps(n, STAND[0], STAND[2], STAND[1], STAND[3]) ? 0 : 1;
  f[19] = n.pending > 0 ? 1 : 0;
  f[20] = n.big && ctx.untilCheck <= 11 && ctx.count + 1 >= ctx.cap && !ctx.cleanup ? 1 : 0;
  f[21] = n.wave / 31;
  f[22] = room.t - n.born < 14 ? 1 : 0;
  f[23] = n.split ? 1 : 0;
  f[24] = ctx.count / ctx.cap;
  const gone = life + (n.big ? 52 : 0);
  f[25] = ctx.cleanup && gone >= ctx.latest - 2 ? 1 : 0;
  f[26] = n.big && n.wave >= 26 ? 1 : 0;
  f[27] = ctx.ends.length ? ctx.ends.filter((e) => e > gone).length / ctx.ends.length : 0;
  f[28] = (gone - ctx.medEnd) / 52;
  f[29] = f[1] * kills;
  f[30] = room.wave >= 26 ? 1 : 0;
  f[31] = ctx.cleanup && n.big ? 1 : 0;
  f[32] = n.aggro && room.wave >= 26 ? 1 : 0;
  let others = 0; for (let k = 0; k < 3; k++) if (k !== p.i && n.nulled & (1 << k)) others++;
  f[33] = others / 2;
  f[34] = Math.max(0, Math.min(2, (room.wave - n.wave) / 5));
  f[35] = room.wave >= 28 ? f[34] : 0;
  f[FEATURES.length + WEAPON_NAMES.indexOf(w)] = 1;
  return f;
}

function context(room, all) {
  const cleanup = room.wave >= 31 ? 1 : 0;
  const check = room.nextWaveCheck();
  const untilCheck = check - room.t;
  const cap = room.cap();
  const count = room.roomCount();
  let standing = 0; for (const n of all) if (room.lifeLeft(n) > untilCheck) standing++;
  const pressure = cleanup ? 0 : Math.max(0, standing - cap + 1) / 4;
  let latest = 0; const ends = [];
  for (const n of all) { const e = room.lifeLeft(n) + (n.big ? 52 : 0); ends.push(e); latest = Math.max(latest, e); }
  ends.sort((a, b) => a - b);
  const medEnd = ends.length ? ends[ends.length >> 1] : 0;
  return { cleanup, untilCheck, cap, count, pressure, latest, ends, medEnd };
}

/** The part of the room a weight set plays: waves 1-19, 20-27, 28 to the end. */
const PHASES = [0, 20, 28];
function phaseOf(wave) { return wave >= 28 ? 2 : wave >= 20 ? 1 : 0; }

/** A linear scorer per role: weights[role] . features; the best candidate is attacked if it scores above 0. */
class LinearPolicy {
  constructor(weights) { this.w = weights; } // { G: Float64Array(NF + NP), R:..., M:... }
  choose(room, p, all) {
    const bit = 1 << p.i;
    const wanted = all.filter((o) => !(o.nulled & bit) && o.pending < o.hp);
    const wv = this.w[p.role + phaseOf(room.wave)];
    let best = null, bs = 0;
    if (room.wave < WAVES.length) { // covering a lane for the next wave
      const next = WAVES[room.wave].spawns;
      const untilWave = room.nextWaveCheck() - room.t;
      for (const lane of LANES) {
        const sp = next.filter((s) => s.lane === lane);
        if (!sp.length) continue;
        const [x, z] = postTile(room, p.role, lane);
        const f = new Float64Array(NP);
        f[0] = 1; f[1] = untilWave / 4;
        f[2] = sp.filter((s) => s.rot[Math.min(2, s.rot.length - 1)] === p.role).length;
        f[3] = sp.length; f[4] = sp.filter((s) => s.big).length;
        f[5] = cheb(p.x, p.z, x, z) / 5;
        f[6] = room.players.filter((q) => q !== p && q.post === lane).length;
        f[7] = p.post === lane ? 1 : 0;
        f[8] = room.wave / 31; f[9] = room.roomCount() / room.cap();
        let s = 0; for (let i = 0; i < NP; i++) s += wv[NF + i] * f[i];
        if (s > bs) { bs = s; best = { post: lane, x, z }; }
      }
    }
    if (!wanted.length) return best;
    const ctx = context(room, all);
    for (const w of KITS[p.role]) {
      const W = WEAPONS[w];
      if (W.spec && p.spec < W.spec) continue;
      for (const n of wanted) {
        if (n.style !== W.hits) continue;
        if (W.range <= 1 && !room.meleeReach(n)) continue;
        const vic = room.victims(w, n, p, wanted);
        const wrong = room.wrongs(w, n, p, all);
        const f = features(room, p, w, n, vic, wrong, ctx);
        let s = 0; for (let i = 0; i < NF; i++) s += wv[i] * f[i];
        if (s > bs) { bs = s; best = { w, n }; }
      }
    }
    return best;
  }
}

module.exports = { PHASES, phaseOf, LinearPolicy, FEATURES, NF, NP, POST_FEATURES, features, context, killP };
