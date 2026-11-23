import json,glob,collections
from los import los
exec(open('bloatfit.py').read().split('res=collections.Counter()')[0])
def inroom(p): return 3288<=p[0]<=3303 and 4440<=p[1]<=4455
for f in sorted(glob.glob('blertdata/raids/11_[345]/*/11.json')):
    B,P,H,walking,splat=load(f)
    names=sorted(P)
    rows=[]
    for t in sorted(B):
        if t-2 not in B or t not in walking or t-1 not in walking: 
            if len(rows)>4: print(f.split('/')[2],f.split('/')[3][:6]); [print('  ',r) for r in rows]
            rows=[]; continue
        cells=[];anyl=0
        for n in names:
            if t not in H[n] or t-1 not in H[n] or t-2 not in P[n] or not inroom(P[n][t-2]): cells.append(' '); continue
            s=los(*B[t-2],*P[n][t-2]); d=H[n][t]<H[n][t-1]
            hand=any(P[n].get(t-k) in splat.get(t-k,()) for k in (0,1))
            anyl+=s
            cells.append('h' if hand else ('X' if s and d else 'o' if s else 'x' if d else '.'))
        if anyl>=2 or (rows and anyl>=1): rows.append(f"{t:4} {''.join(cells)}")
        elif len(rows)>4: print(f.split('/')[2],f.split('/')[3][:6]); [print('  ',r) for r in rows]; rows=[]
        else: rows=[]
