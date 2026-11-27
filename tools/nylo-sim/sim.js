// A fast copy of NyloSolo (the server's Nylocas room) for learning: same wave table, cap, stalls, lanes, flickers,
// splits, explosions and pillars; the bots' weapons with the server's speeds, ranges, areas and measured damage.
'use strict';
const fs = require('fs');
const path = require('path');
const WAVES = JSON.parse(fs.readFileSync(path.join(__dirname, 'waves.json')));
const PREFIRES = JSON.parse(fs.readFileSync(path.join(__dirname, 'prefires.json')));
const DMG = JSON.parse(fs.readFileSync(path.join(__dirname, 'dmg.json')));

const FIRST_WAVE = 3, CYCLE = 4, CAP_WAVE = 20, CAP_EARLY = 12, CAP_LATE = 24;
const SMALL_HP = 8, BIG_HP = 16, SMALL_LIFE = 52, BIG_LIFE = 53, BIG_BOOM_TICKS = 3, DETONATE_TICKS = 2;
const BIG_DEATH = 6, SMALL_DEATH = 2, NYLO_SPEED = 4, AGGRO_RANGE = 2, LANE_TICKS = 14;
const FLICK_1 = 5, FLICK_2 = 7, FLICK_3 = 26, BOSS_LEAD = 16, FREEZE_TICKS = 33;
const PILLARS0 = [['SW', 25, 18], ['NW', 25, 29], ['SE', 36, 18], ['NE', 36, 29]];
const PILLAR_HP = 260; // 130 x (2 + 5 - 3) / 2, a trio
// local tiles (real - 3264, real - 4224)
const inR = (v, a, b) => v >= a && v <= b;
function nyloTile(x, z) {
  return (inR(z, 24, 25) && (inR(x, 17, 25) || inR(x, 38, 46))) || (inR(x, 31, 32) && inR(z, 9, 18)) ||
    (inR(x, 26, 37) && inR(z, 21, 28)) || (inR(x, 28, 35) && inR(z, 19, 30));
}
const STAND = [26, 37, 19, 30];
const MOUTHS = { W: [26, 24], E: [37, 24], S: [31, 19] };
const DEATH_REMOVE_SMALL = SMALL_DEATH; // a killed small one stops counting, and leaves the room, 2 ticks after it dies (Blert)

const ROLES = ['G', 'R', 'M']; // mage, ranger, melee (style each favours)
const WEAPONS = {
  scythe: { hits: 'M', speed: 5, range: 1, area: 'scythe', set: 'scythe', type: 'melee' },
  smash: { hits: 'M', speed: 3, range: 1, area: 'single', set: 'obligator', dmg: 'obligator', type: 'melee' },
  thrust: { hits: 'M', speed: 5, range: 1, area: 'line', set: 'obligator', dmg: 'obligator', type: 'melee' },
  tbow: { hits: 'R', speed: 5, range: 10, area: 'single', set: 'tbow', dmg: 'crossbows', type: 'ranged' },
  chins: { hits: 'R', speed: 3, range: 9, area: 'square', set: 'chins', type: 'ranged' },
  crossbows: { hits: 'R', speed: 2, range: 7, area: 'single', set: 'crossbows', type: 'ranged' },
  ayak: { hits: 'G', speed: 3, range: 10, area: 'single', set: 'ayak', type: 'magic' },
  tome: { hits: 'G', speed: 3, range: 10, area: 'single', set: 'tome', type: 'magic' },
  barrage: { hits: 'G', speed: 5, range: 10, area: 'square', set: 'barrage', type: 'magic', freeze: true },
  dinh: { hits: 'M', speed: 5, range: 1, area: 'bash', set: 'dinh', type: 'melee', spec: 500 },
  claws: { hits: 'M', speed: 4, range: 1, area: 'single', set: 'claws', type: 'melee', spec: 500, hitsplats: 4 },
};
const WEAPON_NAMES = Object.keys(WEAPONS);
const KITS = {
  G: ['barrage', 'tome', 'crossbows', 'tbow', 'scythe', 'claws'],
  R: ['tbow', 'chins', 'crossbows', 'ayak', 'scythe', 'claws'],
  M: ['scythe', 'smash', 'thrust', 'dinh', 'crossbows', 'ayak', 'tbow'],
};
const AREA_MAX = 9, LINE_TILES = 3, BASH_RADIUS = 5, BASH_MAX = 10;

