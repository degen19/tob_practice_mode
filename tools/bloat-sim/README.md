# Bloat sim

An offline Pestilent Bloat room and a team that plays it from a **procedure**, checked against Blert's fastest rooms.
It's the planning side of the Bloat bot crew: the server's `BloatRoom.kt` and this sim run the same rules.

## Data

`tools/blert/bloat-top.tgz` holds the 30 fastest Normal-mode Bloat rooms per scale (Blert, sorted by the Bloat split),
one events file each. Unpack it here: `tar xzf ../blert/bloat-top.tgz` (makes `top/`), or point `BLOAT_TOP` at a copy.

- `stuns.py`: which rooms had a hand stun (a player on a splat tile the tick before it lands). It writes
  `top/clean.json`: 140 of the 150 rooms are clean (solo 30, duo 28, trio 28, 4s 26, 5s 28).
- `profile.py`, `down.py`: downs, weapons, stalls and swing ticks per scale.
- `dmgfit.py`, `fit_traces.py`, `replay_check.py`: the hit models, fitted by replaying Blert's own attacks against its
  hitpoints tick by tick.

## The room (`bloat.py`)

A port of `BloatRoom.kt`'s tick: his loop, speed by hitpoints, turns, the down windows (39-46 first, then 38-45 or
34-41), the stomp's Defence reset, hands (16 a drop, landing 3 ticks later, checked against where players stood the
tick before) and the half/full damage rule.

## The team (`team.py`)

Players wait outside a gate and come in on their tick; flies, phoenix necklaces and stalls follow the server:

- **Flies:** one swarm on a player he sees (from his centre tile, the tank blocking), 1-20 a tick, three quarters
  under Protect from Missiles. When he loses its player it bounces to someone that player sees.
- **Stalls** (Humidify, Spellbook Swap): the tick after a swing; 3 ticks locked, hits held till the end. A phoenix
  necklace (proc under 20%, heals 30%) clears every held hit, so one necklace soaks the whole bunch.
- **Stallers** swing in salve, put a pneck on and stall, step on the swing tick. They keep the pneck on (no salve) for a
  swing if one fly hit could take them under 20%.
- **Non-stallers** keep the pneck on and path on purpose: each cycle they pick the tile they'll swing from, in reach of
  where he'll be when the scythe comes off cooldown, round the tank out of his sight and the stallers', and walk there.
- Everyone ends each tick off any tile a hand lands on next tick (stallers too, as Blert shows).

## Procedures (`procedures/*.json`)

One file per team, the plan the bots follow:

| Field | Meaning |
|---|---|
| `entry` | when the team goes in: his south-west tile `x` and `heading` (`east`/`west`), north or south side |
| `damage_factor` | the team's gear against Blert's fitted hit models |
| `players[]` | one per player, below |
| `gate`, `enter` | `east` (default) or `west`; the tick they pass it (the first player on 0) |
| `stall`, `salve`, `necks`, `hp` | stalls or paths; `always`/`never` salve on walk swings; pnecks carried; entry hitpoints |
| `first_swing`, `walk_swings` | the first walk swing; or every walk swing tick (they hide between ones more than 4 apart) |
| `walk_specs` | `[{"weapon": "BGS_SPEC", "tick": 2}]` on a room tick |
| `down_specs`, `down_start` | `[{"weapon": "CLAW_SPEC", "at": 7, "down": 1}]` ticks into a down (`down` optional); first swing per down |
| `rewalk_attack` | whether they swing on the walks after the first down |

Spec energy is tracked (claws and BGS 50%, chally 30%, ZCB 75%; 10% back every 50 ticks).

## Running

```
python3 team.py procedures/5s.json 400   # success, median, best, deaths, stuns, pnecks per room
python3 calib.py 1 2 3 4 5                 # sim vs Blert on rooms whose first down came at walk tick 39
python3 build_replays.py                   # viewer/replays.json: Blert rooms + sim runs, with the walk-in
```

`viewer/index.html` plays `viewer/replays.json` tick by tick (serve the folder, e.g. `python3 -m http.server`).

## Where it stands

On a first down at walk tick 39 (Blert's fastest rooms mostly are), kill tick, sim vs Blert's top 30:
solo 273 vs 275 (median), duo 127 vs 127, trio 67 vs 67, 4s 65 vs 65, 5s 62 vs 62.
Over random downs, 400 rooms each: 100% success, no deaths, no stuns.
