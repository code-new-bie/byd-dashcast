"""RWVD full aperture: translate each side curve outward by 32 native pixels.

Top and footer anchors do not move. The curve's derivatives and corner shape
are unchanged; the wider carrier keeps every newly visible pixel sharp.
"""
from functools import lru_cache
from sharp_inner_geometry import CURVES as ORIGINAL_CURVES, FULL_SAFE

SIDE_OUTSET = 32.
TOP, BOARD_TOP = 124, 553
FULL_INNER = (112, 120, 1696, 440)
FULL_SOURCE = FULL_INNER[2:]
CURVES = tuple(tuple((x-SIDE_OUTSET, y) for x, y in curve) for curve in ORIGINAL_CURVES)

@lru_cache(maxsize=1)
def inner_contour():
    points = []
    for curve in CURVES:
        for i in range(1025):
            t = i/1024; s = 1-t
            points.append(tuple(sum(w*p[k] for w,p in zip((s**3,3*s*s*t,3*s*t*t,t**3),curve))
                                for k in (0,1)))
    rows = []; j = 0
    for y in range(TOP, BOARD_TOP+1):
        while j+1 < len(points) and points[j+1][1] < y:
            j += 1
        a,b = points[j],points[min(len(points)-1,j+1)]
        t = 0 if b[1] == a[1] else (y-a[1])/(b[1]-a[1])
        left = a[0]+t*(b[0]-a[0]); rows.append((left,1920-left))
    return tuple(rows)

def validate_expanded_carrier():
    x,y,w,h = FULL_INNER
    for curve in CURVES:
        for i in range(1025):
            t = i/1024; s = 1-t
            px,py = (sum(weight*p[k] for weight,p in zip((s**3,3*s*s*t,3*s*t*t,t**3),curve)) for k in (0,1))
            if not (x+3 < px < x+w-3 and y+3 < py < y+h-3):
                raise ValueError('Expanded curve exceeds the sharp carrier')
    return True
