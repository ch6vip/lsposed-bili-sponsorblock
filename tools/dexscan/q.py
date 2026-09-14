"""在 dexindex.py 生成的 index.tsv 上做多正则查询（大小写敏感，符合 DEX 描述符语义）。

用法：
    python q.py <正则1> [正则2 ...]
    set DEX_INDEX=D:\\path\\index.tsv   # 可选，默认 index.tsv

示例：
    python q.py "^C\\t\\S+\\tLtv/danmaku/biliplayerv2/service/D;"
    python q.py "^M\\t\\S+\\tLcom/bilibili/playerbizcommonv2/widget/base/PlayerProgressTextWidget;"
"""
import os
import re
import sys

INDEX = os.environ.get("DEX_INDEX", "index.tsv")
LIMIT = int(os.environ.get("DEX_INDEX_LIMIT", "40"))

pats = [(p, re.compile(p)) for p in sys.argv[1:]]
counts = {p: 0 for p in sys.argv[1:]}
with open(INDEX, "r", encoding="utf-8", errors="replace") as f:
    for line in f:
        for p, rx in pats:
            if rx.search(line):
                counts[p] += 1
                if counts[p] <= LIMIT:
                    sys.stdout.write("[%s] %s" % (p, line))
for p in sys.argv[1:]:
    sys.stdout.write("### %s -> %d hits\n" % (p, counts[p]))
