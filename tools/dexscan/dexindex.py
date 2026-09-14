"""把一个目录下的 DEX 文件导成可 grep 的类/方法/字段索引（TSV）。

用法：
    python dexindex.py <dex目录> <输出 index.tsv>

输出行格式（\\t 分隔）：
    C  <dex名>  <类描述符>            <父类>       <接口列表>  <访问标志>
    M  <dex名>  <类描述符>  <方法名(参数)>  <返回类型>  <访问标志>
    F  <dex名>  <类描述符>  <字段名>  <字段类型>  <访问标志>

纯标准库实现，不依赖 apktool / jadx / androguard。
"""
import struct
import sys
import os
import glob

ACC = [(0x1, "public"), (0x2, "private"), (0x4, "protected"), (0x8, "static"),
       (0x10, "final"), (0x200, "interface"), (0x400, "abstract"), (0x1000, "synthetic"),
       (0x10000, "constructor")]


def uleb(data, off):
    result = 0
    shift = 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            break
        shift += 7
    return result, off


def access_str(flags):
    return "|".join(n for b, n in ACC if flags & b)


class Dex:
    def __init__(self, data):
        self.d = data
        (self.string_ids_size, self.string_ids_off, self.type_ids_size, self.type_ids_off,
         self.proto_ids_size, self.proto_ids_off, self.field_ids_size, self.field_ids_off,
         self.method_ids_size, self.method_ids_off, self.class_defs_size, self.class_defs_off) = \
            struct.unpack_from("<12I", data, 56)
        self._strings = {}

    def string(self, i):
        s = self._strings.get(i)
        if s is None:
            off = struct.unpack_from("<I", self.d, self.string_ids_off + i * 4)[0]
            _, off = uleb(self.d, off)
            end = self.d.index(b"\x00", off)
            s = self.d[off:end].decode("utf-8", "replace")
            self._strings[i] = s
        return s

    def type(self, i):
        return self.string(struct.unpack_from("<I", self.d, self.type_ids_off + i * 4)[0])

    def proto(self, i):
        _, ret, params_off = struct.unpack_from("<3I", self.d, self.proto_ids_off + i * 12)
        params = []
        if params_off:
            size = struct.unpack_from("<I", self.d, params_off)[0]
            params = [self.type(struct.unpack_from("<H", self.d, params_off + 4 + j * 2)[0])
                      for j in range(size)]
        return self.type(ret), params

    def method(self, i):
        cls_idx, proto_idx, name_idx = struct.unpack_from("<2HI", self.d, self.method_ids_off + i * 8)
        ret, params = self.proto(proto_idx)
        return self.type(cls_idx), self.string(name_idx), ret, params

    def field(self, i):
        cls_idx, type_idx, name_idx = struct.unpack_from("<2HI", self.d, self.field_ids_off + i * 8)
        return self.type(cls_idx), self.string(name_idx), self.type(type_idx)


def dump(path, out):
    data = open(path, "rb").read()
    dex = Dex(data)
    name = os.path.basename(path)
    for ci in range(dex.class_defs_size):
        base = dex.class_defs_off + ci * 32
        class_idx, flags, super_idx, ifaces_off, _src, _ann, cd_off, _sv = \
            struct.unpack_from("<8I", data, base)
        cls = dex.type(class_idx)
        sup = dex.type(super_idx) if super_idx != 0xFFFFFFFF else "-"
        ifaces = []
        if ifaces_off:
            n = struct.unpack_from("<I", data, ifaces_off)[0]
            ifaces = [dex.type(struct.unpack_from("<H", data, ifaces_off + 4 + j * 2)[0])
                      for j in range(n)]
        out.write("C\t%s\t%s\t%s\t%s\t%s\n" % (name, cls, sup, ",".join(ifaces), access_str(flags)))
        if not cd_off:
            continue
        off = cd_off
        sf, off = uleb(data, off)
        inf, off = uleb(data, off)
        dm, off = uleb(data, off)
        vm, off = uleb(data, off)
        # 字段：static 与 instance 各自的 index_diff 都从 0 起算
        for idx_kind in range(2):
            fidx = 0
            for _ in range(sf if idx_kind == 0 else inf):
                diff, off = uleb(data, off)
                acc, off = uleb(data, off)
                fidx += diff
                _fcls, fname, ftype = dex.field(fidx)
                out.write("F\t%s\t%s\t%s\t%s\t%s\n" % (name, cls, fname, ftype, access_str(acc)))
        # 方法：direct 与 virtual 各自的 index_diff 也都从 0 起算
        for count in (dm, vm):
            idx = 0
            for _ in range(count):
                diff, off = uleb(data, off)
                acc, off = uleb(data, off)
                code_off, off = uleb(data, off)
                idx += diff
                _mcls, mname, ret, params = dex.method(idx)
                out.write("M\t%s\t%s\t%s(%s)\t%s\t%s\n" % (
                    name, cls, mname, ",".join(params), ret,
                    access_str(acc) + ("|code" if code_off else "")))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    src, dst = sys.argv[1], sys.argv[2]
    with open(dst, "w", encoding="utf-8") as out:
        for f in sorted(glob.glob(os.path.join(src, "*.dex"))):
            dump(f, out)
            print("indexed", f, flush=True)
