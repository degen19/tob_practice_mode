import json,glob,collections
from los import los
exec(open('bloatfit.py').read().split('res=collections.Counter()')[0])
stat=collections.defaultdict(lambda:[0,0]); since=collections.defaultdict(lambda:[0,0])
for f in sorted(glob.glob('blertdata/raids/11_*/*/11.json')):
    B,P,H,walking,splat=load(f)
    for name in P:
        run=0; lasthit=-99
        for t in sorted(H[name]):
            if t not in walking or t-1 not in walking or t-1 not in B or t-2 not in P[name] or t-1 not in H[name]: run=0; continue
            if any(P[name].get(t-k) in splat.get(t-k,()) for k in (0,1)): continue
            s=los(*B[t-1],*P[name][t-2]); drop=H[name][t]<H[name][t-1]
            run=run+1 if s else 0
            if s:
                stat[min(run,8)][0]+=1; stat[min(run,8)][1]+=drop
                g=min(t-lasthit,12); since[g][0]+=1; since[g][1]+=drop
            if drop and s: lasthit=t
for k in sorted(stat): print('run',k,stat[k], round(stat[k][1]/stat[k][0],2))
for k in sorted(since): print('since last hit',k,since[k], round(since[k][1]/since[k][0],2))
