# tools/dexscan — 目标 APK 静态分析工具

这组脚本用来在没有 jadx / apktool 的机器上，把 B 站目标 APK 摊成**可 grep 的类/方法/字段索引**，
以便核对 Hook 点是否还存在、类名是否被混淆改名。纯 Python 标准库，零依赖。

## 依赖

- Python 3.9+
- 可选：`aapt2`（来自 Android SDK build-tools），用于读 manifest 的包名 / versionName / 进程列表

## 复现步骤（以 `bilibili_6.5.0.apks` 为例）

```powershell
# 1. 解包 .apks → base.apk → dex/
#    产物较大（318MB base.apk / 约 250MB dex），请放在仓库外的工作目录
python tools\dexscan\extract_apks.py <APK目录>\bilibili_6.5.0.apks <工作目录>

# 2. manifest 基本信息（包名 / versionCode / versionName / minSdk / targetSdk / 启动 Activity）
& "$env:ANDROID_SDK\build-tools\36.0.0\aapt2.exe" dump badging <工作目录>\base.apk

# 3. 宿主进程列表
& "$env:ANDROID_SDK\build-tools\36.0.0\aapt2.exe" dump xmltree --file AndroidManifest.xml <工作目录>\base.apk `
  | Select-String "android:process"

# 4. 建索引（约 1~2 分钟，产出 ~330MB TSV）
python tools\dexscan\dexindex.py <工作目录>\dex <工作目录>\index.tsv

# 5. 查询（DEX 描述符大小写敏感，PowerShell 里含 $ 的正则要用单引号）
$env:DEX_INDEX = "<工作目录>\index.tsv"
python tools\dexscan\q.py '^C\t\S+\tLcom/bilibili/playerbizcommonv2/widget/seek/v3/g;'
python tools\dexscan\q.py '^M\t\S+\tLtv/danmaku/biliplayerv2/service/D;\t\w+\(\)\t(I|Z)'

# 6. 需要看某个方法集合的类（例如“同时声明 onCreate/onStart/onDestroy 的类”）
python tools\dexscan\analyze.py and "onCreate(Landroid/os/Bundle;)V" "onStart()V" "onDestroy()V"

# 7. 需要确认某个标识符是否存在（例如 getLogDescription）
python tools\dexscan\dexstrings.py <工作目录>\dex\classes26.dex > strings26.txt
Select-String -Path strings26.txt -Pattern "LogDescription"
```

## 字节码级交叉引用

`dexindex.py` 只导签名，不导字节码。需要看“谁 new 了哪个类”时用 SDK 自带的 `dexdump`：

```powershell
& "C:\android-sdk\build-tools\36.0.0\dexdump.exe" -d dex\classes17.dex > d17.txt   # ~220MB 文本
Select-String -Path d17.txt -Pattern "new-instance.*seek/v3/g;" -Context 4,4
```

`tools/dexscan` 不包含 `ctx.py`（带上下文的 grep 小工具）这类一次性脚本；
如果需要，直接按上面 `Select-String -Context` 的写法即可。

## 输出格式

```
C  <dex>  <类描述符>            <父类>         <接口列表>  <访问标志>
M  <dex>  <类描述符>  <方法名(参数…)>  <返回类型>  <访问标志>
F  <dex>  <类描述符>  <字段名>   <字段类型>    <访问标志>
```

访问标志里带 `|code` 表示有方法体实现（抽象方法没有）。

## 为什么不用 jadx / apktool

- 33 个 dex、约 250MB，整套反编译耗时且产物巨大；核对“类名/方法名是否还在”用索引足够快。
- 只有在需要读具体实现语义（例如判断某个 drawable 画的是什么）时，才针对单个类做 `dexdump -d` 或 jadx 单类反编译。
