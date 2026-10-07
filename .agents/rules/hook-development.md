# Hook 开发与混淆适配规则

## 1. 目标与范围
本规则适用于 `app/src/main/kotlin/com/ctf/bilisb/host/`、`unlock/`、`hook/` 以及所有直接与宿主字节码交互的模块。

## 2. 混淆目标定义规范
1. **收拢管理**：类名、方法名候选列表必须统一维护在 `HostTargets.kt` 或对应 Hook 单例的伴生对象中。严禁在拦截逻辑内部散落硬编码字符串。
2. **多版本兼容**：所有候选集必须同时考量 Bilibili 国际版 6.5.0（9110200）与 6.6.0（9130300）。
3. **版本探针记录**：在 Hook 安装成功或失败时，必须通过 `ModuleLog.probe(...)` 打印目标类与方法绑定状态，便于在日志中快速统计命中率（`hook summary: N/M hit`）。

## 3. 宿主数据模型安全构建
1. **Unsafe 分配**：宿主数据类（如 6.6.0 `fE1.k`）的构造器常被 R8 混淆或混入复杂协程 context 参数。在动态创建数据项时，优先通过 `sun.misc.Unsafe.allocateInstance(targetClass)` 进行内存分配。
2. **双重字段覆盖**：
   - 混淆字段覆盖：例如 6.6.0 的 `a`(tabId), `b`(name), `c`(uri), `f`(default_selected), `g`(pos), `h`(reportId), `p`(expired_at)。
   - 语义字段覆盖：同时写入 `tabId`, `name`, `uri`, `pos` 等，确保单测与未混淆/旧版本场景均能兼容。
3. **全层级反射**：反射读写字段时，必须向上回溯 `Class.superclass`，避免目标字段声明在基类中导致查找失败。

## 4. Protobuf 与 Wire 级无损切片
1. **避免全量序列化**：严禁尝试将宿主的大型 Protobuf 对象转为 JSON 或使用残缺 Schema 进行重新序列化，这会导致未知的 Protobuf 扩展 Tag 丢失，造成宿主严重解析异常。
2. **定点切片与追加**：使用 `WireSplice.kt` 和 `WireWriter.kt` 直接在原始字节流（byte array）上操作字段注入与拼接，保留原生数据包中的所有未知 Tag 和签名。

## 5. 异常安全红线
1. 任何 Hook 的 `before` / `after` 回调必须包裹在 `runCatching` 块内。
2. 异常发生时记录简短错误日志，绝不允许向宿主调用栈抛出任何未捕获的 Throwable。
3. 解锁/注入失败时必须放行宿主原生行为或原始响应，杜绝造成黑屏。
