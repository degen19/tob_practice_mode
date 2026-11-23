import json,glob,collections
from los import los
exec(open('bloatfit.py').read().split('res=collections.Counter()')[0])
def inroom(p): return 3288<=p[0]<=3303 and 4440<=p[1]<=4455
al={'A B1P2':(1,2),'C B2P3':(2,3),'D B2P2':(2,2),'E B1P1':(1,1),'F B3P3':(3,3),'G B2P4':(2,4),'H B3P4':(3,4)}
for grp in ('11_1','11_[2345]'):
  res={k:collections.Counter() for k in al}
  for f in sorted(glob.glob(f'blertdata/raids/{grp}/*/11.json')):
    B,P,H,walking,splat=load(f)
    for name in P:
        for t in sorted(H[name]):
            if t-1 not in H[name] or any(t-k not in walking for k in range(0,4)): continue
            if any(P[name].get(t-k) in splat.get(t-k,()) for k in (0,1)): continue
            drop=H[name][t]<H[name][t-1]
            for k,(b,p) in al.items():
                if t-b not in B or t-p not in P[name] or not inroom(P[name][t-p]): continue
                s=los(*B[t-b],*P[name][t-p])
                res[k][(s,drop)]+=1
  for k,c in res.items():
    print(grp,k,'drop|noLOS',c[(False,True)],'drop|LOS',c[(True,True)],'noDrop|LOS',c[(True,False)])
