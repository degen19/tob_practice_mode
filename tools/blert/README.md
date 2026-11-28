# Blert data

Blert (blert.io) records real raids tick by tick. It's the ground truth for timings and bot behaviour, after the
owner's word.

## What's here

- **`blert-top.tgz`:** the 50 fastest regular trio rooms for Maiden (`blert-top/maiden3/`) and Nylocas
  (`blert-top/trio/`). One JSON array of stage events per raid. To unpack: `tar xzf blert-top.tgz`.
- **`data/raids/<mode>_<scale>/<uuid>/<stage>.json`:** raids fetched with `fetch.sh` (Normal mode 10 and 11).
- **`data/solo_*.json`, `data/entry/`:** solos and entry mode.
- **`bloat*.py`, `sote*.py`, `los.py`:** earlier analysis scripts.

## Fetching more

```
./fetch.sh <mode> <scale> <count>                                     # finished raids: all six rooms' events
curl "https://blert.io/api/v1/challenges?type=1&limit=50&page=0"      # a page of raids (uuid, scale, stage, status)
curl "https://blert.io/api/v1/raids/tob/<uuid>/events?stage=12"       # one room's events
```

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
- **type 120 / 121 / 122,** a Nylo wave spawn / stall / cleanup end: `nyloWave` (`wave`, `nylosAlive`, `roomCap`).
- **Coordinates:** Nylocas room-local tiles are (x - 3264, y - 4224).

## Findings worth knowing

- **Nylo cap:**
  - a killed small counts toward the room cap for 2 ticks after its killing hitsplat;
  - a big counts as 1 until its 2 splits appear 6 ticks after its killing hitsplat (1 → 2, never through 0).
  - This matches 1,648 of 1,657 wave checks.
- **Nylo boss:** it spawns 16–19 ticks after cleanup end (type 122).
- **Maiden trios:** the mage's tbow goes 2 ticks after the ranger and melee swings (216 of 221).
