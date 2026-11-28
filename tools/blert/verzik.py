"""The fastest Verzik rooms on Blert, per scale: splits, Verzik's tiles and attacks, the players' tiles and attacks.

python3 verzik.py summary            splits and phase lengths per scale (data/verzik/<scale>/, fetch_verzik.sh)
python3 verzik.py procedure out.json the per-scale procedure the server and verzikSim read
"""
import json
import os
import statistics
import sys
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, 'data', 'verzik')
DEFS = os.path.join(HERE, 'defs')

PLAYER_UPDATE, PLAYER_ATTACK, NPC_SPAWN, NPC_UPDATE, NPC_DEATH, NPC_ATTACK, PLAYER_SPELL = 4, 5, 7, 8, 9, 10, 11
OVERHEADS = [(16, 'magic'), (17, 'missiles'), (18, 'melee'), (21, 'retribution'), (22, 'redemption'), (23, 'smite')]
PHASE, REDS, YELLOWS, BOUNCE, HEAL, DAWN, DAWN_DROP = 150, 151, 153, 154, 155, 156, 157

ATTACKS = {a['protoId']: a for a in json.load(open(os.path.join(DEFS, 'attack_definitions.json')))}
SPELLS = {s['id']: s for s in json.load(open(os.path.join(DEFS, 'spell_definitions.json')))}
NPCS = {n['id']: n for n in json.load(open(os.path.join(DEFS, 'npc_definitions.json')))}
NPC_ATTACKS = {12: 'P1_AUTO', 13: 'P2_BOUNCE', 14: 'P2_CABBAGE', 15: 'P2_ZAP', 16: 'P2_PURPLE', 17: 'P2_MAGE',
               18: 'P3_AUTO', 19: 'P3_MELEE', 20: 'P3_RANGE', 21: 'P3_MAGE', 22: 'P3_WEBS', 23: 'P3_YELLOWS',
               24: 'P3_BALL'}


def npc_name(nid):
    n = NPCS.get(nid)
    return n['name'].replace('_REGULAR', '').split('_REGULAR_')[0] if n else str(nid)


def raids(scale):
    d = os.path.join(DATA, str(scale))
    index = json.load(open(os.path.join(d, 'index.json')))
    for c in index:
        f = os.path.join(d, c['uuid'] + '.json')
        if not os.path.exists(f):
            continue
        ev = json.load(open(f))
        if isinstance(ev, list) and ev:
            yield c, ev


def phases(ev):
    """Phase points: P2 and P3 start ticks, reds spawn, room end."""
    out = {'end': max(e['tick'] for e in ev)}
    for e in ev:
        if e['type'] == PHASE:
            out['P%d' % e['verzikPhase']] = e['tick']
        elif e['type'] == REDS and 'reds' not in out:
            out['reds'] = e['tick']
    return out


def summary():
    for scale in (2, 3, 4, 5):
        rows = [phases(ev) for _, ev in raids(scale)]
        def med(f):
            v = [f(r) for r in rows if f(r) is not None]
            return (statistics.median(v), min(v), max(v), len(v)) if v else None
        print('scale', scale, 'raids', len(rows))
        print('  room   ', med(lambda r: r['end']))
        print('  P1     ', med(lambda r: r.get('P2')))
        print('  reds   ', med(lambda r: r.get('reds') and r.get('P2') and r['reds'] - r['P2']))
        print('  P2     ', med(lambda r: r.get('P3') and r.get('P2') and r['P3'] - r['P2']))
        print('  P3     ', med(lambda r: r.get('P3') and r['end'] - r['P3']))


def gear_deltas(raw):
    out = []
    for r in raw:
        quantity, added = r & 0x7fffffff, bool(r & (1 << 31))
        item, slot = (r >> 32) & 0xffff, (r >> 48) & 0x1f
        out.append((slot, item if added else -1, quantity))
    return out


