"""从 .apks（split APK bundle）里解出 base.apk，并把 base.apk 里的所有 classes*.dex 摊平。

用法：
    python extract_apks.py <bilibili_6.5.0.apks> <输出目录>

产物：
    <输出目录>/base.apk
    <输出目录>/split_config.*.apk
    <输出目录>/dex/classes*.dex
"""
import os
import sys
import zipfile


def main():
    apks, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    with zipfile.ZipFile(apks) as z:
        names = [n for n in z.namelist() if n.endswith(".apk")]
        for n in names:
            z.extract(n, out)
            print("extracted", n)
    base = os.path.join(out, "base.apk")
    dexdir = os.path.join(out, "dex")
    os.makedirs(dexdir, exist_ok=True)
    with zipfile.ZipFile(base) as z:
        dex = [n for n in z.namelist() if n.endswith(".dex")]
        for n in dex:
            z.extract(n, dexdir)
    print("extracted %d dex files into %s" % (len(dex), dexdir))


if __name__ == "__main__":
    main()
