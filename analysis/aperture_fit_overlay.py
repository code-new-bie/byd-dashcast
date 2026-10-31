"""1.17: explicit Kanzi content scaling; preserve supplied art and executables."""
import struct
from analyze_kzb_hmi import KzbDirectory,node_head
from navigation_layout import repack,digest,words,srt
from native_background_layout import _text
from display_fusion import encode_node
import user_background_overlay as previous
from user_background_overlay import build_alpha_hmi,build_runtime_hmi,build_plain_scene,build_runtime_receiver,load_backgrounds


def image(d,strings,name,texture,blend):
    encoded=encode_node(d,strings,name,[
        ('Node.Width',struct.pack('<f',960)),('Node.Height',struct.pack('<f',360)),
        ('Node2D.RenderTransformation',srt(0,0,2)),('BlendMode',words(blend)),
        ('Image2D.Image',_text('kzb://bic_byd_stha/Textures/'+texture)),
        ('Node.ContentStretch',words(1))])
    return encoded[:8]+words(d.dictionary('/$metaclass_dictionary').index('Kanzi.Image2D'))+encoded[12:]


def frame(d,strings,name,mode):
    return encode_node(d,strings,name,[('Node.Width',struct.pack('<f',1920)),
        ('Node.Height',struct.pack('<f',720)),('Node.Opacity',struct.pack('<f',0)),
        ('Node.Visible',words(0))],[image(d,strings,'Backing','DXFullLight',4),
        image(d,strings,'Contour','DX'+mode+'Dark',5)])


def build_runtime_kzb(original,toc,background_dir=None):
    base,prior=previous.build_runtime_kzb(original,toc,background_dir)
    if digest(base)!='d8a0e60b13e6976e8a466ed1418273b5d7d5c1cdb958fa56348e168a8be342d7':
        raise ValueError('Exact saved 1.16 KZB required')
    d=KzbDirectory(base);strings=d.dictionary('/$strings');raw=d.read('/StartupPrefab')
    start=1855+node_head(d,raw,405)['end_properties']-476
    def end_node(at):
        head=node_head(d,raw,at);cursor=head['end_properties'];count=struct.unpack_from('<I',raw,cursor)[0]
        assert count<=4;cursor+=4
        for _ in range(count):cursor=end_node(cursor)
        assert struct.unpack_from('<III',raw,cursor)==(0,0,strings.index(''))
        return cursor+12
    middle=end_node(start);end=end_node(middle)
    assert raw[start:middle]==previous.frame(d,strings,'IETop','Full')
    assert raw[middle:end]==previous.frame(d,strings,'IEBottom','Center')
    result=repack(d,{'/StartupPrefab':raw[:start]+frame(d,strings,'IETop','Full')+
        frame(d,strings,'IEBottom','Center')+raw[end:]},{})
    saved=KzbDirectory(result);changed=[n for n in d.records if d.read(n)!=saved.read(n)]
    assert changed==['/StartupPrefab'] and saved.records.keys()==d.records.keys()
    return result,dict(prior=prior,output_sha256=digest(result),changed_since_1160=changed,
        explicit_content_stretch_fill=1,all_four_owned_image_nodes=True,
        supplied_pngs_theme_geometry_and_executables_unchanged=True,vehicle=False)
