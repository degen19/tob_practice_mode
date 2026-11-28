import re,sys,collections
f=sys.argv[1]
quad=any(' Freezer' in l for l in open(f,errors='ignore'))
sw=collections.defaultdict(lambda: collections.defaultdict(set)); res={}; c=collections.Counter()
for l in open(f,errors='ignore'):
    m=re.match(r'maiden (\S+) t(\d+) (Ranger|Melee|Mage) swung (\d+)',l)
    if m: sw[m[1]][m[3]].add((int(m[2]),m[4]))
    m=re.search(r'maiden trio (\S+): Maiden dead at room tick (\d+).*leaked (\d+)',l)
    if m: res[m[1]]=(int(m[2]),int(m[3]))
    m=re.match(r'maiden (\S+) t(\d+) Mage@(\d+),(\d+) (tbr)',l)
    if m and int(m[2])>55: c['mage tbows>55']+=1; c['close']+= int(m[3])<=11
    if 'waits to swing on' in l: c['waits']+=1
    if 'steps back to its tbow' in l: c['stepback']+=1
rd=md=0
for r in sw:
    R={t for t,_ in sw[r]['Ranger']}; M={t for t,_ in sw[r]['Melee']}
    if any(t not in R for t in M if t>14) or any(t not in M for t in R if t>14): rd+=1
    ph={t%5 for t in R|M if t>14}
    tb=[t for t,w in sw[r]['Mage'] if w=='20997' and t>14]
    if any(t%5 not in ph for t in tb): md+=1
n=len(res)
if quad:
    print(f,'rooms',n,'mean tick %.1f'%(sum(v[0] for v in res.values())/n),'leaks %.2f'%(sum(v[1] for v in res.values())/n),'(4s: the tick and up-close checks are trios only)')
    sys.exit()
print(f,'rooms',n,'mean tick %.1f'%(sum(v[0] for v in res.values())/n),'leaks %.2f'%(sum(v[1] for v in res.values())/n),'ranger/melee desync rooms',rd,dict(c))
