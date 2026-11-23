import json,glob,collections
from los import los
exec(open('bloatfit.py').read().split('res=collections.Counter()')[0])
def inroom(p): return 3288<=p[0]<=3303 and 4440<=p[1]<=4455
al={'B2P2':(2,2),'B1P2':(1,2),'B2P3':(2,3),'B3P3':(3,3),'B1P1':(1,1)}
for grp in ('11_1','11_[2345]'):
  for mname,m in (('center',lambda b,p: los(b[0]+2,b[1]+2,p[0],p[1],1,1)),('nearest5',lambda b,p: los(b[0],b[1],p[0],p[1],5,5))):
    for k,(bo,po) in al.items():
      c=collections.Counter()
      for f in sorted(glob.glob(f'blertdata/raids/{grp}/*/11.json')):
        B,P,H,walking,splat=load(f)
        for name in P:
          for t in sorted(H[name]):
            if t-1 not in H[name] or any(t-j not in walking for j in range(0,4)): continue
            if t-bo not in B or t-po not in P[name] or not inroom(P[name][t-po]): continue
            if any(P[name].get(t-j) in splat.get(t-j,()) for j in (0,1)): continue
            c[(m(B[t-bo],P[name][t-po]),H[name][t]<H[name][t-1])]+=1
      print(grp,mname,k,'miss',c[(False,True)],'hit',c[(True,True)],'falseLOS',c[(True,False)])
