# 2.7.4 (68) 卡片不透明度

「更多 → 分析卡片显示」新增「卡片不透明度」SeekBar，85%–100%，每步 1%，默认 96%。说明为「越高越不透，100% 为纯色」。数值保存在 `wechatmood_config` SharedPreferences 的 `card_opacity` 整数键；缺失、类型非法或越界均回落 96。

Alpha 采用四舍五入：`(percent * 255 + 50) / 100`。

| 不透明度 | Alpha |
| --- | --- |
| 85% | `0xD9` |
| 96%（默认） | `0xF5` |
| 100% | `0xFF` |

默认深色渐变为 `0xF52B2D34 → 0xF5202229`，浅色为 `0xF5FFFFFF → 0xF5F3F4F6`。浅色文本、标题与描边使用对应的深色前景。

滑动每次变化即保存并通过现有设置广播传给微信。设置会话接受新值后，在主线程对已有卡片调用 `updateOpacity()`，只更新表面状态并 `invalidate()`；不绑定内容、不请求布局、不触发动画或新快照，也不改变分析缓存键及进行中的请求。

快照仍在首次显示或显式重新分析时抓取，滚动和内容变化不重拍。100% 跳过静态位图及底衬填充，仍绘制渐变、圆角与描边，并保留已抓取的快照供调低时复用。快照失败时保持全不透明兜底。

验证范围：`CardOpacityTest`、`AnalysisCardOpacityTest`、`AnalysisCardSurfaceTest`、`AnalysisCardSnapshotTest` 和 `SettingsLayoutTest`，以及现有动画回归测试。Android-free 单测验证映射、非法值、绘制决策、快照生命周期和缓存隔离；宿主 View/SeekBar 的调用链由源码及 XML 契约检查覆盖。未执行设备 UI 测试。

构建结果：`:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` 通过（`--no-daemon -Pkotlin.compiler.execution.strategy=in-process`）；72 个测试类、450 项测试，0 失败、0 错误、0 跳过。版本 guard 6 项检查通过。

交付 APK：`/storage/emulated/0/Download/yanwai-2.7.4-opacity-release.apk`，15,999,298 字节（15.26 MiB）。使用指定 release keystore 签署；zipalign 的 16 KiB 页对齐检查通过，v3 正常验签通过，另以 `--min-sdk-version 24` 验证 APK 内 v2/v3 签名均通过。

- APK SHA-256：`c9547ee607be010b1414a6a9baeb9037669f56d8d24ea685b84155f2fb96ef58`
- 证书 SHA-256：`b8292dd14f057fbc434f2a0de57b3ffd9f4fe26ce479420396ec5bbf2a92dab5`（与 2.7.3 相同）
