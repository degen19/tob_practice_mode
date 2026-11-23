import json,glob,collections
from los import los
exec(open('bloatfit.py').read().split('res=collections.Counter()')[0])
stat=collections.Counter()
for f in sorted(glob.glob('blertdata/raids/11_[2345]/*/11.json')):
    B,P,H,walking,splat=load(f)
    for t in sorted(B):
        if t not in walking or t-1 not in walking or t-1 not in B: continue
        inl=[]
        for name in P:
            if t not in H[name] or t-1 not in H[name] or t-2 not in P[name]: continue
            if any(P[name].get(t-k) in splat.get(t-k,()) for k in (0,1)): continue
            s=los(*B[t-1],*P[name][t-2]); drop=H[name][t]<H[name][t-1]
            inl.append((s,drop))
        k=sum(s for s,_ in inl)
        for s,d in inl: stat[(k, s, d)]+=1
for k,v in sorted(stat.items()): print(k,v)