class Rng {
  constructor(seed) { this.s = (seed >>> 0) || 1; }
  next() { let x = this.s; x ^= x << 13; x >>>= 0; x ^= x >>> 17; x ^= x << 5; x >>>= 0; this.s = x; return x / 4294967296; }
  int(a, b) { return a + Math.floor(this.next() * (b - a + 1)); }
  pick(arr) { return arr[Math.floor(this.next() * arr.length)]; }
}

const sign = (v) => (v > 0 ? 1 : v < 0 ? -1 : 0);
const cheb = (ax, az, bx, bz) => Math.max(Math.abs(ax - bx), Math.abs(az - bz));
function boxDist(x, z, x0, z0, x1, z1) { return Math.max(Math.max(x0 - x, x - x1, 0), Math.max(z0 - z, z - z1, 0)); }
// Kotlin dist(): Chebyshev to the footprint, +1 when standing inside it
function dist(px, pz, n) { const s = n.size; let d = boxDist(px, pz, n.x, n.z, n.x + s - 1, n.z + s - 1); if (px >= n.x && px < n.x + s && pz >= n.z && pz < n.z + s) d += 1; return d; }
const overlaps = (n, x0, z0, x1, z1) => n.x <= x1 && n.x + n.size - 1 >= x0 && n.z <= z1 && n.z + n.size - 1 >= z0;

class Room {
  constructor(seed, policy, opts = {}) {
    this.rng = new Rng(seed);
    this.policy = policy;
    this.opts = opts;
    this.t = 0;
    this.wave = 0;
    this.nextWaveAt = FIRST_WAVE;
    this.nylos = [];
    this.dying = [];
    this.pillars = PILLARS0.map(([name, x, y]) => ({ name, x, y, hp: PILLAR_HP }));
    this.stalls = 0;
    this.bossAt = -1;
    this.lastWaveAt = 0;
    this.hits = []; // {at, nylo, dmg, player, wrong}
    this.nextId = 1;
    this.players = ROLES.map((r, i) => ({ i, role: r, x: 32, z: 30, readyAt: 0, set: KITS[r][0], target: null, spec: 1000, tx: 32, tz: 30 }));
    this.log = opts.log ? [] : null;
    this.stats = opts.stats ? [] : null;
  }
  isWaveTick(t) { return t >= FIRST_WAVE && t % CYCLE === 0; } // the server's wave tick lands on multiples of 4 (wave 1 on t4)
  cap() { return this.wave + 1 >= CAP_WAVE ? CAP_LATE : CAP_EARLY; }
  roomCount() { let c = 0; for (const n of this.nylos) if (n.hp > 0 && !n.gone) c++; for (const d of this.dying) if (d > this.t) c++; return c; }
  nextWaveCheck() { let at = Math.max(this.t, this.nextWaveAt); while (!this.isWaveTick(at)) at++; return at; }
  lastWaveEta() { if (this.wave >= WAVES.length) return this.lastWaveAt; let at = this.nextWaveCheck(); for (let i = this.wave; i < WAVES.length - 1; i++) at += Math.max(WAVES[i].stall, CYCLE); return at; }
  lifeLeft(n) { return (n.big ? BIG_LIFE : SMALL_LIFE) - (this.t - n.born); }

