# Verzik

**Files:**
- `solo/VerzikSolo.kt`: the room (P1 to P3 and the transitions);
- `bloat/VVerzikScript.kt`: the practice commands - `::verzik [2-5] [median | top10 | slow | fail | time | seed vN]`
  plays one of verzikSim's raids, `::verzik blert <2-5> [rank]` one of Blert's 50 fastest; `VERZIK_SELFTEST` runs them
  headless and logs tile and hitpoint drift; `VERZIK_FX=1` traces every effect with its tick;
- `bloat/verzik/VerzikReplayRoom.kt`: the room both play in; `VerzikReplays.kt`: the raids;
  `VerzikLooks.kt`: how her room looks and sounds; `bloat/BlertLooks.kt`: a bot's attack as combat shows it;
- `resources/verzik/`: `blert.json.gz` (Blert's rooms, `tools/blert/verzik.py export`); `sim.json.gz` (300 sim raids
  a scale: `VERZIKSIM=<sim> node solve.mjs <n>`, `node vexport.mjs <n> 300 /tmp/verzik<n>.json.gz`, then
  `node simreplay.mjs blert.json.gz <tools/blert/defs> sim.json.gz 2=... 3=... 4=... 5=...`); `cfgs<n>.json`, the
  chart template's setup with the solver's P1 per scale. A sim raid's movement comes from a Blert raid of its scale,
  time-warped phase by phase onto the sim's; its attacks, her attacks and hitpoints are the sim's.

**verzikSim.JS** (`degen19/verzikSim.JS`) is the Verzik sim: Monte Carlo, 2–5 man, an optimizer and solver, and the
chart template. Read its `HANDOFF.md`, `README.md` and `CONVENTIONS.md`.
- Its main branch is the live site: push only when the owner says "push".
- Verzik 2–5 man behaviour stays as it is unless the owner asks.
- "Success" means killed before the fail round.

**Rules from the owner:**
- P2 reds (the R-numbered ones) are Verzik terms only. Don't use them for other rooms.
- HP: verzikSim's values are right (P2/P3 3500 / 3062 / 2625 by scale). Blert's are provably wrong; the replay puts
  Blert's hitpoints on our scale.
- "Success" stays a kill within the reds' fail round, counted as X downs per scale (set in the lobby). Raids that fail
  will carry on to the kill, here and in verzikSim.
- Spec procedures vary by team and get built in the lobby.
- P2 crab popping: the sim assumes a set Boak pattern per side; real teams end it early or pop the purple sooner
  depending on the crabs' spawns and aggro. Model it from Blert, then bring it back to the sim.
- Animations and sounds have to feel 1:1.

**When the owner's away** (no answer within an hour, for the next two weeks from 2026-11-27): model the other scales
from Blert and build the PPO learner to optimize the room at each scale.

**P2 crabs (`tools/blert/verzik_crabs.py`, 3-5 man):**
- 2-3 nylocas a raid; they pop at 10-13 ticks old (911 of 923 are never attacked). The purple is always blowpiped,
  dead at 13-15 ticks old; the first blowpipe comes a median 14 ticks after her purple attack.
- A purple costs each melee player a median 1 tick (mean 1.3-1.4) on either side. By where the player's next swing
  falls (ticks after her purple attack): due on 1 -> 3 lost, on 2 -> 2, on 3 -> 1 (most teams: 89-121 of ~150).
- verzikSim's Boak blocks give the same cost for West once Blert's attack tick is taken as the sim's tick -2
  (3 / 2 / 1); East gives 2 / 1 / 1 where real players lose 3 / 2 / 1. To settle: which tick Blert's NPC attack is in
  the sim's count, then East's blocks.

**The sim's setup vs Blert:** the chart template has no purple DC at 5s (and the other scales' solved setups the
same), so in the sim the purple heals her every 4 ticks until reds (~160 HP); every top Blert team blowpipes it dead
13-15 ticks after it lands. That's the lobby's procedure to set.

**Learner (verzikSim `room/verzik`, `cli/learn.mjs`):** a policy-gradient learner over the optimizer's chart inputs
(death-charge owners, Boak sides, East bounce tech, amulets, East patterns), reward -end for a kill and -1000 for a
fail. On each scale's template setup (256 fresh raids): 2s kills 36 -> 73, median 581 -> 548; 3s 7 -> 10, 337 -> 326;
4s 121 -> 116, 301 -> 295; 5s 214 -> 216, 285 -> 279 (Blert's top-50 medians 541.5 / 332 / 298.5 / 289). Most of it
is the death charges the template leaves unset. Next: the lobby's procedures as its start, its inputs widened (P1
chart, thresholds), fails that carry on to the kill.

**What Blert shows (`tools/blert`, top 50 per scale):**
- Her P1 blast and her P2 spark both hurt 2 ticks after her attack (537 of 566, 320 of 345).
- Her death: Blert seldom has the tick her hitpoints reach 0; P1 dies 4 ticks before her climb-down form, P2 and P3 2
  ticks before the next form (every raid).
- 911 of 923 P2/P3 nylocas are never attacked: they turn (detonate animation, 2 ticks) and pop.
- The P2 kill comes within 44 ticks of the reds in all top 4s and 5s, 21 of 50 trios, no duos.

