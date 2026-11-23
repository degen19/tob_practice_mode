import json,glob,collections
from los import los
exec(open('bloatfit.py').read().split('res=collections.Counter()')[0])
def inroom(p): return 3288<=p[0]<=3303 and 4440<=p[1]<=4455
tot=collections.Counter()
for f in sorted(glob.glob('blertdata/raids/11_*/*/11.json')):
    B,P,H,walking,splat=load(f)
    for name in P:
        out=[];sp=[]
        for t in sorted(H[name]):
            ok = t in walking and t-1 in B and t-2 in B and t-2 in P[name] and t-1 in H[name] and inroom(P[name][t-2])
            if ok:
                s=los(*B[t-1],*P[name][t-2]); d=H[name][t]-H[name][t-1]
                hand=any(P[name].get(t-k) in splat.get(t-k,()) for k in (0,1))
                spd=max(abs(B[t-1][0]-B[t-2][0]),abs(B[t-1][1]-B[t-2][1]))
                if not hand: tot[(s,d<0,spd)]+=1
                c=('h' if hand else ('X' if d<0 else 'o')) if s else ('h' if hand else ('x' if d<0 else '.'))
            if ok and (s or d<0): out.append(c); sp.append(str(spd))
            else:
                if len(out)>5: print(f.split('/')[2],name[:5],''.join(out),''.join(sp))
                out=[];sp=[]
for k,v in sorted(tot.items()): print(k,v)
