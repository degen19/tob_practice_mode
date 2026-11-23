import json,glob,collections
OVER_X,OVER_Y,W,H=3273,4310,14,15
rows=[]
for f in sorted(glob.glob('blertdata/raids/11_*/*/13.json')):
    try: d=json.load(open(f))
    except: continue
    if not d: continue
    sc=f.split('/')[2]
    atk=[e['tick'] for e in d if e['type']==10]
    procs=[e['tick'] for e in d if e['type']==130]
    ends=[e['tick'] for e in d if e['type']==132]
    pos=collections.defaultdict(dict)
    for e in d:
        if e['type']==4: pos[e['player']['name']][e['tick']]=(e['xCoord'],e['yCoord'])
    for p in procs:
        before=[t for t in atk if t<p]; after=[t for t in atk if t>p]
        lb=before[-1] if before else None; fa=after[0] if after else None
        # when did all players (in room, z<= ... ) reach the top after proc?
        tops=[]
        for n,pp in pos.items():
            under=[t for t,(x,z) in pp.items() if t>p and x>3330]
            back=[t for t,(x,z) in pp.items() if t>p and x<3300 and OVER_Y<=z<OVER_Y+H]
            top=[t for t,(x,z) in sorted(pp.items()) if t>p and x<3300 and z>=OVER_Y+H and (not fa or t<=fa+5)]
            firstback=min(back) if back else None
            tt=[t for t in top if firstback and t>firstback]
            tops.append((n[:4],min(under) if under else None,firstback,min(tt) if tt else None))
        # sote position/hp change
        rows.append((sc,p,'lastAtk',lb,'firstAfter',fa,'cyclePhase',(fa-lb)%5 if fa and lb else None,'ends',ends,tops))
for r in rows: print(r)
