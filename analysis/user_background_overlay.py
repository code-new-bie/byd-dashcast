"""Reproduce the user's RGBA overlays above video, below native information.

The supplied RGB is constant per theme and the two theme alpha channels match.
Draw an opaque theme backing, then the exact shared full-size alpha using the
existing destination-in mode. This replaces the old background instead of
merely attenuating its colour/shadows. No new executable/GL mode is required.
"""
import hashlib
import io
import struct
from pathlib import Path

from PIL import Image
from analyze_kzb_hmi import KzbDirectory, Cursor, custom_types, STANDARD_NODE_TYPES, node_head
from axes_rim_layout import FRAME_TOP, FRAME_BOTTOM
from display_fusion import encode_node
from native_background_layout import _owned_frame, _owned_end, _text
from native_background_masks import (build_alpha_hmi, build_plain_scene, build_runtime_receiver,
                                     build_runtime_kzb as previous_kzb)
from navigation_layout import dictionary, repack, words, srt, digest

ROOT = Path(__file__).resolve().parents[1]
BASE_KZB_SHA = '42706b7b86eb1ed2cde0d82aeb970b57a04f919409d399a6a9961b253be67c69'
FILES = {
    ('Full', 'Dark'): ('仪表轮廓_深色.png', 'full-dark.png',
        '058c12def19b93ff433964d247898e7fc729a61b247076ab8341d7f443e7c411'),
    ('Full', 'Light'): ('仪表轮廓_浅色.png', 'full-light.png',
        'a9163305594485444bc4d624f73eb658f66ea33d5c35fa96f036c1ae99540e61'),
    ('Center', 'Dark'): ('矩形_深色.png', 'center-dark.png',
        '3d1afb21cdff2da37b035606d16a08ed66f40cc50e575cf2dd6275c388b32a59'),
    ('Center', 'Light'): ('矩形_浅色.png', 'center-light.png',
        '4866ac38362995ec9d1c91bb701ca9fbb7d56007efff639e40541229c25a4670'),
}
SLOTS = {'Dark': 'DXFullLight', 'Light': 'DXCenterLight'}
build_runtime_hmi = build_alpha_hmi


def load_backgrounds(folder=None):
    folder = Path(folder) if folder is not None else (ROOT / 'bg' if (ROOT / 'bg').is_dir()
                                                   else ROOT / 'assets/backgrounds')
    images, raw, evidence = {}, {}, []
    for key, (original, public, expected) in FILES.items():
        candidates = [p for p in (folder / original, folder / public) if p.is_file()]
        if len(candidates) != 1:
            raise ValueError('One exact owned background required: ' + public)
        data = candidates[0].read_bytes()
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError('Review changed user artwork before building: ' + public)
        with Image.open(io.BytesIO(data)) as saved:
            if saved.mode != 'RGBA' or saved.size != (1920, 720):
                raise ValueError('Exact 1920x720 straight RGBA input required')
            image = saved.copy()
        extrema = image.getextrema()
        if any(lo != hi for lo, hi in extrema[:3]) or extrema[3] != (0, 255):
            raise ValueError('Exact constant-theme RGB/shared-alpha artwork required')
        images[key], raw[key] = image, data
        evidence.append(dict(mode=key[0], theme=key[1], source=public, sha256=expected,
                             rgb=[channel[0] for channel in extrema[:3]], dimensions=[1920, 720]))
    for mode in ('Full', 'Center'):
        if images[mode, 'Dark'].getchannel('A').tobytes() != images[mode, 'Light'].getchannel('A').tobytes():
            raise ValueError('Theme alpha geometry must match')
    for theme in ('Dark', 'Light'):
        if images['Full', theme].getpixel((0, 0))[:3] != images['Center', theme].getpixel((0, 0))[:3]:
            raise ValueError('Full/center theme colours must match')
    return images, raw, evidence


def image_node(directory, strings, name, texture, blend):
    raw = encode_node(directory, strings, name, [
        ('Node.Width', struct.pack('<f', 960)), ('Node.Height', struct.pack('<f', 360)),
        ('Node2D.RenderTransformation', srt(0, 0, 2)), ('BlendMode', words(blend)),
        ('Image2D.Image', _text('kzb://bic_byd_stha/Textures/' + texture))])
    return raw[:8] + words(directory.dictionary('/$metaclass_dictionary').index('Kanzi.Image2D')) + raw[12:]


def frame(directory, strings, name, mode):
    return encode_node(directory, strings, name, [
        ('Node.Width', struct.pack('<f', 1920)), ('Node.Height', struct.pack('<f', 720)),
        ('Node.Opacity', struct.pack('<f', 0)), ('Node.Visible', words(0))], [
        image_node(directory, strings, 'Backing', SLOTS['Dark'], 4),
        image_node(directory, strings, 'Contour', 'DX' + mode + 'Dark', 5)])


