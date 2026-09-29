# Agent Note: B站 6.6.0 适配——六个失效点的重定位过程与教训

Status: implemented
Date: 2026-09-29
关联: HANDOVER_6.6.0.md（上一会话的静态核对）、docs/APK_6.6.0_ANALYSIS.md（事实文档）

## 背景

宿主 6.5.0 → 6.6.0（versionCode 9130300）。上一会话产出交接文档，列出 6 个失效点与建议改法。
本轮按字节码逐项核实后落地，其中**两条交接结论被推翻**，另发现一个交接没列的坑（同名类跨版本互换角色）。

## 六个点的最终结论（与交接文档的差异）

1. **观察者注册（主链路）**：交接判断「注册入口迁到 service.D0/D0#J6#i7」——**方向反了，目标也错了**。
   - 字节码实测：`D0.J6(E0)` = `g.remove(observer)`（注销），`D0.i7(E0)` = `contains→add`（注册），
     而且全部 l0/z0 调用点在各自服务的 `onStart`/`onStop`（EJ1.b、service.D0、StatisticsService 等 5 对）；
     `D0.e()` 遍历观察者链表只做「needHideSimpleProgressNormalCase / getSegmentSwitchValue」查询
     （字符串池实证），D0 是 **SeekService**，与视频切换分发无关。
   - 真正的漂移：`PlayDirectorServiceV3#j0(E0)` → **`l0(F0)`**（注销仍叫 z0），回调接口 E0 → **F0**
     （a/b/c/e 形状不变，父接口 z0→A0）。`PlayDirectorServiceV3#D()`（当前视频）→ **`F()`**。
   - 教训：**交接文档的静态结论也可能有方向性错误**；注册/注销方向必须靠「方法体字节码 +
     全部调用点的上下文（onStart/onStop）」定，不能按接口声明顺序猜。
2. **容器类型 f→h**：交接正确。`h extends f`（f 还在），`bindPlayerContainer` 参数改传 h，
   `h#t()` 取 Context 保留。候选表 f/h 并列即可。
3. **更多面板 f0→e0**：交接正确。6.5.0 只有 f0、6.6.0 只有 e0，无重载歧义。
4. **ip1.h 消失**：交接建议重定位——**不必**。`ip1.h` 在 **6.5.0 索引同样是 0 命中**
   （它是 6.3.0 形态），6.5.0 基线 33/38 里 `ip.mossScope` 本来就是 MISS，
   评论区限定靠 `header.b#b` scope 标记 + `grpc.c#f` 主改写覆盖，真机已验证。
   按交接的备选方案（评估既有路径是否覆盖）结案：覆盖。
5. **头包装类型提示 kr1.g/kr1.k**：交接正确，新家是 **`xr1.g`/`xr1.k`**，且挂在父类
   `MossInterceptor$e` 的字段 b/a 上（grpc.c 自身字段与 6.5.0 相同）。`fieldTypedAnyHint`
   沿类层级查找，补类型名即可。
6. **Router/BLRouter**：交接判断正确且无需改码——6.5.0 上这两类名本来就不存在
   （uriRouter 探针靠 IntentHandlerActivity 命中），6.6.0 一致（blrouter 包 445 类还在）。

## 交接没列的坑：同名类跨版本互换角色（E0/F0 撞名）

- 6.5.0：观察者接口叫 **E0**；**F0** 是无关的媒体资源接口（j(I,I,I)→MediaResource）。
- 6.6.0：观察者接口叫 **F0**；**E0** 变成空标记接口（SeekService 观察者）。
- 因此「按候选名顺序取第一个能加载的类」在 6.6.0 会把代理实现到空 E0 上 → 静默收不到回调。
- 解法：**「方法能解析 ⇔ 接口正确」自洽校验**——对每个候选接口 iface 试
  `forTarget(服务, ADD 候选, iface)`，能解析出注册方法的 (方法, 接口) 对即正身，代理实现该接口。
  实现在 `VideoDirectorListener.registerDirectorService`。
- 泛化：**候选表的类名维度的解析可以撞名，方法签名维度不会**——凡是「先选类再配方法」的解析，
  都值得检查一遍是否存在跨版本撞名空间。

## 工具/方法教训（本轮新增，进 docs/APK_6.6.0_ANALYSIS.md §4）

1. **index.tsv 列分隔是 TAB**：`名(参数)` 与返回列之间是 `\t` 不是空格。写
   `名\(\)\t返回类型` 型正则时把分隔符写成空格，会得到「全索引 0 命中」的假阴性
   （本轮差点据此误判 D() 链路全灭）。宁可先 `^M\t...;\t名\(` 确认行存在再查返回类型。
   另：`q.py` 查 `f(String,[B)` 这种签名时别忘了参数间的逗号（本轮第一版正则漏了逗号，
   误报「主改写路径没了」，虚惊）。
2. **dexdump 大 dex 丢 class_data 但保留方法体清单**（classes28 的 D0/PlayDirectorServiceV3
   类块缺失）：`grep -n "类名.方法名:" dump.txt` 定位 `[addr] class.method:(sig)` 头，
   `sed` 取块即可看到字节码，不必换工具。
