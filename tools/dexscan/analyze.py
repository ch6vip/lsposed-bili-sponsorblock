"""在 index.tsv 上做“类 → 方法集合”的聚合查询。

用法：
    python analyze.py and "<方法签名片段1>" ["<片段2>" ...]   # 同时声明全部片段的类
    python analyze.py or  "<方法签名片段1>" ["<片段2>" ...]   # 至少声明一个片段的类
"""
import collections
import os
import sys

INDEX = os.environ.get("DEX_INDEX", "index.tsv")


def main():
    mode = sys.argv[1]
    sigs = sys.argv[2:]
    classes = collections.defaultdict(set)
    methods = collections.defaultdict(list)
    meta = {}
    with open(INDEX, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if p[0] == "C":
                meta[p[2]] = (p[1], p[3], p[4], p[5])
            elif p[0] == "M" and len(p) >= 6:
                classes[p[2]].add(p[3])
                methods[p[2]].append((p[3], p[4]))

    hits = []
    for cls, sigset in classes.items():
        if mode == "and":
            ok = all(any(s in s2 for s2 in sigset) for s in sigs)
        else:
            ok = any(any(s in s2 for s2 in sigset) for s in sigs)
        if ok:
            hits.append(cls)

    print("hits:", len(hits))
    for cls in sorted(hits):
        m = meta.get(cls, ("?", "?", "?", "?"))
        print("%-95s super=%-45s dex=%s" % (cls, m[1], m[0]))
        for s in sigs:
            for sig, ret in methods[cls]:
                if s in sig:
                    print("      %s -> %s" % (sig, ret))


if __name__ == "__main__":
    main()
