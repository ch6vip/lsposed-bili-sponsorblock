# Agent Note: 解锁开关、搜索来源与下载请求边界

Status: implemented

## Problem

0624939 的搜索通过劫持原生影视 type=8 实现区域页；关闭总开关、关闭搜索或未配服务器时仍回空，
因此“默认关闭”也改变宿主行为。分页固定第一页，网络错误伪装成没有结果。
缓存补参作用到普通播放，失败时传 null，代理却使用补参前的能力位；详情页下载权限尚未处理。
旧可行性/评估/交接互相引用过期结论，无法判断哪些实现真正通过验收。

## Decision

搜索以模块专用 type=810 区分来源。页面 URI 带 bilisb_unlock=1，OgvSearchResultFragment.onCreate
只为这个页面改写 arguments.type；原生 7/8 请求始终放行。已打开的区域页关闭功能后改走原生番剧搜索。
依据是 6.6.0 classes18：ogv.c 路由桥强制写 7/8，Fragment.Dm/Am 读取 arguments.type 到 c0，
loadData 协程把 c0 放入 u.d，再经 ogv.d 进入请求。没有用进程全局“最后点击页签”猜来源。

分页沿 Pagination.next/pageSize 读取，响应同时写 page(10) 和 PaginationReply(7).next/prev(1/2)。
错误经 MossResponseHandler.onError(MossException) 及节流提示反馈，不再回喂伪造空结果。
区域设置复用现有 Codec 字段，在设置页提供四区选择；构建页面不写默认值。

CacheRequestPatch 只处理 download>0；fnval 合并能力位而非覆盖宿主新位。序列化或宿主 parseFrom
失败都保留原对象。代理使用有效请求的参数，并保留原始下载语义用于分类。
DownloadRightsPatch 只处理 ViewPgcAny 载荷内的季权限和选集卡权限；字段号来自 6.6.0 DEX，
不同 Rights 消息不共用字段布局。保留播放权、会员状态、cache_auth 及未知字段。
详情页即使已有面板或季数据获取失败，也保留已成功应用的下载权限修改。

播放/选集等待超时后取消 Future；HTTP 连接在 finally 释放，播放 HTTP 超时缩至 2.5 秒。
这改善取消和队列行为，不宣称 HttpURLConnection 在所有 Android 版本都能立即响应线程中断。

## Alternatives considered

- 保留 type=8 劫持：改动最少且已有实播记录，但无法保留原生影视语义，不能解决开关回归的根因。
- 全局记录当前区域页：无需适配页面参数，但多页面预加载、异步请求和快速切页会错配，故不采用。
- 自建整套搜索页面：可完全控制路由和分页，但需重做宿主卡片、生命周期和导航；当前专用参数即可隔离。
- 覆盖整个详情响应：能同时改多种权限，但更易丢失宿主实验字段；采用限定路径的 wire 拼接。

## Consequences

原生请求与模块请求分离，失败保留宿主输入；测试覆盖配置组合、游标、异常和真实 fixture。
代价是增加一个 6.6.0 页面生命周期 Hook，宿主更新后需复查 arguments→c0→请求的链路。
默认仍单区域服务器；未实现播放器限制解除、PCDN 全局处理或多区自动选路。
缓存权利位补齐不意味着服务端授权或完整离线下载已验证。

本地 docs 先备份再合并，移除三份被取代文档；历史记录与当前状态分开。
README 许可证文字按已有根 LICENSE 修正为 GPL-3.0，许可证文件未改动。

## Verification

2026-10-05：`:app:testDebugUnitTest :app:assembleDebug` 通过，283 测试、0 失败、0 跳过。
新增测试覆盖原生 type=7/8 所有开关组合、区域关闭降级、分页首中末页、非法游标、
区域 UI→prefs→Codec、补参失败保留对象、未知字段保留、下载权利位 fixture、超时任务取消。
新路由、下载入口及离线文件尚未在真机验收；此前旧路由的“全通”不能作为本次证据。
