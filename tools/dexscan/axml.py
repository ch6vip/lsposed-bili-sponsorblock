"""Minimal Android binary XML (AXML) parser -> prints elements/attributes."""
import struct, sys

RES_STRING_POOL_TYPE = 0x0001
RES_XML_TYPE = 0x0003
RES_XML_START_ELEMENT_TYPE = 0x0102
RES_XML_END_ELEMENT_TYPE = 0x0103
RES_XML_RESOURCE_MAP_TYPE = 0x0180

TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10

ATTR_TYPES = {
    0x01: "ref", 0x02: "attr", 0x03: "string", 0x04: "float", 0x05: "dim",
    0x06: "fraction", 0x07: "dynamic_ref", 0x08: "int_dec", 0x10: "int_hex",
    0x11: "int_bool", 0x12: "int_color",
}


class Pool:
    def __init__(self, data, off):
        (typ, hsz, sz) = struct.unpack_from("<HHI", data, off)
        string_count, style_count, flags, strings_start, styles_start = struct.unpack_from("<IIIII", data, off + 8)
        utf8 = bool(flags & (1 << 8))
        offsets = struct.unpack_from("<%dI" % string_count, data, off + hsz)
        base = off + strings_start
        self.strings = []
        for o in offsets:
            p = base + o
            if utf8:
                # u16len (may be 2 bytes), u8len, bytes, 0
                n = data[p]
                if n & 0x80:
                    n = ((n & 0x7F) << 8) | data[p + 1]
                    p += 2
                else:
                    p += 1
                ln = data[p]
                if ln & 0x80:
                    ln = ((ln & 0x7F) << 8) | data[p + 1]
                    p += 2
                else:
                    p += 1
                self.strings.append(data[p:p + ln].decode("utf-8", "replace"))
            else:
                n = struct.unpack_from("<H", data, p)[0]
                p += 2
                if n & 0x8000:
                    n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, p)[0]
                    p += 4
                self.strings.append(data[p:p + n * 2].decode("utf-16-le", "replace"))
    def get(self, i):
        return self.strings[i] if 0 <= i < len(self.strings) else "?"


def parse(path):
    data = open(path, "rb").read()
    (typ, hsz, sz) = struct.unpack_from("<HHI", data, 0)
    assert typ == RES_XML_TYPE, hex(typ)
    off = hsz
    pool = None
    names = {}
    depth = 0
    while off < len(data):
        (t, hs, ss) = struct.unpack_from("<HHI", data, off)
        if t == RES_STRING_POOL_TYPE:
            pool = Pool(data, off)
        elif t == RES_XML_RESOURCE_MAP_TYPE:
            cnt = (ss - hs) // 4
            ids = struct.unpack_from("<%dI" % cnt, data, off + hs)
            for i, rid in enumerate(ids):
                names[rid] = pool.get(i)
        elif t == RES_XML_START_ELEMENT_TYPE:
            ns, name = struct.unpack_from("<ii", data, off + hs)
            attr_start, attr_size, attr_count = struct.unpack_from("<HHH", data, off + hs + 8)
            ap = off + hs + attr_start
            attrs = []
            for _ in range(attr_count):
                ans, aname, araw, afword, adata = struct.unpack_from("<iiiII", data, ap)
                atyp = (afword >> 24) & 0xFF
                if atyp == TYPE_STRING:
                    val = pool.get(adata)
                elif atyp == TYPE_INT_DEC:
                    val = adata if adata < 2**31 else adata - 2**32
                elif atyp == 0x11:
                    val = bool(adata)
                elif atyp == 0x10 or atyp == 0x12:
                    val = hex(adata)
                else:
                    val = "%s:%s" % (ATTR_TYPES.get(atyp, hex(atyp)), adata)
                attrs.append((names.get(aname, pool.get(aname)), val))
                ap += 20
            print("  " * depth + "<%s %s>" % (pool.get(name), " ".join('%s="%s"' % a for a in attrs)))
            depth += 1
        elif t == RES_XML_END_ELEMENT_TYPE:
            depth -= 1
        off += ss


if __name__ == "__main__":
    parse(sys.argv[1])