  spawnNylo(s) {
    const n = { id: this.nextId++, x: s.x, z: s.y, big: s.big, size: s.big ? 2 : 1, hp: s.big ? BIG_HP : SMALL_HP, born: this.t, lane: s.lane, rot: s.rot, style: s.rot[0],
      aggro: s.target === 'A', pillar: s.target === 'A' ? null : this.pillars.find((p) => p.name === s.target), wave: this.wave, nulled: 0, pending: 0, diedAt: -1, exploding: -1,
      frozenUntil: -1, nextAttack: 0, biting: false, gone: false, key: `w${this.wave}:${s.x}:${s.y}:${s.big ? 'B' : 's'}`, split: false };
    this.nylos.push(n);
  }
  split(x, z) {
    for (let i = 0; i < 2; i++) {
      const style = 'MRG'[this.rng.int(0, 2)];
      const n = { id: this.nextId++, x: x + i, z: z + i, big: false, size: 1, hp: SMALL_HP, born: this.t, lane: null, rot: style, style, aggro: false, pillar: this.nearestPillar(x + i, z + i),
        wave: this.wave, nulled: 0, pending: 0, diedAt: -1, exploding: -1, frozenUntil: -1, nextAttack: 0, biting: false, gone: false, key: 's', split: true };
      this.nylos.push(n);
    }
  }
  nearestPillar(x, z) { let best = null, bd = 1e9; for (const p of this.pillars) if (p.hp > 0) { const d = Math.abs(p.x + 1 - x) + Math.abs(p.y + 1 - z); if (d < bd) { bd = d; best = p; } } return best; }
  free(x, z, size) {
    for (let dx = 0; dx < size; dx++) for (let dz = 0; dz < size; dz++) if (!nyloTile(x + dx, z + dz)) return false;
    for (const p of this.pillars) if (p.hp > 0 && x + size - 1 >= p.x && x <= p.x + 2 && z + size - 1 >= p.y && z <= p.y + 2) return false;
    return true;
  }
  stepToward(n, tx, tz) {
    const fx = n.x, fz = n.z, dx = sign(tx - fx), dz = sign(tz - fz);
    for (const [ox, oz] of [[fx + dx, fz + dz], [fx + dx, fz], [fx, fz + dz]]) if ((ox !== fx || oz !== fz) && this.free(ox, oz, n.size)) { n.x = ox; n.z = oz; return; }
    const here = cheb(fx, fz, tx, tz);
    let best = null, bs = 1e9;
    for (let x = -1; x <= 1; x++) for (let z = -1; z <= 1; z++) {
      if (!x && !z) continue;
      const cx = fx + x, cz = fz + z;
      if (!this.free(cx, cz, n.size) || cheb(cx, cz, tx, tz) > here) continue;
      const s = cheb(cx, cz, tx, tz) * 2 + Math.abs(cx - tx) + Math.abs(cz - tz);
      if (s < bs) { bs = s; best = [cx, cz]; }
    }
    if (best) { n.x = best[0]; n.z = best[1]; }
  }
  beside(n, p) {
    const c = n, size = n.size;
    const acrossX = c.x + size - 1 >= p.x && c.x <= p.x + 2;
    const acrossZ = c.z + size - 1 >= p.y && c.z <= p.y + 2;
    return (acrossX && (c.z + size === p.y || c.z === p.y + 3)) || (acrossZ && (c.x + size === p.x || c.x === p.x + 3));
  }
  meleeSpots(px, pz, size) {
    const out = [];
    for (let i = 0; i < size; i++) out.push([px - size, pz - i], [px + 1, pz - i], [px - i, pz - size], [px - i, pz + 1]);
    return out;
  }

  waves() {
    if (this.wave >= WAVES.length) return;
    if (this.t < this.nextWaveAt || !this.isWaveTick(this.t)) return;
    if (this.roomCount() >= this.cap()) { this.stalls++; return; }
    const w = WAVES[this.wave];
    this.wave++;
    for (const s of w.spawns) this.spawnNylo(s);
    this.lastWaveAt = this.t;
    if (this.wave === WAVES.length) this.wave31At = this.t;
    this.nextWaveAt = this.t + Math.max(w.stall, CYCLE);
  }

