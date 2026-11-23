import json,glob,collections
from los import los
exec(open('bloatfit.py').read().split('res=collections.Counter()')[0])
cnt=collections.Counter()
for f in sorted(glob.glob('blertdata/raids/11_1/*/11.json')):
    B,P,H,walking,splat=load(f)
    for name in P:
        for t in sorted(H[name]):
            if t-1 not in H[name] or t not in walking or t-1 not in walking or (t-1) not in B or (t-2) not in P[name]: continue
            s=los(*B[t-1],*P[name][t-2]); drop=H[name][t]<H[name][t-1]
            if s:
                bx,by=B[t-1]; px,py=P[name][t-2]
                cnt[(bx-3288,by-4440,px-3288,py-4440,drop, B.get(t-2) and (B[t-1][0]-B[t-2][0],B[t-1][1]-B[t-2][1]))]+=1
for k,v in sorted(cnt.items()): print(k,v)