3. **dex 号不可信**：dex 大迁移（17→18/28、21→11），一切以全索引检索为准。
4. **真机黑屏先查屏幕本身**：MIUI adb 驱动测试时熄屏极快（约 15s），`screencap` 全黑
   包括状态栏 = 屏幕锁定/熄灭，与页面渲染无关。判据：黑屏截图里**状态栏时钟可见**才是
   真页面黑屏；`svc power stayon usb` 先设再测。锁屏（指纹）adb 无法越过，需人工解锁。

## 落地改动清单

- `host/HostTargets.kt`：+J(int 进度)、+g0(long 进度)、容器接口 {f,h}、+l0(注册)、
  观察者接口 {E0,F0,legacy}、当前视频 {D,F}、面板刷新 {f0,e0}——全部旧候选保留。
- `player/VideoDirectorListener.kt`：观察者接口自洽解析（见上）。
- `BiliSponsorBlockHooks.kt`：director/容器两处形状校验谓词改集合判断。
- `hook/MorePanelInjector.kt`：刷新方法按候选列表匹配。
- `hook/IpLocationHooks.kt`：+xr1.g / +xr1.k 类型提示。
- 版本 0.7.0 / versionCode 10（build.gradle.kts + module.prop）。

## 验证状态（2026-09-29，两轮）

**第一轮（0.7.0 首版）**：单测 133/133 绿；真机安装期探针 32/37 + 2 延迟 MISS，与 6.5.0 基线
33/38 逐键对账等价（+cleartextPolicy 新 OK、−Gemini k0 与 −seekTrack:f 为宿主侧消失），
主链路 0 MISS。但**运行时详情页/搜索页黑屏 + 输入 ANR**。

**根因（当晚定位，是模块自己的回归）**：第二轮审查把 `registerDirectorService` 的弱表
`registeredHosts.add`（先登记后 invoke）改成 `observersByService` map 时，把去重挪到了
`addMethod.invoke` 之后——而 invoke 的正是我们挂着 after 回调的 `l0` 本身（LSPosed 对反射
调用同样生效）→ 回调重入 → 去重不生效 → 无限重入。6.6.0 的 `StatisticsService.onStart`
在 `UnitedBizDetailsActivity.onCreate` **主线程内**同步注册 → 卡死 → 黑屏。6.5.0 上同样的坑
只因注册路径不在主线程而未爆发。定位证据：MIUIScout 栈（`StatisticsService.onStart →
l0 → hook chain → registerDirectorService:178`）+ 模块日志无任何 director 完成记录。

**排查方法论教训（重要）**：`pm disable-user` 模块**不能**用作对照——LSPosed 读自己的
模块库注入，不看宿主侧包启用状态，hook 照常装载（模块日志在"禁用"窗口仍有 install
summary）。本轮一度据此误判为"宿主自身问题"，后被 MIUIScout 的主线程栈推翻。
真机黑屏排查的正确姿势：先看 LSPosed 模块日志有没有「本该完成却没有完成记录」的路径，
再用 MIUIScout/ANR trace 的**我们代码所在帧**对照模块源码行号。

**修复**：登记提前到 invoke 之前 + 失败回滚（含注释说明重入链路）。

## 第三阶段：进度喂入重构（修复黑屏后发现的第二个真机回归）

黑屏修复后自动跳过仍不触发。逐层排查（心跳探针）发现 6.6.0 进度链路整体搬家：
1. 文本进度控件不实例化 → J/g0 零回调（候选表里的"新名字"其实根本不会跑）；
2. seek bar 播放期间不逐帧走 `g#draw`（仅布局/seek 爆发——draw 喂入方案失败）；
3. `D0$c.run`（SeekService tick 派发器）也只在 seek 后打一炮（hook 后心跳证实）；
4. 尝试过把 s0 动态代理注册进 D0 的监听器列表——D0 上 d/e 两个 WI1.h 持有者同名同形
   （d=service.k 列表、e=s0 列表），注册错列表静默无效，**列表手术方案废弃**。

**终案**：模块自持 500ms 轮询已绑定 handle 的 core（`getCurrentPosition/getDuration`
真名 6.5.0/6.6.0 稳定），等价 6.5.0 tick 语义；handle 清理时轮询自停；draw 钩子保留为
seek/布局补充喂入 + 彩色标记绘制。附带：冷启动首次拉片段失败按 30s 冷却重拉。

**方法论教训**：
- 交接/静态核对确认的"hook 点存在"≠"会被调用"。本轮 J(I,I) 在索引里形状完美、
  安装探针 OK，但真机零回调——**装上≠跑到**，进度类 hook 必须用运行时心跳验证。
- 探针全部 `first(3)` 限频时，「日志安静」无法区分「没跑到」和「限频吃掉」；
  关键路径要留**周期性**心跳（每 N 次打一条）而不是只靠 first。
- 真机 UI 自动化：截图是 900x2000 缩放图、设备 1220x2712，坐标必须 ×1.356 换算；
  底部半屏面板的上滑手势=关闭而非滚动（滚动用列表内部慢拖）。

**终验（BV14741127BN 秒表样本，画面直读位置）**：自然播放到片段起点 2ms 精度自动跳过、
统计 +102.3s 分毫不差、彩色标记/剩余扣减/「我的」页入口（设置弹窗 0.7.0(10)）截图实证、
面板注入 size=19 且 0 渲染错误、安装期 34/39。6.6.0 适配完整闭环。