**Bugs from the owner's first look at `::verzik blert` (04d9848), and their state:**
1. P1 tile: she was the size-1 `verzik_initial_quickstart` transmogged into P1, so her model sat west of the throne.
   Now each form is a new NPC of its own size; the start form is `verzik_initial` (size 5) at Blert's tile. Checked:
   size 5 at 3166,4323 on the 5x4 throne (3166,4324). The real raid (the 4s WR clip) shows the second cause: her
   seated forms are drawn in their own throne, so the empty throne (`tob_verzik_throne_visible`) must be hidden
   until she climbs down; it was left visible (the solo room sets it at its start), so two thrones showed. The replay
   now hides it until her climb-down form. The solo room has the same bug (to port once the replay is confirmed).
2. P1 orb: the real raid frame by frame: she winds up, the orb leaves her hands ~1.5 ticks in (45 client cycles),
   high, and lands ~2.3 ticks in (70), as Blert's +2 hurt. Checked: every orb lands on the tick the recorded player
   hitpoints drop.
3. P1 death / transition: her death animation now plays when she dies (4 ticks before the climb-down form), and every
   form comes in as a new NPC on Blert's tile. Checked: NPC drift 0 on 8 raids, her 3 deaths each raid.
4. Dawnbringer on the floor: from each Blert drop to its pick-up, on Blert's tile. Checked against the recorded drops.
5. Hitsplats on her: each hit landing that tick its own splat (weapon hit delays from verzikSim; a scythe's 4:2:1, a
   Dawnbringer spec's recorded damage), others' tint. Checked: splats add up to each tick's recorded damage.
6. Hitsplats on players: from the recorded hitpoints (Blert has them for the players who recorded).
7. Reds' spawn: `elemental_spawn` on their first tick (was a tick late); the purple climbs out (`tob_spider_tank_spawn`).
8. Nylocas deaths: turn for 2 ticks (detonate animation), then pop on Blert's tick; killed ones pivot, then pop.
9. P2 shield: `verzik_phase2_poisonaura` was the wrong graphic (the owner: no green blob). Taken off; the real raid's
   pale swirl at reds comes with her heal animation (`verzik_phase2_heal`, 10 ticks), which plays.
10. Webs: the solo room's volleys while she spins (Blert doesn't record webs), web NPC and loc for 30 ticks.
11. Under her during webs: her tiles block players for the spin. Checked: 49 tiles flagged.
- The owner has a few more to send.

**Bugs from the owner's look at `::verzik 4 median` (sim raids), and their state:**
1. P2 attack animations at the start: the replay played `verzik_phase2_spawn` (14 ticks) on her P2 form, over her
   first attacks. That rise belongs to her climb-down form (`verzik_phase1_to2_transition` has it as its stand and
   walk animation; Blert: that form 13 ticks, P2 comes in ready). Taken off the P2 form (the solo room has the same).
2. Non-purple nylos (reds) not showing their spawn: they died in the sim export 40 ticks after reds, before P2
   ended. Blert: reds live till she goes into her P2-to-P3 form (443 of 444 within 3 ticks). They now do.
3. Green blob in the shield: see 9 above.
4. P2 ranged attacks landing late: the urn flew for 6 ticks. The 4s WR clip, frame by frame: it leaves her ~0.5
   tick into her cast and splashes on its tile ~1.7 ticks after; it now leaves at 15 client cycles and lands at 60
   (2 ticks). (Blert's player hitpoints mostly drop 6 ticks after her attack - 356 of 829 - so its damage tick is
   not the splash; the sim doesn't model the urn's damage.) Also in the clip, at every P2 attack: a pale dome at her
   feet; not identified in the cache yet.
5. P3 animations at the start / P3 death: the sim raid ended its playback on the kill tick, so the death bat (2 ticks
   later) never came and she held her death animation. Playback now runs to her last form.
6. No player hitpoints, splats or orbs: the sim export carried no player hitpoints. It now takes them from the
   sim's log (every hit, brew and shark) - splats and hitpoint bars follow - and the Theatre's HUD opens for whoever
   watches: the party's orbs top left and her bar top centre.
7. Start of P2 / scythes from a distance / afk: players stood where a Blert team stood at that moment, not where the
   sim's attacks need them. Now: in P2 each player has a rest tile a step out from her swing tile (the template's
   tiles), steps in on the tick they swing and straight back out; at reds they stand beside the crab or her they hit
   next; in P3 they keep their place round her and step beside her to swing. Every melee swing is from a tile beside
   what it hits (not diagonal; a halberd two tiles): out of reach 11,390 -> 62 of 189,107 swings across the four
   scales' 300 raids each.
8. Dawnbringer: it now goes down on the floor where the holder stands (2 ticks after their last spec, or just before
   the next holder's first), the next holder already standing on that tile picks it up a tick later, and nobody leaves
   the tile until they're done with it. The last holder's goes when P1 dies. Every pick-up is on its holder's tile.
9. Eating and drinking: the sim's brews, restores, surges and sharks per player (the sim's supplies, the same for
   every player) play `human_eat` with the drink or eat sound, on ticks they don't attack. Spec transfers play the
   Lunar cast (4411) with Energy Transfer's graphic (734) on the target.
- Found in the solo room, not yet fixed there: the death bat comes up 2 tiles off (it's transmogged from her 7x7 P3;
  it's 3x3, centred) and the empty throne shows while she sits on it.

**Next:** the owner's remaining bugs; a real-game capture for her projectiles and effects; the sim's raids at every
scale in the server with the same looks; P2 crab popping (Boak and its early ends) from Blert; the PPO learner for the
bots' play, kept close to Blert.
