import json,glob,collections
from los import los
exec(open('bloatfit.py').read().split('res=collections.Counter()')[0])
n=0
for f in sorted(glob.glob('blertdata/raids/11_[345]/*/11.json')):
    B,P,H,walking,splat=load(f)
    for name in P:
        out=[]
        for t in sorted(H[name]):
            if t not in walking or t-1 not in B or t-2 not in P[name] or t-1 not in H[name]: 
                if len(out)>6: print(name[:5],''.join(out)); n+=1
                out=[]; continue
            s=los(*B[t-1],*P[name][t-2]); d=H[name][t]-H[name][t-1]
            hand=any(P[name].get(t-k) in splat.get(t-k,()) for k in (0,1))
            c=('h' if hand else ('X' if d<0 else 'o')) if s else ('h' if hand else ('x' if d<0 else '.'))
            if s or d<0: out.append(c)
            else:
                if len(out)>6: print(name[:5],''.join(out)); n+=1
                out=[]
        if n>40: break
    if n>40: break
