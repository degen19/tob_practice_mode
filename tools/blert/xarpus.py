"""Blert's fastest Xarpus rooms per scale: the mechanics' timings and how the teams play them.

python3 -I xarpus.py xarpus   (after xarpus_fetch.sh for each scale)  ->  prints a report (markdown) and writes xan.json beside it.
"""
import collections
import json
import os
import statistics as st
import sys

X, Y = 3168, 4385  # his 5x5's south-west tile
SPIT, TURN, TURNS = 10, 11, {12: 'NE', 13: 'SE', 14: 'SW', 15: 'NW'}


def under(x, y):
    return X <= x <= X + 4 and Y <= y <= Y + 4


def quarter(x, y):
    e, n = x > X + 2, y > Y + 2
    return 'NE' if e and n else 'SE' if e else 'SW' if not n else 'NW'


def med(a):
    return st.median(a) if a else None


def hist(a, top=8):
    return ', '.join(f'{k}:{v}' for k, v in collections.Counter(a).most_common(top))


def room(ev):
    r = {'phase': {}, 'exh': [], 'spits': [], 'turns': [], 'splats': [], 'atk': [], 'pos': collections.defaultdict(dict)}
    for e in ev:
        t, ty = e['tick'], e['type']
        if ty == 140:
            r['phase'][e['xarpusPhase']] = t
        elif ty == 141:
            x = e['xarpusExhumed']
            r['exh'].append((x['spawnTick'], t, len(x.get('healTicks') or []), x.get('healAmount'), e['xCoord'], e['yCoord']))
        elif ty == 10:
            a = e['npcAttack']['attack']
            if a == SPIT:
                r['spits'].append(t)
            elif a == TURN or a in TURNS:
                r['turns'].append((t, TURNS.get(a)))
        elif ty == 142:
            s = e['xarpusSplat']
            r['splats'].append((t, s.get('source'), s.get('startTick'), s.get('bounceFrom'), e['xCoord'], e['yCoord']))
        elif ty == 4:
            r['pos'][e['player']['name']][t] = (e['xCoord'], e['yCoord'])
        elif ty == 5:
            a = e['attack']
            r['atk'].append((t, e['player']['name'], a['type'], a['weapon']['id'], a.get('distanceToTarget'), e['xCoord'], e['yCoord']))
        elif ty == 9 and e['npc']['id'] in (8341, 10769, 10773):
            r['dead'] = t
    return r