  nylo(n) {
    if (n.gone) return;
    const t = this.t, age = t - n.born;
    if (n.hp <= 0) {
      if (n.diedAt < 0) {
        n.diedAt = t;
        if (this.stats) this.stats.push([n.big ? 'big' : n.split ? 'split' : 'small', 'killed', n.wave, age]);
        if (n.big) { this.dying.push(Infinity); n.splitAt = t + BIG_DEATH; }
        else this.dying.push(t + SMALL_DEATH);
        this.dying = this.dying.filter((d) => d > t);
      }
      if (n.big && t === n.splitAt) { const k = this.dying.indexOf(Infinity); if (k >= 0) this.dying.splice(k, 1); this.split(n.x, n.z); n.gone = true; }
      if (!n.big && t - n.diedAt >= DEATH_REMOVE_SMALL) n.gone = true;
      return;
    }
    const life = n.big ? BIG_LIFE : SMALL_LIFE;
    if (age >= life) {
      if (n.exploding < 0) { n.exploding = t; if (this.stats) this.stats.push([n.big ? 'big' : n.split ? 'split' : 'small', 'expired', n.wave, age]); }
      if (t - n.exploding >= (n.big ? BIG_BOOM_TICKS : DETONATE_TICKS)) { n.gone = true; if (n.big) this.split(n.x, n.z); }
      return;
    }
    if (n.rot.length >= 3 && n.lane) {
      const s = age === FLICK_1 ? n.rot[1] : age === FLICK_2 ? n.rot[2] : age === FLICK_3 ? n.rot[3] : undefined;
      if (s && s !== n.style) n.style = s;
    }
    if (n.frozenUntil > t) return;
    if (n.lane && age < LANE_TICKS) {
      const ex = n.lane === 'W' ? 26 : n.lane === 'S' ? n.x : 37;
      const ez = n.lane === 'S' ? 19 : n.z;
      if (n.x !== ex || n.z !== ez) { this.stepToward(n, ex, ez); return; }
    }
    if (n.aggro) {
      let p = null, pd = 1e9;
      for (const q of this.players) { const d = cheb(n.x, n.z, q.x, q.z); if (d < pd) { pd = d; p = q; } }
      if (!p) return;
      const d = boxDist(p.x, p.z, n.x, n.z, n.x + n.size - 1, n.z + n.size - 1);
      const spots = this.meleeSpots(p.x, p.z, n.size);
      const inPlace = n.style === 'M' ? spots.some(([x, z]) => x === n.x && z === n.z) : d >= 1 && d <= AGGRO_RANGE;
      if (!inPlace) {
        let goal = null;
        if (n.style === 'M' || d === 0) { let bd = 1e9; for (const [x, z] of spots) if (this.free(x, z, n.size)) { const dd = cheb(n.x, n.z, x, z); if (dd < bd) { bd = dd; goal = [x, z]; } } }
        else goal = [p.x, p.z];
        if (goal) this.stepToward(n, goal[0], goal[1]);
      }
      return;
    }
    if (n.pillar && n.pillar.hp <= 0) { n.aggro = true; return; }
    if (!n.pillar) n.pillar = this.nearestPillar(n.x, n.z);
    const pl = n.pillar;
    if (!pl) { n.aggro = true; return; }
    if (!this.beside(n, pl)) { this.stepToward(n, pl.x + 1, pl.y + 1); return; }
    n.biting = true;
    if (t >= n.nextAttack) { n.nextAttack = t + NYLO_SPEED; pl.hp -= this.rng.int(0, 2); }
  }

  land() {
    const keep = [];
    for (const h of this.hits) {
      if (h.at > this.t) { keep.push(h); continue; }
      const n = h.nylo;
      n.pending = Math.max(0, n.pending - h.dmg);
      if (n.hp <= 0 || n.gone || n.exploding >= 0) continue;
      if (h.wrong || n.nulled & (1 << h.player)) continue;
      n.hp -= h.dmg;
    }
    this.hits = keep;
  }

  cleanupEnd() {
    if (this.wave < WAVES.length || this.bossAt >= 0) return;
    if (this.nylos.some((n) => !n.gone) || this.dying.includes(Infinity)) return;
    let at = this.t; while (!this.isWaveTick(at)) at++;
    this.lastGone = this.t;
    this.bossAt = at + BOSS_LEAD;
  }

  step() {
    this.waves();
    for (const n of this.nylos.slice()) this.nylo(n);
    this.nylos = this.nylos.filter((n) => !n.gone);
    for (const p of this.pillars) if (p.hp <= 0 && !p.down) { p.down = true; }
    this.cleanupEnd();
    for (const p of this.players) this.player(p);
    this.land(); // hits land after the room's own step: a kill shows the tick after, as in the server
    if (this.t % 50 === 0) for (const p of this.players) p.spec = Math.min(1000, p.spec + 100);
    this.t++;
  }

  run(maxT = 420) { while (this.bossAt < 0 && this.t < maxT) this.step(); return { boss: this.bossAt < 0 ? maxT + 20 : this.bossAt, stalls: this.stalls, w31: this.wave31At ?? -1, gone: this.lastGone ?? -1, pillars: this.pillars.filter((p) => p.hp > 0).length }; }

  /* ---- players */
  targets() { return this.nylos.filter((n) => !n.gone && n.hp > 0 && n.exploding < 0); }

  meleeReach(n) { return overlaps(n, STAND[0] - 1, STAND[2] - 1, STAND[1] + 1, STAND[3] + 1); }