def replay(chal, ev):
    """One raid as the server replays it. Tiles are the room's real coordinates; ticks start at 0."""
    end = max(e['tick'] for e in ev)
    names, tiles, gear, prayers, php = [], [], [], [], []
    by_name = {}
    for e in sorted((e for e in ev if e['type'] == PLAYER_UPDATE), key=lambda e: e['tick']):
        n = e['player']['name']
        if n not in by_name:
            by_name[n] = len(names)
            names.append(n)
            tiles.append([None] * (end + 1))
            php.append([-1] * (end + 1))
            gear.append([])
            prayers.append([])
        i = by_name[n]
        tiles[i][e['tick']] = (e['xCoord'], e['yCoord'])
        if 'hitpoints' in e['player']:
            php[i][e['tick']] = e['player']['hitpoints'] >> 16
        for slot, item, qty in sorted(gear_deltas(e['player'].get('equipmentDeltas') or []), key=lambda d: d[1] >= 0):
            gear[i].append([e['tick'], slot, item])
        ps = e['player'].get('prayerSet')
        if ps is not None:
            head = next((name for bit, name in OVERHEADS if ps >> 50 == 0 and ps >> bit & 1), '')
            if (prayers[i][-1][1] if prayers[i] else '') != head:
                prayers[i].append([e['tick'], head])
    for row in tiles:
        first = next((t for t in row if t is not None), (0, 0))
        last = first
        for k in range(len(row)):
            last = row[k] = row[k] or last
    npcs, room_index = [], {}
    for e in sorted((e for e in ev if e['type'] in (NPC_SPAWN, NPC_UPDATE, NPC_DEATH)), key=lambda e: e['tick']):
        rid = e['npc']['roomId']
        if rid not in room_index:
            room_index[rid] = len(npcs)
            npcs.append({'spawn': e['tick'], 'death': None, 'track': []})
        n = npcs[room_index[rid]]
        hp = e['npc'].get('hitpoints', 0)
        row = [e['tick'], e['npc']['id'], e['xCoord'], e['yCoord'], hp >> 16, hp & 0xffff]
        if not n['track'] or n['track'][-1][1:] != row[1:]:
            n['track'].append(row)
        if e['type'] == NPC_DEATH:
            n['death'] = e['tick']
    attacks = []
    for e in ev:
        if e['type'] == PLAYER_ATTACK:
            a = e['attack']
            target = room_index.get((a.get('target') or {}).get('roomId'), -1)
            d = ATTACKS.get(a['type'], {})
            anim = (d.get('animationIds') or [-1])[0]
            attacks.append([e['tick'], by_name[e['player']['name']], d.get('name', 'UNKNOWN'), anim,
                            (a.get('weapon') or {}).get('id', -1), target])
    spells = []
    for e in ev:
        if e['type'] == PLAYER_SPELL and e['player']['name'] in by_name:
            d = SPELLS.get(e['spell']['type'], {})
            gfx = (d.get('graphics') or [{}])[0].get('id', -1)
            target = by_name.get((e['spell'].get('target') or {}).get('name'), -1)
            spells.append([e['tick'], by_name[e['player']['name']], d.get('name', 'UNKNOWN'),
                           (d.get('animationIds') or [-1])[0], gfx, target])
    her = [[e['tick'], room_index.get(e['npc']['roomId'], -1), NPC_ATTACKS.get(e['npcAttack']['attack'], 'UNKNOWN'),
            by_name.get(e['npcAttack'].get('target'), -1)] for e in ev if e['type'] == NPC_ATTACK]
    drops = [[e['tick'], e['xCoord'], e['yCoord'], 1 if e['verzikDawnDrop'].get('dropped') else 0] for e in ev if e['type'] == DAWN_DROP]
    dawns = [[e['verzikDawn']['attackTick'], by_name.get(e['verzikDawn'].get('player'), -1), e['verzikDawn'].get('damage', 0)]
             for e in ev if e['type'] == DAWN]
    yellows = [[e['tick'], [[p['x'], p['y']] for p in e['verzikYellows']]] for e in ev if e['type'] == YELLOWS]
    marks = []
    for e in ev:
        if e['type'] == PHASE:
            marks.append([e['tick'], 'P%d' % e['verzikPhase']])
        elif e['type'] == BOUNCE:
            marks.append([e['tick'], 'bounce'])
        elif e['type'] == HEAL:
            marks.append([e['tick'], 'heal'])
    return {
        'uuid': chal['uuid'], 'scale': chal['scale'], 'end': end, 'players': len(names),
        'tiles': [[c for xy in row for c in xy] for row in tiles], 'hp': php, 'gear': gear, 'prayers': prayers,
        'drops': sorted(drops), 'dawns': sorted(dawns), 'yellows': yellows,
        'npcs': npcs, 'attacks': sorted(attacks), 'spells': sorted(spells), 'her': sorted(her), 'marks': sorted(marks),
    }


def export(out):
    """All four scales' raids, fastest Verzik first: {scale: [raid, ...]} (gzip JSON)."""
    import gzip
    data = {}
    for scale in (2, 3, 4, 5):
        rs = [replay(c, ev) for c, ev in raids(scale)]
        data[str(scale)] = sorted(rs, key=lambda r: r['end'])
        print(scale, len(rs), 'raids, fastest', rs[0]['end'] if rs else None)
    blob = json.dumps(data, separators=(',', ':')).encode()
    with gzip.open(out, 'wb', 9) as f:
        f.write(blob)
    print(out, len(blob), 'bytes raw', os.path.getsize(out), 'gz')


if __name__ == '__main__':
    {'summary': summary, 'export': export}[sys.argv[1]](*sys.argv[2:])
