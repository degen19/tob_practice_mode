import json,glob,collections,sys
def hp(v): return (v>>16, v&0xffff)
for f in sorted(glob.glob('blertdata/raids/11_*/*/13.json')):
    try: d=json.load(open(f))
    except: continue
    if not d: continue
    sc=f.split('/')[2]
    atk=[(e['tick'],e['npcAttack']['attack'],e['npcAttack'].get('target')) for e in d if e['type']==10]
    mz=[(e['tick'],e['type'],e.get('soteMaze')) for e in d if e['type'] in (130,131,132)]
    types=collections.Counter(e['type'] for e in d)
    # hp at maze procs
    npchp={}
    for e in d:
        if e['type']==8 and 'hitpoints' in e.get('npc',{}): npchp[e['tick']]=hp(e['npc']['hitpoints'])
    out=[]
    for t,ty,m in mz:
        if ty==130: out.append((t,npchp.get(t),npchp.get(t-1),npchp.get(t+1)))
    seq=''.join({7:'M',8:'o',9:'D'}.get(a,'?') for _,a,_ in atk)
    ticks=[t for t,_,_ in atk]
    gaps=[b-a for a,b in zip(ticks,ticks[1:])]
    print(sc,f.split('/')[3][:8],'first',ticks[:1],'mazes',out,'types',dict(types))
    print('   seq',seq)
    print('   gaps',collections.Counter(gaps).most_common(6), 'big', [(ticks[i],g) for i,g in enumerate(gaps) if g>10])