  victims(w, target, p, wanted) {
    const W = WEAPONS[w];
    switch (W.area) {
      case 'single': return [target];
      case 'scythe': { const arc = this.arc(target, p); return [target, ...wanted.filter((o) => o !== target && o.style === 'M' && arc.some(([x, z]) => overlaps(o, x, z, x, z)))]; }
      case 'bash': return [target, ...wanted.filter((o) => o !== target && o.style === 'M' && dist(p.x, p.z, o) <= BASH_RADIUS).slice(0, BASH_MAX - 1)];
      case 'square': return wanted.filter((o) => o.style === W.hits && overlaps(o, target.x - 1, target.z - 1, target.x + target.size, target.z + target.size)).slice(0, AREA_MAX);
      case 'line': {
        const dx = sign(target.x - p.x), dz = dx !== 0 ? 0 : sign(target.z - p.z);
        const tiles = []; for (let i = 0; i < LINE_TILES; i++) tiles.push([target.x + dx * i, target.z + dz * i]);
        return wanted.filter((o) => o.style === 'M' && tiles.some(([x, z]) => overlaps(o, x, z, x, z))).slice(0, LINE_TILES);
      }
    }
    return [target];
  }
  arc(target, p) { const dx = sign(target.x - p.x), dz = sign(target.z - p.z); return [[target.x + dz, target.z + dx], [target.x - dz, target.z - dx]]; }
  wrongs(w, target, p, all) {
    const W = WEAPONS[w], bit = 1 << p.i;
    const others = all.filter((o) => o !== target && o.style !== W.hits && !(o.nulled & bit) && o.pending < o.hp);
    switch (W.area) {
      case 'single': return [];
      case 'bash': return others.filter((o) => dist(p.x, p.z, o) <= BASH_RADIUS);
      case 'square': return others.filter((o) => overlaps(o, target.x - 1, target.z - 1, target.x + target.size, target.z + target.size));
      default: {
        if (W.area === 'scythe' && target.big) return [];
        const dx = sign(target.x - p.x), dz = sign(target.z - p.z);
        const tiles = W.area === 'line' ? [1, 2].map((i) => [target.x + dx * i, target.z + (dx !== 0 ? 0 : dz * i)]) : this.arc(target, p);
        return others.filter((o) => tiles.some(([x, z]) => overlaps(o, x, z, x, z)));
      }
    }
  }

  /** Open tiles for sight: the lanes, the floor and the standing area, less standing pillars. */
  open(x, z) {
    if (!(nyloTile(x, z) || (x >= STAND[0] && x <= STAND[1] && z >= STAND[2] && z <= STAND[3]))) return false;
    for (const pl of this.pillars) if (pl.hp > 0 && x >= pl.x && x <= pl.x + 2 && z >= pl.y && z <= pl.y + 2) return false;
    return true;
  }
  /** A straight line from the player's tile to the nearest tile of [n] crosses only open tiles. */
  sees(px, pz, n) {
    const tx = Math.max(n.x, Math.min(px, n.x + n.size - 1)), tz = Math.max(n.z, Math.min(pz, n.z + n.size - 1));
    const steps = Math.max(Math.abs(tx - px), Math.abs(tz - pz)) * 3;
    for (let i = 1; i < steps; i++) {
      const fx = px + 0.5 + (tx - px) * i / steps, fz = pz + 0.5 + (tz - pz) * i / steps;
      const x = Math.floor(fx), z = Math.floor(fz);
      if ((x === px && z === pz) || (x === tx && z === tz)) continue;
      if (!this.open(x, z)) return false;
    }
    return true;
  }

  // where the bot attacks [n] from with [w]: its own tile if in reach, else the nearest standing tile in reach
  attackSpot(p, w, n) {
    const W = WEAPONS[w];
    const ok = (x, z) => {
      if (x < STAND[0] || x > STAND[1] || z < STAND[2] || z > STAND[3]) return false;
      for (const pl of this.pillars) if (pl.hp > 0 && x >= pl.x && x <= pl.x + 2 && z >= pl.y && z <= pl.y + 2) return false;
      if (W.range <= 1) { // cardinally beside its footprint
        const inX = x >= n.x && x < n.x + n.size, inZ = z >= n.z && z < n.z + n.size;
        return (inX && (z === n.z - 1 || z === n.z + n.size)) || (inZ && (x === n.x - 1 || x === n.x + n.size));
      }
      const d = dist(x, z, n);
      return d >= 1 && d <= W.range && this.sees(x, z, n);
    };
    if (ok(p.x, p.z)) return [p.x, p.z, 0];
    let best = null, bd = 1e9;
    for (let x = STAND[0]; x <= STAND[1]; x++) for (let z = STAND[2]; z <= STAND[3]; z++) {
      if (!ok(x, z)) continue;
      const d = cheb(p.x, p.z, x, z);
      if (d < bd) { bd = d; best = [x, z, d]; }
    }
    return best;
  }

