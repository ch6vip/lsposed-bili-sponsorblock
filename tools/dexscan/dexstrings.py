"""打印单个 DEX 的字符串常量表（用于快速确认某个标识符/字段名是否存在）。

用法：
    set PYTHONIOENCODING=utf-8
    python dexstrings.py <classes17.dex> > strings.txt
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dexindex import Dex  # noqa: E402


def main():
    dex = Dex(open(sys.argv[1], "rb").read())
    for i in range(dex.string_ids_size):
        sys.stdout.write(dex.string(i) + "\n")


if __name__ == "__main__":
    main()
