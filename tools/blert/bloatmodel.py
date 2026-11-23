import json,glob,collections
from los import los
exec(open('bloatfit.py').read().split('res=collections.Counter()')[0])
def inroom(p): return 3288<=p[0]<=3303 and 4440<=p[1]<=4455
models={
 'nearest5': lambda b,p: los(b[0],b[1],p[0],p[1],5,5),
 'sw': lambda b,p: los(b[0],b[1],p[0],p[1],1,1),
 'center': lambda b,p: los(b[0]+2,b[1]+2,p[0],p[1],1,1),
 'any_tile': lambda b,p: any(los(b[0]+i,b[1]+j,p[0],p[1],1,1) for i in range(5) for j in range(5)),
 'center_or_near': lambda b,p: los(b[0]+2,b[1]+2,p[0],p[1],1,1) and los(b[0],b[1],p[0],p[1],5,5),
 'nearest3': lambda b,p: los(b[0]+1,b[1]+1,p[0],p[1],3,3),
}
for grp in ('11_1','11_[2345]'):
  res={k:collections.Counter() for k in models}
  for f in sorted(glob.glob(f'blertdata/raids/{grp}/*/11.json')):
    B,P,H,walking,splat=load(f)
    for name in P:
        for t in sorted(H[name]):
            if t-1 not in H[name] or any(t-k not in walking for k in range(0,3)): continue
            if t-2 not in B or t-2 not in P[name] or not inroom(P[name][t-2]): continue
            if any(P[name].get(t-k) in splat.get(t-k,()) for k in (0,1)): continue
            drop=H[name][t]<H[name][t-1]
            for k,m in models.items(): res[k][(m(B[t-2],P[name][t-2]),drop)]+=1
  for k,c in res.items(): print(grp,k,'drop|noLOS',c[(False,True)],'drop|LOS',c[(True,True)],'noDrop|LOS',c[(True,False)])
