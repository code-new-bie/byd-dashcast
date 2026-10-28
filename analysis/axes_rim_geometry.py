"""RWVE: an exact 8px vertical dilation of the RWVD curved opening.

The original curves are translated, never scaled. A tangent vertical 16px
connector joins the top and lower portions at the side's minimum x coordinate.
"""
from functools import lru_cache
from expanded_rim_geometry import CURVES as PREVIOUS_CURVES, FULL_SAFE, SIDE_OUTSET

TOP_OUTSET = BOTTOM_OUTSET = 8.
TOP, BOARD_TOP, NATIVE_BOARD_TOP = 116,561,553
FULL_INNER = (112,112,1696,456)
FULL_SOURCE = FULL_INNER[2:]

def shifted(curve,delta):
    return tuple((x,y+delta) for x,y in curve)

upper=shifted(PREVIOUS_CURVES[0],-TOP_OUTSET)
lower=tuple(shifted(curve,BOTTOM_OUTSET) for curve in PREVIOUS_CURVES[1:])
start,end=upper[-1],lower[0][0]
assert start[0]==end[0]
join=(start,(start[0],start[1]+(end[1]-start[1])/3),
      (start[0],start[1]+2*(end[1]-start[1])/3),end)
CURVES=(upper,join,*lower)

@lru_cache(maxsize=1)
def inner_contour():
    points=[]
    for curve in CURVES:
        for i in range(1025):
            t=i/1024;s=1-t
            points.append(tuple(sum(w*p[k] for w,p in zip((s**3,3*s*s*t,3*s*t*t,t**3),curve)) for k in (0,1)))
    result=[];j=0
    for y in range(TOP,BOARD_TOP+1):
        while j+1<len(points) and points[j+1][1]<y:
            j+=1
        a,b=points[j],points[min(len(points)-1,j+1)]
        t=0 if b[1]==a[1] else (y-a[1])/(b[1]-a[1])
        left=a[0]+t*(b[0]-a[0]);result.append((left,1920-left))
    return tuple(result)

def validate_axes_carrier():
    x,y,w,h=FULL_INNER
    for curve in CURVES:
        for i in range(1025):
            t=i/1024;s=1-t
            px,py=(sum(weight*p[k] for weight,p in zip((s**3,3*s*s*t,3*s*t*t,t**3),curve)) for k in (0,1))
            if not (x+3<px<x+w-3 and y+3<py<y+h-3):
                raise ValueError('Vertically expanded opening exceeds sharp carrier')
    return True
