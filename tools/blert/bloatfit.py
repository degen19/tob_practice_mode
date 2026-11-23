import json,glob,collections,itertools
from los import los
def load(f):
    d=json.load(open(f))
    B={};P=collections.defaultdict(dict);H=collections.defaultdict(dict);down=set();splat={}
    downs=[(e['tick'],e['bloatDown']) for e in d if e['type']==110]; ups=[e['tick'] for e in d if e['type']==111]
    for e in d:
        if e['type'] in (7,8) and 8350<=e['npc']['id']<=8370: B[e['tick']]=(e['xCoord'],e['yCoord'])
        if e['type']==4 and 'hitpoints' in e['player']: 
            P[e['player']['name']][e['tick']]=(e['xCoord'],e['yCoord']); H[e['player']['name']][e['tick']]=e['player']['hitpoints']>>16
        if e['type']==113: splat[e['tick']]=set((h['x'],h['y']) for h in e['bloatHands'])
    # walk ticks: not between down and up
    end=max(B) if B else 0
    walking=set(range(0,end+1))
    for (t,_),u in itertools.zip_longest(downs,ups,fillvalue=None):
        walking-=set(range(t,(u if u else end)+1))
    return B,P,H,walking,splat
res=collections.Counter(); tot=collections.Counter()
files=sorted(glob.glob('blertdata/raids/11_1/*/11.json'))
data=[load(f) for f in files]
for db in range(-2,3):
  for dp in range(-2,3):
    for dl in range(0,4):
      agree=n=0; tp=fp=fn=0
      for B,P,H,walking,splat in data:
        for name in P:
          for t in sorted(H[name]):
            if t-1 not in H[name] or t not in walking or (t-dl) not in walking: continue
            if any(P[name].get(t-k) in splat.get(t-k,()) for k in (0,1)): continue
            drop=H[name][t]<H[name][t-1]
            bt=t-dl+db; pt=t-dl+dp
            if bt not in B or pt not in P[name]: continue
            s=los(*B[bt],*P[name][pt])
            n+=1; agree+= (s==drop); tp+=s and drop; fp+=s and not drop; fn+=(not s) and drop
      res[(db,dp,dl)]=(agree/n if n else 0, n, tp,fp,fn)
for k,v in sorted(res.items(),key=lambda kv:-kv[1][0])[:12]: print(k,v)