def main(base):
    out = {}
    print('# Blert: the 50 fastest Xarpus rooms per scale (Normal mode)\n')
    for s in sorted(os.listdir(base)):
        d = os.path.join(base, s)
        idx = {c['uuid']: c for c in json.load(open(os.path.join(d, 'index.json')))}
        rooms = []
        for u, c in idx.items():
            f = os.path.join(d, u + '.json')
            if not os.path.exists(f) or os.path.getsize(f) < 100:
                continue
            try:
                ev = json.load(open(f))
            except ValueError:
                continue
            if not isinstance(ev, list):
                continue
            r = room(ev)
            r['uuid'], r['time'] = u, c['splits'].get('69', {}).get('ticks')
            r['party'] = [p['username'] for p in c['party']]
            rooms.append(r)
        S = {}
        S['n'] = len(rooms)
        S['time'] = sorted(r['time'] for r in rooms if r['time'])
        p2 = [r['phase'].get(1) for r in rooms if 1 in r['phase']]
        p3 = [(r['phase'][2] - r['phase'][1]) for r in rooms if 1 in r['phase'] and 2 in r['phase']]
        # exhumeds
        firsts, gaps, lives, counts, healn, p2gap, ex_inst = [], [], [], [], [], [], []
        for r in rooms:
            ex = sorted(r['exh'])
            if not ex:
                continue
            counts.append(len(ex))
            firsts.append(ex[0][0])
            gaps += [b[0] - a[0] for a, b in zip(ex, ex[1:])]
            lives += [e[1] - e[0] for e in ex]
            healn += [e[2] for e in ex]
            if 1 in r['phase']:
                p2gap.append(r['phase'][1] - ex[0][0])
        # spits
        first_spit, spit_gap, skipped, skip_under, splat_delay, bounce_delay = [], [], 0, 0, [], []
        for r in rooms:
            if 1 not in r['phase'] or not r['spits']:
                continue
            p2t, end = r['phase'][1], r['phase'].get(2, r.get('dead', 10 ** 6))
            first_spit.append(r['spits'][0] - p2t)
            spit_gap += [b - a for a, b in zip(r['spits'], r['spits'][1:])]
            have = set(r['spits'])
            t = r['spits'][0]
            while t < end:
                if t not in have:
                    skipped += 1
                    if any(under(*pp[t]) for pp in r['pos'].values() if t in pp):
                        skip_under += 1
                t += 4
            for sp in r['splats']:
                if sp[1] == 1 and sp[2] is not None:
                    splat_delay.append(sp[0] - sp[2])
                if sp[1] == 2 and sp[2] is not None:
                    bounce_delay.append(sp[0] - sp[2])
        # P3
        first_turn, turn_gap, repeats, seqs = [], [], 0, []
        for r in rooms:
            if 2 not in r['phase'] or not r['turns']:
                continue
            first_turn.append(r['turns'][0][0] - r['phase'][2])
            turn_gap += [b[0] - a[0] for a, b in zip(r['turns'], r['turns'][1:])]
            dirs = [d for _, d in r['turns'] if d]
            repeats += sum(1 for a, b in zip(dirs, dirs[1:]) if a == b)
            if dirs:
                seqs.append(dirs)
        # where they stand: share of P2 ticks under him, per player; tiles at the spit
        under_share, spit_tiles, p3_quarter_ok = [], collections.Counter(), []
        for r in rooms:
            if 1 not in r['phase']:
                continue
            p2t, end = r['phase'][1], r['phase'].get(2, r.get('dead', p2t))
            for name, pp in r['pos'].items():
                ts = [t for t in range(p2t, end) if t in pp]
                if ts:
                    under_share.append(sum(under(*pp[t]) for t in ts) / len(ts))
            for t in r['spits']:
                for pp in r['pos'].values():
                    if t in pp:
                        spit_tiles[(pp[t][0] - X, pp[t][1] - Y)] += 1
        # attacks: weapons by phase
        weapons = collections.Counter()
        for r in rooms:
            for a in r['atk']:
                weapons[a[3]] += 1
        S.update(
            p2=hist(p2), p3_after_p2=hist(p3), exh_count=hist(counts), exh_first=hist(firsts), exh_gap=hist(gaps),
            exh_life=hist(lives), exh_heal_ticks=hist(healn), p2_after_first_exh=hist(p2gap),
            first_spit=hist(first_spit), spit_gap=hist(spit_gap), skipped=skipped, skipped_under=skip_under,
            splat_delay=hist(splat_delay), bounce_delay=hist(bounce_delay), first_turn=hist(first_turn),
            turn_gap=hist(turn_gap), turn_repeats=repeats, turn_seqs=len(seqs),
            under_share_med=med(under_share), under_share_hist=hist([round(u, 1) for u in under_share]),
            spit_tiles=hist([f'{k[0]},{k[1]}' for k in spit_tiles.elements()], 12), weapons=hist(weapons.elements(), 14),
        )
        out[s] = S
        print(f'## Scale {s}: {S["n"]} rooms, {raidtime(S["time"][0])} .. {raidtime(S["time"][-1])} (median {raidtime(med(S["time"]))})\n')
        for k, v in S.items():
            if k not in ('n', 'time'):
                print(f'- {k}: {v}')
        print()
    json.dump(out, open(os.path.join(base, 'xan.json'), 'w'), indent=1, default=str)


def raidtime(t):
    t = int(t)
    cs = t * 60
    return '%d:%02d.%d' % (cs // 6000, cs // 100 % 60, cs // 10 % 10)


if __name__ == '__main__':
    main(sys.argv[1])
