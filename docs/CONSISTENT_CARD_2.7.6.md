# 卡片背景一致性修复 — 2.7.6 / 70

`FrostedAnalysisView.draw()` 不再先铺不透明 `drawColor`。每帧先从 `ModulePrefs.analysisSettings()` 读取一次共享的 `RuntimeSettings`，同步 `cardOpacity` 和 `cardPalette`；共享设置为空时才保留视图缓存。`updateOpacity` 继续兼容已有调用，配色变化同步文字及标签颜色。

绘制顺序为：圆角裁剪 → 有效快照且透明度小于 100 时画 center-crop 模糊图 → 一次覆盖整个 bounds 的 top/bottom 线性渐变 → 按配色描边 → 原有内容绘制。bounds 为 `(0, 0, width, max(height, 1))`；只有描边阶段才缩进 bounds。无有效快照或透明度为 100 时只使用不透明配色渐变，没有局部不透明矩形。

center-crop 的纯逻辑计算位于 `AnalysisCardBackdrop.centerCrop`：

```text
scale = max(targetWidth / sourceWidth, targetHeight / sourceHeight)
dx = (targetWidth - sourceWidth * scale) / 2
dy = (targetHeight - sourceHeight * scale) / 2
```

绘制使用 `Matrix.setScale(scale, scale)` 和 `postTranslate(dx, dy)`；源图两轴缩放一致，越出目标的部分由圆角路径裁剪。200×100 源图到 100×100 卡片时 `scale=1, dx=-50, dy=0`，保留源图中央 100×100，没有拉伸或留边。卡片高度变化仅重新计算绘制矩阵，使用原有模糊图。

设置保存沿用原有广播。`SettingsSession` 检测透明度或配色变化后，主线程回调读取同一个共享设置快照，并在 `BubbleDecorator.cards` 遍历每张卡片，更新兼容缓存后无条件 `invalidate()`。即使某张卡已在回调执行前读取到新设置，仍会收到统一重绘。动画参数和快照请求、抓取、缓存时机保持原有实现。

验证覆盖：禁止 `drawColor` 底色、圆角裁剪及完整渐变顺序、等比例裁剪与高度变化、两张旧缓存不同的卡片读取相同设置、透明度/配色广播和延迟回调重绘、设置不可用时回落，以及原有快照与动画回归测试。构建使用离线单测及 Debug、Release 任务，参数保留 `--no-daemon -Pkotlin.compiler.execution.strategy=in-process`。

本次未安装 APK 或执行微信实机视觉测试。

## 验证和交付

- 离线单测：75 个测试类、460 项测试，失败、错误、跳过均为 0。新增 center-crop 与一致性测试各 3 项；绘制顺序、透明度、快照及动画测试均通过。
- Debug 和 Release：`BUILD SUCCESSFUL in 1m 38s`。测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`；构建日志：`output/consistent-2.7.6-build.log`。
- 包名/版本：`dev.jev.wechatmood`，`2.7.6 / 70`；已用 APK badging 核对。
- 交付 APK：`/storage/emulated/0/Download/yanwai-2.7.6-consistent-release.apk`。
- 签名源：`output/releases/2.7.6/yanwai-2.7.6-consistent-release.apk`。
- 大小：16,015,682 字节（15.27 MiB）。
- APK SHA256：`1dfb8e6c674bf0ba3d9d7dbda0290a104758dd1e4115c7ffad32f1420aa5e2d5`；源文件与 Download 副本一致。
- 签名：使用 `/workspace/keys/yanwai-release.jks`，口令从同目录文件读取，仅传入签名子进程环境，不打印、不入库。
- 签名前后 zipalign 校验通过；`apksigner verify` 通过，另以 `--min-sdk-version 24` 验证 v2/v3 均通过，Manifest minSdk 保持 28。
- 证书 SHA256：`b8292dd14f057fbc434f2a0de57b3ffd9f4fe26ce479420396ec5bbf2a92dab5`，与既有 release 一致。
- 签名、badging、产物摘要保存在 `output/releases/2.7.6/`。

推送目标：fork `huaqingoba/yanwai` 的 `feat/emotion-cache`，代理 `http://127.0.0.1:7080`。原有未跟踪文档 `docs/OPAQUE_CARD_2.7.2.md` 保留，不纳入本次提交。
