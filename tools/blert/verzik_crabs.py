"""P2 crabs on Blert's fastest Verzik rooms: who pops each nylocas, when, from where, and what it costs them.

python3 verzik_crabs.py [scale ...]          the nylocas and purples per scale (data/verzik/<scale>/: unpack verzik-top.tgz)
python3 verzik_crabs.py boak [scale ...]     the ticks a purple costs each player, by side, and the first blowpipe

A nylocas pops (it isn't killed: 911 of 923 never are) when it reaches the player it's after. The popper is the player
within a tile of it on its last tick. What it costs: the popper's ticks without an attack around the pop, beyond their
weapon's own cycle (a scythe's 5).
"""
import statistics
import sys
from collections import Counter, defaultdict

import verzik

CRAWLERS = {8381: 'melee', 8382: 'range', 8383: 'mage'}
PURPLE = 8384
SCYTHE_CYCLE = 5


def tile_of(track, t):
    s = None
    for row in track:
        if row[0] > t:
            break
        s = row
    return s


def analyse(scale):
    rows = []
    purples = []
    for chal, ev in verzik.raids(scale):
        r = verzik.replay(chal, ev)
        p2 = next((t for t, m in r['marks'] if m == 'P2'), None)
        p3 = next((t for t, m in r['marks'] if m == 'P3'), None)
        if p2 is None or p3 is None:
            continue
        her = next(i for i, n in enumerate(r['npcs']) if any(s[1] == 8372 for s in n['track']))
        her_tile = next(s for s in r['npcs'][her]['track'] if s[1] == 8372)
        hx = her_tile[2] + 1
        swings = defaultdict(list)
        for a in r['attacks']:
            swings[a[1]].append(a[0])
        for i, n in enumerate(r['npcs']):
            kind = n['track'][0][1]
            if not (p2 <= n['spawn'] < p3) or n['death'] is None:
                continue
            if kind == PURPLE:
                hits = [a for a in r['attacks'] if a[5] == i]
                purples.append({'age': n['death'] - n['spawn'], 'spawn': n['spawn'] - p2, 'hits': len(hits),
                                'by': Counter(a[2] for a in hits)})
                continue
            if kind not in CRAWLERS:
                continue
            last = tile_of(n['track'], n['death'])
            side = 'W' if n['track'][0][2] < hx else 'E'
            poppers = []
            for p in range(r['players']):
                x, y = r['tiles'][p][2 * n['death']], r['tiles'][p][2 * n['death'] + 1]
                if max(abs(x - last[2]), abs(y - last[3])) <= 2:
                    poppers.append(p)
            cost = []
            for p in poppers:
                before = [t for t in swings[p] if t <= n['death']]
                after = [t for t in swings[p] if t > n['death']]
                if before and after:
                    cost.append(max(0, after[0] - before[-1] - SCYTHE_CYCLE))
            rows.append({'side': side, 'kind': CRAWLERS[kind], 'spawn': n['spawn'] - p2, 'age': n['death'] - n['spawn'],
                         'poppers': len(poppers), 'cost': max(cost) if cost else None, 'raid': chal['uuid']})
    return rows, purples


def summary(*scales):
    for scale in [int(s) for s in scales] or [2, 3, 4, 5]:
        rows, purples = analyse(scale)
        if not rows:
            continue
        ages = [r['age'] for r in rows]
        costs = [r['cost'] for r in rows if r['cost'] is not None]
        raids = len({r['raid'] for r in rows})
        print(f'== {scale}s: {len(rows)} P2 nylocas in {raids} raids ({len(rows) / raids:.1f} a raid)')
        print(f'   pop age: median {statistics.median(ages)}, range {min(ages)}-{max(ages)}; '
              f'by age {sorted(Counter(min(a, 20) for a in ages).items())}')
        print(f'   popped with a player beside it: {sum(1 for r in rows if r["poppers"])} of {len(rows)}')
        if costs:
            print(f'   ticks the popper lost (beyond a scythe cycle): median {statistics.median(costs)}, '
                  f'mean {statistics.mean(costs):.2f}, {sorted(Counter(min(c, 8) for c in costs).items())}')
        by_side = Counter(r['side'] for r in rows)
        print(f'   spawn side {dict(by_side)}; first spawn {min(r["spawn"] for r in rows)} ticks into P2')
        if purples:
            print(f'   purples: {len(purples)}, age at death median {statistics.median([p["age"] for p in purples])}, '
                  f'killed by {sum((p["by"] for p in purples), Counter()).most_common(5)}')




CYCLE = {'SCYTHE': 5, 'BLOWPIPE': 2, 'CLAW_SCRATCH': 4, 'EYE_OF_AYAK_AUTO': 3, 'SHADOW': 5, 'DAWN_AUTO': 4, 'DAWN_SPEC': 4,
         'ZCB_AUTO': 5, 'SWIFT': 3, 'CHALLY_SPEC': 7, 'CLAW_SPEC': 4, 'SANG': 4}


def boak(scale, window=20):
    """Per purple spawn: each player's ticks lost to it (beyond their weapons' cycles) in [spawn, spawn + window), their
    side (west/east of her middle), and the first blowpipe shot after the spawn."""
    out = []
    for chal, ev in verzik.raids(scale):
        r = verzik.replay(chal, ev)
        her = next((i for i, n in enumerate(r['npcs']) if any(s[1] == 8372 for s in n['track'])), None)
        if her is None:
            continue
        hx = next(s for s in r['npcs'][her]['track'] if s[1] == 8372)[2] + 1
        reds = min((n['spawn'] for n in r['npcs'] if n['track'][0][1] == 8385), default=10 ** 9)
        for t0, _, name, _ in r['her']:
            if name != 'P2_PURPLE' or t0 + window > reds:
                continue
            bp = min((a[0] for a in r['attacks'] if a[2] == 'BLOWPIPE' and t0 <= a[0] < t0 + window), default=None)
            for p in range(r['players']):
                mine = sorted((a[0], a[2]) for a in r['attacks'] if a[1] == p and t0 - 6 <= a[0] < t0 + window)
                lost = 0
                for (ta, wa), (tb, _) in zip(mine, mine[1:]):
                    if tb <= t0:
                        continue
                    lost += max(0, min(tb, t0 + window) - max(ta, t0) - CYCLE.get(wa, 5)) if ta >= t0 else max(0, tb - t0 - max(0, CYCLE.get(wa, 5) - (t0 - ta)))
                x = r['tiles'][p][2 * t0]
                out.append({'side': 'W' if x < hx else 'E', 'lost': lost, 'bp': None if bp is None else bp - t0,
                            'swings': len([m for m in mine if m[0] >= t0])})
    return out


def boak_summary(*scales):
    for scale in [int(s) for s in scales] or [3, 4, 5]:
        rows = boak(scale)
        for side in ('W', 'E'):
            lost = [r['lost'] for r in rows if r['side'] == side]
            if lost:
                print(f'{scale}s {side}: {len(lost)} player-spawns, ticks lost in 20: median {statistics.median(lost)}, '
                      f'mean {statistics.mean(lost):.2f}, {sorted(Counter(min(x, 10) for x in lost).items())}')
        bps = [r['bp'] for r in rows if r['bp'] is not None]
        if bps:
            print(f'{scale}s first blowpipe after the purple: median {statistics.median(bps)}, {sorted(Counter(bps).items())[:12]}')


if __name__ == '__main__':
    if sys.argv[1:2] == ['boak']:
        boak_summary(*sys.argv[2:])
    else:
        summary(*sys.argv[1:])
