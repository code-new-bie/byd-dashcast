"""Dependency-free contract for a sharp carrier covering the entire inner rim.

Safe UI bounds are guidance for application adapters, never a compositing clip.
"""
FULL_INNER=(144,120,1632,440)
FULL_SOURCE=FULL_INNER[2:]
FULL_SAFE=(320,176,1280,336)
CURVES=(
    ((450.,124.),(340.,124.),(150.,158.),(150.,226.)),
    ((150.,226.),(150.,320.),(218.,477.),(239.,510.)),
    ((239.,510.),(260.,543.),(270.,553.),(310.,553.)),
)
def validate_sharp_carrier():
    x,y,w,h=FULL_INNER
    assert FULL_SAFE!=FULL_INNER
    for curve in CURVES:
        for i in range(1025):
            t=i/1024;s=1-t
            a,b=(sum(weight*point[k] for weight,point in zip((s**3,3*s*s*t,3*s*t*t,t**3),curve)) for k in (0,1))
            # A few native pixels include the saved texture's LINEAR footprint.
            assert x+3<a<1920-x-3 and y+3<b<y+h-3,(a,b)
    return True
