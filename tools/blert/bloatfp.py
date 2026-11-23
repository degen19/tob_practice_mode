import json,glob,collections
from los import los
exec(open('bloatfit.py').read().split('res=collections.Counter()')[0])
files=sorted(glob.glob('blertdata/raids/11_1/*/11.json'))
for f in files:
    B,P,H,walking,splat=load(f)
    for name in P:
        run=0; line=[]
        for t in sorted(H[name]):
            if t-1 not in H[name] or t not in walking or t-1 not in walking or (t-1) not in B or (t-2) not in P[name]: 
                if line: print(f.split('/')[3][:6],name[:5],' '.join(line)); line=[]
                run=0; continue
            s=los(*B[t-1],*P[name][t-2])
            drop=H[name][t]<H[name][t-1]
            hand=any(P[name].get(t-k) in splat.get(t-k,()) for k in (0,1))
            if s or drop:
                run=run+1 if s else 0
                line.append(f"{t}:{'L' if s else '-'}{'D' if drop else '.'}{'h' if hand else ''}({H[name][t-1]}>{H[name][t]})")
            else:
                if line: print(f.split('/')[3][:6],name[:5],' '.join(line)); line=[]
                run=0
        if line: print(f.split('/')[3][:6],name[:5],' '.join(line))
