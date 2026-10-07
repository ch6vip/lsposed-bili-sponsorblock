# 多进程隔离与配置同步规则

## 1. 进程划分与职责边界
Bilibili 客户端采用多进程架构，模块必须在入口 `BiliHookInit.kt` 根据当前进程名称进行严格的职责划分：

| 进程名称 | 挂载职责 | 处理原则 |
|---|---|---|
| `com.bilibili.app.in`（主进程） | UI 渲染、SponsorBlock 控制、播放器拦截、首页页签、搜索拦截、设置入口 | 挂载全量 UI 与播放相关 Hook |
| `com.bilibili.app.in:download`（下载进程） | 离线缓存、取下载地址、gRPC 传输 | 仅挂载 AccessKey 捕获、UPOS 替换、播放地址补齐与下载权限 Hook |
| `com.bilibili.app.in:ijkservice` / `:live_ijkservice` | 纯底层解码与音频渲染服务 | 立即跳过（Skip），严禁在此进程挂载业务 Hook |
| 其他未知子进程 | - | 默认全部跳过，防止意外引发崩溃 |

## 2. 模块配置同步与数据权威性
1. **单一数据源（Single Source of Truth）**：
   - 模块的真实权威配置保存在自身私有目录的 SharedPreferences 中。
   - 宿主目录下的 `sponsorblock_settings.json` 只是为了方便宿主在跨进程或无 IPC 环境下读取的只读镜像。
2. **禁止直接覆写镜像**：
   - 业务逻辑或测试修改配置时，必须通过 `SettingsWriter` 或 `SettingsProvider` 提供的 IPC 接口进行更新。
   - 严禁通过反编译或直接修改文件的方式篡改镜像，否则会被权威配置覆写重置。
3. **设置生效周期**：
   - SponsorBlock 核心跳过与标记配置：重进播放页或重新加载视频生效。
   - B 站增强（IP 属地、互动提示隐藏等）：模块内部维护轮询检测，修改后 10 秒内自动热生效。
   - 番剧解锁开关：由于涉及多个服务级 Hook 与请求头改写，建议重启客户端以保证所有 Hook 生效。
