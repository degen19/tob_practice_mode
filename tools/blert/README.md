# Blert data

Blert (blert.io) records real raids tick by tick. It's the ground truth for timings and bot behaviour, after the
owner's word.

## What's here

- **`blert-top.tgz`:** the 50 fastest regular trio rooms for Maiden (`blert-top/maiden3/`) and Nylocas
  (`blert-top/trio/`). One JSON array of stage events per raid. To unpack: `tar xzf blert-top.tgz`.
- **`data/raids/<mode>_<scale>/<uuid>/<stage>.json`:** raids fetched with `fetch.sh` (Normal mode 10 and 11).
- **`data/solo_*.json`, `data/entry/`:** solos and entry mode.
- **`bloat*.py`, `sote*.py`, `los.py`:** earlier analysis scripts.

## Verzik, the 50 fastest rooms at each scale

- **`verzik-top.tgz`:** the 50 fastest regular Verzik rooms on Blert for 2, 3, 4 and 5 players (sorted by the Verzik
  split, 85), each room's events. To unpack: `tar xzf verzik-top.tgz -C data` (gives `data/verzik/<scale>/`).
  `./fetch_verzik.sh <scale> [count]` fetches them again; it waits out Blert's limit of 120 requests a window.
- **`verzik.py summary`:** splits per scale. **`verzik.py export <out.json.gz>`:** the replays the server plays
  (`content/raids/tob-bloat/src/main/resources/verzik/blert.json.gz`, `::verzik blert <scale> [rank]`).
- **`verzik_plans.mjs`:** each top team's P1 Dawn rotation as a verzikSim plan, simulated, and the best one improved
  with the sim's plan search.
- **`defs/`:** Blert's attack, spell and NPC definitions (from blert-io/protos, MIT).

## Fetching more

```
./fetch.sh <mode> <scale> <count>                                     # finished raids: all six rooms' events
curl "https://blert.io/api/v1/challenges?type=1&limit=50&page=0"      # a page of raids (uuid, scale, stage, status)
curl "https://blert.io/api/v1/raids/tob/<uuid>/events?stage=12"       # one room's events
```

- **The fastest rooms of one boss:** sort by its split, e.g. `sort=%2Bsplits:70` for Normal Xarpus (`extraFields=splits:70`
  to get the times back; the API lists them under the room's base split, 69). Split ids are in Blert's `common/split.ts`.
- **Xarpus:** `./xarpus_fetch.sh <scale> 50`, then `python3 -I xarpus.py xarpus` (timings and how the teams play) and
  `python3 -I xarpus_pack.py out.json label=path[,scale] ...` (rooms for the replay page; takes server recordings too).
- **Rate limit:** about 120 requests a window, then `rate_limit_exceeded`; `xarpus_fetch.sh` waits and retries.
- **Stages:** 10 Maiden, 11 Bloat, 12 Nylocas, 13 Sotetseg, 14 Xarpus, 15 Verzik.
- **Modes:** 10 entry, 11 normal, 12 hard. **Scale:** party size.
- Be polite to the API: sleep about 0.3 s between requests.

## Event shape

Every event has `type`, `stage`, `tick`, `xCoord`, `yCoord`, plus:

- **type 4,** a player's tile and state each tick: `player.name`.
- **type 5,** a player attack: `attack.type`, `attack.weapon.id`, `attack.target.roomId`,
  `attack.distanceToTarget`.
- **type 7 / 8 / 9,** an NPC spawns / updates each tick / dies: `npc.id`, `npc.roomId`,
  `npc.hitpoints` = current << 16 | base.
  - Nylocas also carry `npc.nylo` (`wave`, `big`, `style` 0 melee / 1 range / 2 mage, `spawnType` 1 W / 2 S / 3 E,
    `parentRoomId` for a split).
- **type 10,** an NPC attack: `npcAttack.attack` (Xarpus: 10 spit, 11 turn, 12-15 turn NE / SE / SW / NW).
- **type 140 / 141 / 142,** Xarpus's phase (`xarpusPhase` 1 P2, 2 P3), an exhumed when it goes (`xarpusExhumed`:
  `spawnTick`, `healAmount`, `healTicks`) and an acid splat (`xarpusSplat`: `source` 1 spit / 2 bounce, `startTick`,
  `bounceFrom`).
- **Attack types seen at Xarpus:** 38 scythe, 85 / 84 Elder maul spec / swing, 11 BGS spec, 62 Ralos spec, 58 / 57 ZCB
  spec / bolt, 20 / 19 claw spec / scratch, 14 halberd spec, 12 blowpipe.
- **type 120 / 121 / 122,** a Nylo wave spawn / stall / cleanup end: `nyloWave` (`wave`, `nylosAlive`, `roomCap`).
- **Coordinates:** Nylocas room-local tiles are (x - 3264, y - 4224).

## Findings worth knowing

- **Nylo cap:**
  - a killed small counts toward the room cap for 2 ticks after its killing hitsplat;
  - a big counts as 1 until its 2 splits appear 6 ticks after its killing hitsplat (1 → 2, never through 0).
  - This matches 1,648 of 1,657 wave checks.
- **Nylo boss:** it spawns 16–19 ticks after cleanup end (type 122).
- **Maiden trios:** the mage's tbow goes 2 ticks after the ranger and melee swings (216 of 221).
- **Xarpus (the 50 fastest Normal rooms of each scale, 250 rooms):**
  - exhumeds: 7 every 12 ticks solo, 9 / 12 every 8 in duos / trios, 15 / 18 every 4 in 4s / 5s, from the first
    instance tick 1 at or after tick 8; each lasts 11 and heals from its third tick (20 / 16 / 12 / 9 / 8 a heal). He
    rises 20 ticks after the last one comes up (t100 / 92 / 116 / 84 / 96).
  - spits: the first 7 ticks after he rises, then every 4, round the party in a fixed order, at the tile the player
    stood on as he spat; the acid leaves 2 ticks later and flies a tick per tile less one. Under him on the spit tick:
    he rears but throws nothing (solos do this on about 2 spits in 5).
  - bounces: 1 or 2 (about even), leaving as it lands, to the tiles the next players in turn stood on when he spat,
    random tiles once there's nobody left; a tick per tile plus one. Bounces from a duo stacked on one tile: 0 or 1.
  - the screech is on a tick divisible by 4; the first turn is 8 ticks after it, then one every 8.
  - acid stays for the rest of the room and hurts (about 6 a tick, 2 ticks later) whatever its age; real players stand
    in it about 5 ticks a room. Each holds a tile beside him and steps two straight out on every spit tick, back the
    tick after, so the acid lands off the ring (a solo steps under him instead). Damage taken per player, P2 / P3: solo
    308 / 53, duo 19 / 27, trio 32 / 17, 4s 22 / 20, 5s 22 / 16.
  - every P2 scythe is swung from a tile touching him (distance 1).
