# 2.7.1 / 65 分析卡平滑更新

## 实现与动画参数

本仓库的分析卡是追加在微信原消息下方的 `FrostedAnalysisView`，不是 WindowManager 浮窗。不存在 `ensureWindow`、临时分析气泡、更新时的 `bringToFront` 或窗口重新 `addView`。原聊天气泡包含原始消息，继续显示；120ms 临时气泡退场参数已集中定义，但当前没有对应视图，未对原消息应用淡出。

| 动作 | 参数 |
| --- | --- |
| 首次未命中展示 | alpha 0→1、scale 0.96→1、translationY 8dp→0，180ms，DecelerateInterpolator |
| 已有内容更新 | 仅文字/概率环 alpha 淡出 120ms→原位更新→淡入 150ms；磨砂底板持续显示 |
| 高度变化 | ValueAnimator 180ms、DecelerateInterpolator，从当前显示高度单调过渡到目标高度 |
| 缓存首次显示/重新进入 | 直接显示，不播放入场；重复刷新相同数据不触发任何动画 |
| 关闭 | 从当前帧淡出、scale→0.98，120ms 后移除；回收/失效绑定即时清理 |

卡片宽度和 minWidth 为 300dp；可用宽度不足时固定为宿主允许的宽度，左右 padding 各 12dp。Loading 使用三行非零占位，首行「分析中…」，与结果三段结构保持一致，每行至少 28dp，正文结果行使用相同的最小行高。重新分析时占位高度不低于已有卡片高度，避免先收缩再撑开。

内容更新不重建根视图，不调用 removeAllViews。同一消息的适配器重新绑定复用卡片；同步绑定期间用 alpha 隐去待核对的数据，确认消息身份后直接恢复，失效绑定清理旧卡。概率百分比变化而标签/文本不变时复用 EmotionRingSpan，只更新数据和 invalidate，不 setText、不重新测量；标签或分析正文变化时更新同一个 TextView 的内容。百分比也同步到无障碍描述。

动画中途收到新结果会取消旧回调，接续当前文字 alpha/当前高度。关闭期间再次展示同一消息会取消移除回调并复用根；回收或分离时取消动画并完成待更新内容，重新附着不重播入场。

## 三种构造场景

以下使用生产代码共用的 Presentation 和假卡片入场计数器构造，属于离线状态验证。参数及 Android 接入通过单测/源码接线检查，不能据此宣称已完成真机帧级视觉验收。

| 场景 | 输入序列 | 验证行为 |
| --- | --- | --- |
| 首次出现 | 空卡→未命中 loading→重复 loading | ENTER→NONE，入场次数 1；布局后开始 180ms 动画，无 Overshoot |
| loading→结果 | loading→已进入 MoodStore 的结果→重复结果；再构造重新分析 | CONTENT→NONE，结果不再次入场；重新分析仍为内容更新；高度插值增长/收缩单调，重定向首帧等于当前高度 |
| 缓存命中 | 新卡→缓存结果→连续十次刷新；另一个新卡再次读取缓存 | DIRECT→NONE；两次首次绑定入场计数均 0；概率单独变化为 RINGS |

Android 显示逻辑固定宽度，并持续保留磨砂根视图；关闭中断入场不先恢复 alpha=1，避免快速关闭造成闪亮一帧。参数、缓存分支、更新和回收接线见 `AnalysisCardMotionTest` 与 `AnalysisCardAnimationWiringTest`。

当前环境没有可运行的设备 adb/录屏工具，未安装 APK、未操作实际微信会话，未录制真机视频。视觉验收仍需在装有新版模块的 LSPosed 微信进程中观察上述三种场景，再快速滑动、重新进入、关闭/打开，核对宿主布局和刷新是否产生闪烁。这里描述的是已验证的状态及预期渲染行为，不是实际设备观察结论。

## 改动文件

- `AnalysisCardMotion.kt`：动画常量、无 Android 依赖的渲染策略及高度插值。
- `FrostedAnalysisView.kt`：入场、内容淡入淡出、高度动画、关闭和取消处理。
- `BubbleDecorator.kt`：固定尺寸、缓存判断、根复用、关闭与回收区分。
- `MessageSniffer.kt`：同消息绑定时保留根视图，未知/失败绑定清理。
- `AnalysisCardContent.kt`、`AnalysisPlaceholderSpan.kt`：固定占位结构和行高。
- `AnalysisCardText.kt`、`EmotionRingSpan.kt`：稳定概率槽位和数据原位更新。
- 两个动画测试文件；`version.properties` 由仓库 bump 脚本升至 2.7.1 / 65。

构建、单测和签名信息见同目录 `SMOOTH_CARD_2.7.1_ARTIFACT.txt`。
