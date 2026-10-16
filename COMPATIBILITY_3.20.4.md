# 国际版 3.20.4 兼容与验证

目标：`com.bilibili.app.in` 3.20.4，versionCode `8230800`。真机：Samsung SM-S918B / arm64-v8a，支持 libxposed API 101 的框架。

本次保留已有 6.5.0 / 6.6.0 候选，但未重新进行这两个版本的真机回归。

## 播放器适配

| 链路 | 3.20.4 协议 |
| --- | --- |
| widget 绑定 | `q(tv.danmaku.biliplayerv2.h)` |
| Android Context / core / Director | `h.z()` / `e.q(): service.f0` / `e.f(): service.c0` |
| 观察者注册 / 注销 | `PlayDirectorServiceV3.J0(u2)` / `S1(u2)` |
| 视频标识 | `P(): Video$f` → `D(): Video$b` → `c()` aid / `d()` cid |
| 进度 | `U(int,int)`、已有自持轮询及 SeekBar draw |
| 标记绘制 | `seek.v3.c` Drawable / `seek.v3.b` SeekBar |
| 「我的」入口 | `mine.c`，同时校验 RecyclerView adapter 结构 |
| 更多菜单 | `gemini.base.ui.b#Z0(List)` / item `e` / holder `e$c`，按布局成组解析 |

短方法名反射校验声明返回类型及继承关系，避免调用同名无关方法。`Video$f.T()` 是交互元数据，不用于读取 aid/cid。

## 设置可见性

Android 11+ 会过滤宿主对模块 Provider 的解析。Provider 启动时向目标宿主授予定向 URI 读取权限，原调用包和 UID 校验保留。首次安装打开一次模块桌面设置；MY_PACKAGE_REPLACED receiver 在覆盖更新后恢复权限。

## 其他版本入口

- 关注引导：`FollowPopupUtil.j(Activity, Scene)` 实例方法。
- REST / gRPC 身份：`cg1.a`、`com.bilibili.okretro.interceptor.a`、`if1.a.n()/k()`、`of1.a$a.e()`。按 URL 或每次调用的服务名限定改写范围，开关关闭时放行。
- HTTP DNS：`NativeHttpDns.resolve/assign/fallback` 与旧版 Record；失败保留原结果。
- 番剧详情：大写 `OGVIntroDetailInfoComponent`、`.bean.OGVIntroInfoVo`、旧 Publish 参数顺序、`response.c$c`。
- 旧 REST 选集：`getSectionFloatLayerSeason`，缺少正片分区时才补充；使用宿主生成的 JSON 描述符按字段名构造，保留原季元数据。该版本不存在新版 SeasonMoss。
- K 通用发送：六参 `kntr.base.moss.api.c.d`，通过宿主 ProtoBuf/serializer 桥接普通 protobuf 处理管线，失败保留原对象。3.20.4 不存在 KPlayerMoss，不能由通用入口命中推断下载使用了该路径。

## 已取得的证据

- 真机读取 aid/cid，包括旧视频 `170001/279786` 和 `98935548/168885122`。
- `BV14741127BN` 返回 HTTP 200，解析八个可跳过片段；自动跳过片头 `0–30.015s`、自我推广 `119.447–180.017s`、赞助 `300.019–600.014s` 等区间，跳转后正常继续播放。
- 截图确认彩色标记显示在实际轨道内，时间显示包含扣减后的时长。
- 「我的 → Bili2233」成功打开设置；离开播放页约三秒后观察者注销和状态清理成功。
- 更多菜单出现 `morePanelInjected size=21` 日志；最终点击面板尚未验收。
- 覆盖更新后宿主恢复 `ModuleSettings: read from IPC`。
- 其他新增入口均已在真机安装命中；临时 instrumentation 使用真实宿主 DEX 验证 Episode/Section/Season 模型构造、字段映射与季元数据保留。

上述真机记录来自本地适配构建，包含额外的独立 UI 实验。本 PR 去除了语言选择和本机属地显示功能；本 PR 的独立分支另行进行单测和构建，不把本地合并版的 Hook 总数当成此分支端到端证据。

## 验证限制

新增关注气泡抑制、服务端 IP 属地返回、HTTP DNS 中继、番剧详情注入、真实漫游与离线下载仍未完成端到端验收。手动跳过、倒计时、静音、全屏/小窗和快速切集也未完整回归。没有有效解析服务器时无法验收真实漫游/下载。

可选 GeminiProgressTextWidget、旧容器和其他版本特征类在 3.20.4 不存在，记录为 SKIP；核心链路安装结果与功能行为需要分别检查。

没有提交 APK、客户端 DEX、设备截图、原始日志、签名密钥或账号数据。协议、反射回退、设置调用方校验等回归测试位于 `app/src/test`，构建命令为：

```bash
./gradlew :app:testReleaseUnitTest :app:assembleDebug :app:assembleRelease
```
