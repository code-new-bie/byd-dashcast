"""Writes the C header with the notice lettering, rendered from the firmware's own bold font.

Usage: notice_glyphs.py msyhbd.ttf notice_glyphs.h

The font comes from Resource_Font/ in your own app.sv. The bitmaps stay out of the repository, so run this once
and pass the header to build.sh. Only the 19 characters of the three notices are kept. Needs Pillow.
"""
import hashlib
import statistics
import sys
from io import BytesIO
from pathlib import Path

SHA256 = "902de9fa292978d04e5052b5005bcc445ebb0907eb83245386e559f3aba732cd"
NOTICES = {"NOTICE_HOST_TEXT": "已切换U盘模式", "NOTICE_NCM_TEXT": "已切换投屏模式", "NOTICE_FAILED_TEXT": "操作失败，请查看日志"}
CELL_W, CELL_H, FONT_SIZE, ROW_BYTES = 32, 40, 30, 4
TEXT_CENTRE, LATIN_CENTRE, COMMA_LEFT = 19.5, 19, 2
NOT_FULL_HEIGHT = "U，"


def draw(font, character):
    from PIL import Image, ImageDraw

    image = Image.new("L", (CELL_W * 3, CELL_H * 3), 0)
    canvas = ImageDraw.Draw(image)
    canvas.fontmode = "1"
    canvas.text((CELL_W, CELL_H), character, 255, font=font)
    return image


def glyph(image, box, shift_x, shift_y):
    bits = bytearray(ROW_BYTES * CELL_H)
    pixels = image.load()
    for y in range(box[1], box[3]):
        for x in range(box[0], box[2]):
            if pixels[x, y]:
                row, column = y + shift_y, x + shift_x
                if not (0 <= row < CELL_H and 0 <= column < CELL_W):
                    raise SystemExit("Glyph does not fit its cell")
                bits[row * ROW_BYTES + (column >> 3)] |= 0x80 >> (column & 7)
    return bits


def main():
    raw = Path(sys.argv[1]).read_bytes()
    if hashlib.sha256(raw).hexdigest() != SHA256:
        raise SystemExit("Exact firmware font required (Resource_Font/msyhbd.ttf)")
    from PIL import ImageFont

    font = ImageFont.truetype(BytesIO(raw), FONT_SIZE, layout_engine=ImageFont.Layout.BASIC)
    used = sorted({c for text in NOTICES.values() for c in text})
    images = {c: draw(font, c) for c in used}
    boxes = {c: image.getbbox() for c, image in images.items()}
    tall = [box for c, box in boxes.items() if c not in NOT_FULL_HEIGHT]
    middle = (statistics.median(b[1] for b in tall) + statistics.median(b[3] - 1 for b in tall)) / 2
    common_shift = int(round(TEXT_CENTRE - middle))
    lines = [f"static const unsigned char NOTICE_GLYPHS[{len(used)}][{ROW_BYTES * CELL_H}] = {{"]
    for c in used:
        box = boxes[c]
        shift_x = COMMA_LEFT - box[0] if c == "，" else (CELL_W - (box[2] - box[0])) // 2 - box[0]
        shift_y = LATIN_CENTRE - (box[1] + box[3] - 1) // 2 if c == "U" else common_shift
        lines.append("    {" + ",".join(str(b) for b in glyph(images[c], box, shift_x, shift_y)) + "},")
    lines.append("};")
    index = {c: i + 1 for i, c in enumerate(used)}
    for name, text in NOTICES.items():
        lines.append(f"static const unsigned char {name}[] = {{" + ",".join(str(index[c]) for c in text) + ",0};")
    Path(sys.argv[2]).write_text("\n".join(lines) + "\n", encoding="ascii")


if __name__ == "__main__":
    main()
