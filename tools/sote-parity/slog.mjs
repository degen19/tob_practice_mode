// node slog.mjs <verzikSim dir> <team>[/way] <seed>...: the sim's Sotetseg raid and log per seed, with cli/sote.mjs
// --full's players (the server's SotePlan.of(team, way)), in SoteCheck's format.
import { pathToFileURL } from 'node:url';
import { join, resolve } from 'node:path';

const [dir, teamArg, ...seeds] = process.argv.slice(2);
const at = (f) => pathToFileURL(join(resolve(dir), f)).href;
const { runSote, defaultSetup } = await import(at('engine/sote.js'));
const { blankPlayer } = await import(at('engine/loadout.js'));
const { Rng } = await import(at('engine/rng.js'));
const [team, way = 'pneck'] = [Number(teamArg.split('/')[0]), teamArg.split('/')[1]], setup = defaultSetup(team);
// way (as SotePlan.WAYS): who is short of phoenix necklaces and how they cross - from trios up the last player only
const WAY = { shark: [['shark', 'pneck'], 1], noneck: [['shark', 'shark'], 0], redemption: [['pneck', 'redemption'], 1], run: [['run', 'run'], 2] };
// trios to solos: Blert's most common team among the 50 fastest, start spec and surges as the solver tunes them (the
// server's SotePlan.of); 4s and 5s: the sim's defaults
const S = (spec, o = {}) => ({ spec, backup: false, horn: false, ...o }), M = S('maul'), Z = S('zcb'), N = S('none');
const PLAN = {
  1: [{ prefire: "Tumeken's shadow", startSpec: 95, surge: true, specs: [0, 1, 2].map(() => S('maul', { untilHit: true })) }],
  2: [{ prefire: 'Twisted bow', startSpec: 100, surge: true, specs: [S('maul', { duoBackup: true }), M, M] }, { prefire: "Tumeken's shadow", startSpec: 100, surge: true, specs: [M, M, M] }],
  3: [{ prefire: 'Shadow Barrage', startSpec: 100, surge: false, specs: [N, M, M] }, { prefire: 'Twisted bow', startSpec: 100, surge: true, specs: [M, N, Z] }, { prefire: 'ZCB auto', startSpec: 100, surge: true, specs: [N, M, M] }],
};
const crossing = (k) => (WAY[way] && team > 1 && (way === 'run' || team === 2 || k === team - 1) ? { mazes: WAY[way][0], pnecks: WAY[way][1] } : {});
const players = Array.from({ length: team }, (_, k) => {
  const g = blankPlayer(k); g.prayer = 'Zeal'; g.locator = true;
  Object.assign(g.range, { cape: "Blessed dizana's quiver", neck: 'Necklace of rupture', body: 'Masori body (f)', legs: 'Masori chaps (f)', head: 'Masori mask (f)', hands: 'Zaryte vambraces' });
  Object.assign(g.mage, { head: 'Ancestral hat', body: 'Ancestral robe top', legs: 'Ancestral robe bottom', neck: 'Occult necklace', hands: 'Confliction gauntlets', cape: 'Imbued saradomin cape' });
  g.weapons.push('Elder maul', 'Twisted bow', 'Zaryte crossbow', "Tumeken's shadow", 'Shadow ancient sceptre');
  return { name: `P${k + 1}`, gear: g, prefire: 'Twisted bow', pnecks: 2, sharks: 4, rests: 4, ...(PLAN[team] ? PLAN[team][k] : setup[k]), ...crossing(k) };
});
for (const seed of seeds) {
  const log = [], r = runSote(players, team, new Rng(seed), log);
  console.log(`== ${seed} end ${r.end} kill ${r.kill} mazes ${r.mazes.join(',')} hp ${r.players.map((p) => p.hp).join(',')}`);
  for (const l of log) console.log(l);
}