def theme_manager(directory, strings):
    """Keep original theme transitions; only owned image targets are rewritten/added."""
    raw = directory.read('/State Managers/Bg_RootPage State Manager')
    cursor = Cursor(raw)
    if [cursor.word() for _ in range(4)] != [0, 0, 0, 2]:
        raise ValueError('Exact original two theme groups required')
    cursor.word()
    props, types = directory.dictionary('/$property_dictionary'), custom_types(directory)
    if props[cursor.word()] != 'state2':
        raise ValueError('Original native theme driver changed')
    cursor.word()
    if cursor.word() != 3:
        raise ValueError('Original three theme states required')
    result = bytearray(raw[:cursor.offset])
    contour = {'Content/' + name + '/Contour': mode for name, mode in
               ((FRAME_TOP, 'Full'), (FRAME_BOTTOM, 'Center'))}
    for expected in range(3):
        name, value, count = cursor.word(), cursor.word(), cursor.word()
        if value != expected:
            raise ValueError('Original theme state order changed')
        result += words(name, value, count + 2)
        seen = set()
        for _ in range(count):
            target, properties = cursor.word(), cursor.word()
            target_name = strings[target]
            result += words(target, properties)
            for _ in range(properties):
                key = cursor.word()
                begin = cursor.offset
                cursor.variant(types[props[key]]['kind'] if props[key] in types else STANDARD_NODE_TYPES[key])
                payload = raw[begin:cursor.offset]
                if target_name in contour and props[key] == 'Image2D.Image':
                    payload = _text('kzb://bic_byd_stha/Textures/DX' + contour[target_name] + 'Dark')
                    seen.add(target_name)
                result += words(key) + payload
            begin = cursor.offset
            if [cursor.word() for _ in range(3)] != [0, 0, 0]:
                raise ValueError('Original theme actions changed')
            result += raw[begin:cursor.offset]
        if seen != set(contour):
            raise ValueError('Both owned mask targets must be present')
        texture = SLOTS['Light' if value == 1 else 'Dark']
        for root in (FRAME_TOP, FRAME_BOTTOM):
            result += words(strings.index('Content/' + root + '/Backing'), 1, props.index('Image2D.Image'))
            result += _text('kzb://bic_byd_stha/Textures/' + texture) + words(0, 0, 0)
    return bytes(result) + raw[cursor.offset:]


def build_runtime_kzb(original, toc, background_dir=None):
    images, source, evidence = load_backgrounds(background_dir)
    base, prior = previous_kzb(original, toc)
    if digest(base) != BASE_KZB_SHA:
        raise ValueError('Exact 1.15.0 saved KZB baseline required')
    directory = KzbDirectory(base)
    strings = directory.dictionary('/$strings')
    raw = directory.read('/StartupPrefab')
    start = 1855 + node_head(directory, raw, 405)['end_properties'] - 476
    middle = _owned_end(directory, raw, start, FRAME_TOP)
    end = _owned_end(directory, raw, middle, FRAME_BOTTOM)
    if raw[start:middle] != _owned_frame(directory, strings, FRAME_TOP, 'Full') or \
            raw[middle:end] != _owned_frame(directory, strings, FRAME_BOTTOM, 'Center'):
        raise ValueError('Exact previous two mask frames required')
    for name in ('Backing', 'Content/' + FRAME_TOP + '/Backing', 'Content/' + FRAME_BOTTOM + '/Backing'):
        if name in strings:
            raise ValueError('Owned backing node already exists')
        strings.append(name)
    changes = {'/$strings': dictionary(strings),
        '/StartupPrefab': raw[:start] + frame(directory, strings, FRAME_TOP, 'Full') +
            frame(directory, strings, FRAME_BOTTOM, 'Center') + raw[end:],
        '/State Managers/Bg_RootPage State Manager': theme_manager(directory, strings)}
    # Share exact full-size alpha between themes. The formerly duplicated light
    # mask slots become tiny opaque backings. Four texture slots remain four;
    # total owned texture bytes grow by 5.27 MiB, rather than 15.82 MiB.
    pngs = {'DXFullDark': source['Full', 'Dark'], 'DXCenterDark': source['Center', 'Dark']}
    for theme, slot in SLOTS.items():
        buffer = io.BytesIO()
        # Match the panel's 8:3 aspect even if Image2D uses aspect fitting.
        Image.new('RGBA', (8, 3), (*images['Full', theme].getpixel((0, 0))[:3], 255)).save(buffer, 'PNG')
        pngs[slot] = buffer.getvalue()
    for name, png in pngs.items():
        changes['/Resource Files/Images/' + name + '.png'] = words(0, 0, 0, 0, len(png)) + png
    output = repack(directory, changes, {})
    saved = KzbDirectory(output)
    actual = [name for name in directory.records if directory.read(name) != saved.read(name)]
    if set(actual) != set(changes) or saved.records.keys() != directory.records.keys():
        raise ValueError('Unrelated resource or topology changed')
    return output, dict(prior=prior, input_sha256=digest(original), output_sha256=digest(output),
        changed_resources=actual, supplied_backgrounds=evidence,
        shared_mask_slots=['DXFullDark', 'DXCenterDark'], theme_backing_slots=SLOTS,
        exact_1920x720_alpha_no_resampling=True, owned_texture_slots=4,
        owned_texture_rgba_bytes=2 * 1920 * 720 * 4 + 2 * 8 * 3 * 4,
        drawing_order=['original background', 'opaque owned theme backing',
                       'destination-in supplied alpha', 'native foreground'],
        active_background_rgb_and_alpha_match_supplied_artwork=True,
        idle_background_scene_and_information_unchanged=True,
        executable_window_loader_and_wire_protocol_unchanged=True, vehicle=False)
