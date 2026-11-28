"""Blert-shaped Xarpus events (Blert's or the server's .data/recordings) -> the replay page's compact room format.

python3 -I pack.py out.json label=path[,scale] ...   (path: a Blert stage-14 JSON array, or a server .jsonl recording)
"""
import json
import sys

KIND = {38: 'S', 85: 'EM', 84: 'em', 11: 'BGS', 62: 'Ra', 63: 'ra', 58: 'ZCB', 57: 'zcb', 20: 'C', 19: 'c', 14: 'Ch', 12: 'BP',
        54: 'TB', 89: 'BC'}
TURNS = {12: 'NE', 13: 'SE', 14: 'SW', 15: 'NW', 25: 'NE', 26: 'SE', 27: 'SW', 28: 'NW'}
WEAPON = {22325: 'S', 25736: 'S', 25739: 'S', 21003: 'EM', 11804: 'BGS', 20370: 'BGS', 28922: 'Ra', 26374: 'ZCB',
          13652: 'C', 28039: 'C', 23987: 'Ch'}


def load(path):
    if path.endswith('.jsonl'):
        return [json.loads(l) for l in open(path) if l.strip()]
    return json.load(open(path))


def pack(ev, label, scale=None):
    names, n = [], 0
    for e in ev:
        if e['type'] == 4 and e['player']['name'] not in names:
            names.append(e['player']['name'])
        n = max(n, e['tick'] + 1)
    P = [[None] * len(names) for _ in range(n)]
    X = [None] * n
    out = {'label': label, 'scale': scale or len(names), 'names': names, 'ticks': n, 'atk': [], 'spits': [], 'turns': [],
           'splats': [], 'exh': [], 'p2': None, 'p3': None, 'dead': None}
    swings = any(e['type'] == 5 for e in ev)
    for e in ev:
        t, ty = e['tick'], e['type']
        if ty == 1000 and swings:
            continue
        if ty == 4:
            hp = e['player'].get('hitpoints')
            P[t][names.index(e['player']['name'])] = [e['xCoord'], e['yCoord'], (hp >> 16) if hp else None, (hp & 0xffff) if hp else None]
        elif ty in (7, 8):
            hp = e['npc'].get('hitpoints') or 0
            X[t] = [e['xCoord'], e['yCoord'], e['npc']['id'], hp >> 16, hp & 0xffff]
        elif ty == 9:
            out['dead'] = t
        elif ty == 5:
            a = e['attack']
            k = KIND.get(a['type']) or WEAPON.get(a.get('weapon', {}).get('id')) or '?'
            out['atk'].append([t, names.index(e['player']['name']), k])
        elif ty == 1000:
            h = e['hit']
            if h['player'] in names:
                out['atk'].append([t, names.index(h['player']), WEAPON.get(h['weapon'], 'hit'), h['damage']])
        elif ty == 10:
            a = e['npcAttack']['attack']
            if a == 10:
                out['spits'].append(t)
            elif a == 11 or a in TURNS:
                out['turns'].append([t, TURNS.get(a)])
        elif ty == 140:
            out['p2' if e['xarpusPhase'] == 1 else 'p3'] = t
        elif ty == 141:
            x = e['xarpusExhumed']
            out['exh'].append([x['spawnTick'], e['xCoord'], e['yCoord'], x.get('healTicks') or []])
        elif ty == 142:
            s = e['xarpusSplat']
            b = s.get('bounceFrom') or {}
            out['splats'].append([t, e['xCoord'], e['yCoord'], s.get('source'), s.get('startTick'), b.get('x'), b.get('y')])
    for t in range(1, n):
        if X[t] is None:
            X[t] = X[t - 1]
        for i in range(len(names)):
            if P[t][i] is None and P[t - 1][i] is not None:
                P[t][i] = P[t - 1][i]
    out['p'], out['x'] = P, X
    return out


def main():
    rooms = []
    for arg in sys.argv[2:]:
        label, rest = arg.split('=', 1)
        path, _, scale = rest.partition(',')
        rooms.append(pack(load(path), label, int(scale) if scale else None))
    json.dump(rooms, open(sys.argv[1], 'w'), separators=(',', ':'))


if __name__ == '__main__':
    main()
