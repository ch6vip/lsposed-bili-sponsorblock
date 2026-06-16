# 复刻要点

## 需要复刻的行为

- 自动跳过 sponsor 片段
- 在进度条上画片段标记
- 显示跳过提示
- 支持投票和提交片段
- 支持设置服务端地址、分类开关、自动跳过开关

## 关键 hook 点

- 播放器容器创建
- 进度条 draw
- 剩余时长文本更新
- 播放 / 暂停 / seek
- 小窗进入

## video id 获取链路（已确认，对齐 APK）

aid/cid 不反射 `PlayerParamsV2`，而是 hook `VideoDirectorObserver.onStart`：

```
Ch1.g#onCreate → container.getPlayDirectorServiceV3()/getVideoPlayDirectorService()
  → director.addVideoDirectorObserver(代理 VideoDirectorObserver)
  → onStart(current, previous)
  → current.getLogDescription() = "....aid: 12345, cid: 67890"
  → 正则 ^.*aid:\s(\d+),\scid:\s(\d+)$ → aid, cid
  → AidBvidConverter.aidToBvid(aid) → bvid → /api/skipSegments
```

证据：
- `PlayerHookProvider.g()` 判定 `onStart`：`returnType==void && paramCount==2 && param[0]==param[1]`
- 解析 `yl.c(getLogDescription).a()` 得 `ej` list，`get(1)=aid`、`get(2)=cid`
- 正则来自 `vg.java:173`，与项目 `PlayerBridge` 旧 `logDescriptionPattern` 一致
- `zo.toString()` = `SegmentsInfo(aid=,cid=,epId=,duration=,segments=)`，证明 `c(aid,cid,epId,duration)` 参数顺序

APK 存的是 aid 不是 bvid，bvid 由 `i6.H(aid)` 实时转换。

## 先做最小版

先只做：
1. 取视频 ID
2. 请求片段
3. 自动 seek

再补 UI 和统计。

