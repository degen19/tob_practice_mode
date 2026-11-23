TANK=set((x,z) for x in range(3293,3299) for z in range(4445,4451))
def blocked(x,z): return (x,z) in TANK
def coord(a,b,size):
    if a>=b: return a
    if a+size-1<=b: return a+size-1
    return b
def los(sx,sz,dx,dz,sw=5,sl=5):
    x0=coord(sx,dx,sw); z0=coord(sz,dz,sl); x1=dx; z1=dz
    if (x0,z0)==(x1,z1): return True
    if blocked(x0,z0): return False
    ddx=x1-x0; ddz=z1-z0
    east=ddx>=0; north=ddz>=0
    if abs(ddx)>abs(ddz):
        ox=1 if east else -1; oz=0 if north else -1
        sc=(z0<<16)+(1<<15)+oz; tan=int((ddz<<16)/abs(ddx)) if ddz>=0 else -int(((-ddz)<<16)//abs(ddx))
        tan=(ddz<<16)//abs(ddx) if ddz>=0 else -((-ddz<<16)//abs(ddx))
        cx=x0
        while cx!=x1:
            cx+=ox; cz=sc>>16
            if blocked(cx,cz) and not (cx==x1 and cz==z1): return False
            sc+=tan; nz=sc>>16
            if nz!=cz and blocked(cx,nz) and not (cx==x1 and nz==z1): return False
    else:
        ox=0 if east else -1; oz=1 if north else -1
        sc=(x0<<16)+(1<<15)+ox
        tan=(ddx<<16)//abs(ddz) if ddx>=0 else -((-ddx<<16)//abs(ddz))
        cz=z0
        while cz!=z1:
            cz+=oz; cx=sc>>16
            if blocked(cx,cz) and not (cx==x1 and cz==z1): return False
            sc+=tan; nx=sc>>16
            if nx!=cx and blocked(nx,cz) and not (nx==x1 and cz==z1): return False
    return True