  sample(w, big) {
    const key = (WEAPONS[w].dmg || w) + (big ? 'B' : 's');
    const arr = DMG[key] || DMG[(WEAPONS[w].dmg || w) + 's'] || DMG[(WEAPONS[w].dmg || w) + 'B'] || DMG['crossbowss'];
    return arr[Math.floor(this.rng.next() * arr.length)];
  }

  hitDelay(w, d) {
    const type = WEAPONS[w].type;
    if (type === 'melee') return 1;
    if (type === 'ranged') return 1 + Math.floor((3 + d) / 6);
    return 1 + Math.floor((1 + d) / 3);
  }

  attack(p, w, n, wanted, all) {
    const W = WEAPONS[w];
    const d = dist(p.x, p.z, n);
    const delay = this.hitDelay(w, d);
    const vic = this.victims(w, n, p, wanted);
    const wrong = this.wrongs(w, n, p, all);
    const bit = 1 << p.i;
    for (const v of vic) {
      if (v.style !== W.hits || v.nulled & bit) continue;
      let dmg;
      if (W.area === 'scythe' && v.big) { const a = this.sample(w, false); dmg = a + Math.floor(a / 2) + Math.floor(a / 4); }
      else if (W.hitsplats) { dmg = 0; for (let k = 0; k < W.hitsplats; k++) dmg += this.sample(w, true); }
      else dmg = this.sample(w, v.big);
      const dealt = Math.min(dmg, v.hp);
      v.pending += dealt;
      this.hits.push({ at: this.t + delay, nylo: v, dmg: dealt, player: p.i, wrong: false });
      if (this.stats) this.stats.push(['hit', p.role]);
    }
    for (const o of wrong) o.nulled |= bit;
    if (W.freeze) for (const o of [...vic, ...wrong].slice(0, AREA_MAX)) o.frozenUntil = this.t + FREEZE_TICKS;
    if (W.spec) p.spec -= W.spec;
    p.set = W.set;
    p.readyAt = this.t + W.speed;
    p.last = n;
    if (this.log) this.log.push(`t${this.t} ${p.role} ${w} w${n.wave}${n.big ? 'B' : ''}${n.style} x${vic.length}`);
  }

  moveToward(p, tx, tz) {
    for (let s = 0; s < 2; s++) {
      if (p.x === tx && p.z === tz) return;
      const nx = p.x + sign(tx - p.x), nz = p.z + sign(tz - p.z);
      p.x = nx; p.z = nz;
    }
  }

  player(p) {
    const t = this.t;
    if (p.plan && (p.plan.n.gone || p.plan.n.hp <= 0 || p.plan.n.exploding >= 0 || (p.plan.n.nulled & (1 << p.i)))) p.plan = null;
    // decide on the tick before the weapon's ready (the click), as the server's crew does
    if (p.readyAt <= t + 1 && (!p.plan || p.plan.at < t - 1)) {
      const all = this.targets();
      const choice = this.policy.choose(this, p, all);
      if (choice && choice.post) { p.plan = null; p.post = choice.post; p.tx = choice.x; p.tz = choice.z; }
      else if (choice) { p.post = null; const spot = this.attackSpot(p, choice.w, choice.n); if (spot) p.plan = { w: choice.w, n: choice.n, x: spot[0], z: spot[1], at: t }; else p.plan = null; }
      else { p.plan = null; const pf = PREFIRES[Math.min(this.wave, PREFIRES.length - 1)][ROLES.indexOf(p.role)]; p.tx = pf[0]; p.tz = pf[1]; }
    }
    if (p.plan) {
      const sp = this.attackSpot(p, p.plan.w, p.plan.n);
      if (!sp) { p.plan = null; return; }
      this.moveToward(p, sp[0], sp[1]);
      if (p.x === sp[0] && p.z === sp[1] && t >= p.readyAt && t > p.plan.at) { // a click goes off the tick after it's made
        const all = this.targets();
        const bit = 1 << p.i;
        const wanted = all.filter((o) => !(o.nulled & bit) && o.pending < o.hp);
        const n = p.plan.n;
        if (n.style !== WEAPONS[p.plan.w].hits) { p.plan = null; return; }
        this.attack(p, p.plan.w, n, wanted, all);
        p.plan = null;
      }
    } else this.moveToward(p, p.tx, p.tz);
  }
}

module.exports = { PREFIRES, Room, WEAPONS, WEAPON_NAMES, KITS, ROLES, WAVES, dist, cheb, overlaps, STAND, MOUTHS, CAP_LATE, SMALL_LIFE, BIG_LIFE };
