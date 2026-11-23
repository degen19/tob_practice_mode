import json,glob,collections,los as L
exec(open('bloatfit.py').read().split('res=collections.Counter()')[0])
def inroom(p): return 3288<=p[0]<=3303 and 4440<=p[1]<=4455
tanks={'6x6':(3293,3298,4445,4450),'8x8':(3292,3299,4444,4451),'6x6+corners':None}
data={grp:[load(f) for f in sorted(glob.glob(f'blertdata/raids/{grp}/*/11.json'))] for grp in ('11_1','11_[2345]')}
for tn,tb in (('6x6',(3293,3298,4445,4450)),('7x7sw',(3292,3298,4444,4450)),('8x8',(3292,3299,4444,4451)),('4x4',(3294,3297,4446,4449))):
  L.TANK=set((x,z) for x in range(tb[0],tb[1]+1) for z in range(tb[2],tb[3]+1))
  for grp,ds in data.items():
    for mname,m in (('center',lambda b,p: L.los(b[0]+2,b[1]+2,p[0],p[1],1,1)),('nearest5',lambda b,p: L.los(b[0],b[1],p[0],p[1],5,5))):
      c=collections.Counter()
      for B,P,H,walking,splat in ds:
        for name in P:
          for t in sorted(H[name]):
            if t-1 not in H[name] or any(t-j not in walking for j in range(0,3)): continue
            if t-2 not in B or t-2 not in P[name] or not inroom(P[name][t-2]): continue
            if any(P[name].get(t-j) in splat.get(t-j,()) for j in (0,1)): continue
            c[(m(B[t-2],P[name][t-2]),H[name][t]<H[name][t-1])]+=1
      print(tn,grp,mname,'miss',c[(False,True)],'hit',c[(True,True)],'falseLOS',c[(True,False)])
