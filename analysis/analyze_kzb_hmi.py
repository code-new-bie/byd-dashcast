"""Bounded KZBF3 primitives, not a generic Kanzi loader or vehicle certification."""
import struct

class KzbDirectory:
    def __init__(self, data):
        self.data = data
        if len(data) < 32 or data[:8] != b"KZBF\x03\0\0\0":
            raise ValueError("Expected inspected KZBF version 3")
        project_length = self.word(8)
        if not 1 <= project_length <= 1024:
            raise ValueError("Unexpected project name length")
        cursor = 12 + project_length
        self.project = data[12:cursor].decode("utf-8")
        count = self.word(cursor)
        cursor += 4
        if not 1 <= count <= 10000:
            raise ValueError("Unexpected directory count")
        names = []
        for _ in range(count):
            end = data.find(b"\0", cursor, min(len(data), cursor + 4096))
            if end < 0:
                raise ValueError("Unterminated resource path")
            names.append(data[cursor:end].decode("utf-8"))
            cursor = end + 1
        if len(set(names)) != count or self.word(cursor) != count:
            raise ValueError("Directory names/index count mismatch")
        cursor += 4
        self.records = {}
        for index, name in enumerate(names):
            if cursor + 24 > len(data):
                raise ValueError("Truncated resource index")
            resource_id, class_id, offset, size, flags, decoded_size = struct.unpack_from("<6I", data, cursor)
            cursor += 24
            if resource_id != index or offset > len(data) or size > len(data) - offset:
                raise ValueError("Resource index identity/range mismatch")
            self.records[name] = dict(id=index, class_id=class_id, offset=offset,
                                      size=size, flags=flags, decoded_size=decoded_size)
        if any(row["offset"] < cursor for row in self.records.values()):
            raise ValueError("Resource overlaps the directory")

    def word(self, offset):
        if offset < 0 or offset + 4 > len(self.data):
            raise ValueError("Read escaped the KZB buffer")
        return struct.unpack_from("<I", self.data, offset)[0]

    def read(self, name):
        row = self.records[name]
        if row["flags"] != 0 or row["decoded_size"] != row["size"]:
            raise ValueError("Compressed/unknown resource needs explicit decoding")
        return self.data[row["offset"]:row["offset"] + row["size"]]

    def dictionary(self, name):
        data = self.read(name)
        count = struct.unpack_from("<I", data)[0]
        items = data[4:].split(b"\0", count)
        if len(items) != count + 1 or items[-1]:
            raise ValueError("Unexpected string dictionary encoding")
        return [item.decode("utf-8") for item in items[:-1]]

class Cursor:
    def __init__(self, data, offset=0):
        self.data = data
        self.offset = offset

    def read(self, count):
        if count < 0 or self.offset + count > len(self.data):
            raise ValueError("Serialized value escaped its resource")
        value = self.data[self.offset:self.offset + count]
        self.offset += count
        return value

    def word(self):
        return struct.unpack("<I", self.read(4))[0]

    def variant(self, kind):
        # rc_hmi.exe loadVariantValue jump table at 0x0827215a determines these sizes.
        if kind in (0, 1, 2):
            raw = self.read(4)
            return struct.unpack("<f" if kind == 0 else "<i", raw)[0]
        if kind in (3, 4, 5, 6, 7, 8, 12, 13):
            count = {3: 4, 4: 2, 5: 3, 6: 4, 7: 9, 8: 16, 12: 5, 13: 9}[kind]
            return list(struct.unpack("<" + "f" * count, self.read(count * 4)))
        if kind in (9, 11):
            return self.read(self.word()).decode("utf-8")
        raise ValueError("Unsupported/reserved property type: " + str(kind))

def custom_types(directory):
    strings = directory.dictionary("/$strings")
    cursor = Cursor(directory.read("/$property_types"))
    result = {}
    for _ in range(cursor.word()):
        # rc_hmi loadCustomPropertyTypes: loadString followed by type + flags + inheritance.
        name = strings[cursor.word()]
        kind, flags, inherited = cursor.word(), cursor.word(), cursor.word()
        value = cursor.variant(kind)
        if name in result:
            raise ValueError("Duplicate custom property type")
        result[name] = dict(kind=kind, flags=flags, inherited=inherited, default=value)
    if cursor.offset != len(cursor.data):
        raise ValueError("Custom type decoder did not consume the resource")
    return result

STANDARD_NODE_TYPES = {
    1: 11, 2: 9, 3: 3, 4: 0, 5: 1, 6: 1, 7: 0, 8: 3, 10: 0,
    11: 11, 12: 11, 13: 2, 14: 9, 15: 2, 16: 11, 17: 11, 18: 9,
    19: 1, 20: 1, 22: 9, 25: 12, 28: 12, 31: 2, 34: 0,
    54: 4, 57: 0, 58: 13, 64: 0, 72: 11, 73: 11, 74: 3, 75: 1,
    78: 11, 95: 4, 109: 11, 110: 4, 111: 4, 112: 3, 115: 11,
    124: 4, 125: 11, 126: 0, 128: 11, 129: 0, 130: 1, 131: 9,
    132: 1, 133: 1, 135: 0, 136: 1, 137: 2, 138: 4, 139: 4,
    140: 2, 141: 0, 142: 1, 143: 2, 144: 1, 145: 2, 146: 11,
    147: 0, 149: 2, 157: 0, 159: 1, 160: 3, 161: 11, 162: 11,
    163: 11, 164: 11, 165: 1, 166: 0, 167: 1, 168: 2, 169: 0,
    170: 11, 171: 1, 172: 11, 173: 1, 176: 11, 180: 4,
    220: 1, 221: 1, 225: 0, 226: 2, 227: 0, 231: 0, 232: 0,
    283: 0, 284: 1, 285: 0, 286: 1, 287: 0, 288: 0, 291: 11,
    292: 0, 293: 0, 294: 0, 295: 0, 296: 0, 303: 2, 323: 0, 385: 11,
}

def node_head(directory, data, offset):
    strings = directory.dictionary("/$strings")
    classes = directory.dictionary("/$metaclass_dictionary")
    names = directory.dictionary("/$property_dictionary")
    kinds = custom_types(directory)
    cursor = Cursor(data, offset)
    name_id, base_id, class_id = cursor.word(), cursor.word(), cursor.word()
    name = strings[name_id]
    base = "" if base_id == 0xffffffff else strings[base_id]
    if class_id == 0xffffffff and base.startswith("kzb://"):
        class_name = "InheritedPrefab"
    else:
        class_name = classes[class_id]
    count = cursor.word()
    if count > 128 or not class_name.startswith(("Kanzi.", "Rc.", "InheritedPrefab")):
        raise ValueError("Not a supported node header")
    properties = {}
    for _ in range(count):
        key = cursor.word()
        property_name = names[key]
        kind = kinds[property_name]["kind"] if property_name in kinds else STANDARD_NODE_TYPES[key]
        if property_name in properties:
            raise ValueError("Duplicate property value")
        properties[property_name] = cursor.variant(kind)
    binding_count = cursor.word()
    if binding_count > 128:
        raise ValueError("Not a bounded property-binding block")
    bindings = {}
    for _ in range(binding_count):
        key = names[cursor.word()]
        bindings[key] = cursor.read(cursor.word()).decode("utf-8")
    return dict(name=name, base=base, node_class=class_name, offset=offset,
                end_properties=cursor.offset, properties=properties, bindings=bindings)
