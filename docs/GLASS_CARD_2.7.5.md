# 浅色卡片与即时设置修复：2.7.5 / 69

包名：`dev.jev.wechatmood`。Release APK 元数据确认 versionName=2.7.5、versionCode=69，未启用 debuggable。

卡片不透明度范围为 **60–100%**，默认 **88%**；非法或缺失值使用默认值，已有合法设置保留。滑块的 XML 初始值与运行时代码同步。100% 不绘制模糊底衬，有快照时填充不透明配色渐变。

| card_palette | 上端 RGB | 下端 RGB | 解析规则 |
| --- | --- | --- | --- |
| dark | 0x2B2D34 | 0x202229 | 默认值；非法值回落到此项 |
| light | 0xEEF1F5 | 0xE3E7ED | 固定浅色，不使用纯白 |
| auto | 由宿主 uiMode 决定 | 由宿主 uiMode 决定 | 夜间使用 dark，其余使用 light |

设置页「卡片配色」使用不可编辑下拉。设置保存成功后，`SettingsProvider.publish` 将同一配置快照交给 `ModulePrefs.applyLocalSettings`，通过 `SettingsBridge.receive` / `SettingsSession.accept` 触发已有 `onCardOpacityChanged` 回调，并向宿主发布现有设置广播。回调同时比较不透明度和配色，在主线程调用 `BubbleDecorator.updateOpacity` 遍历卡片；每张卡通过 `FrostedAnalysisView.updateOpacity` 更新状态并 `invalidate`。不等待下一次 render，不触发分析任务，也不改变缓存标识。文字、现有标签 span 和描边跟随卡片配色；宿主 uiMode 变化也刷新颜色。

快照失败固定使用当前配色的 **92%** 渐变（alpha=0xEB），保留圆角描边；内容区不增加提示，仅记录 `CARD_SNAPSHOT_FAILED` 日志。回退不受滑块值影响，包括 100%。成功快照仍使用滑块指定的不透明度；60–75% 不增加压暗遮罩。

模糊仍为两轮横向/纵向 box-blur，radius=3、七个采样点；缩采样和最大尺寸不变。现有首次显示快照、同帧共享、冻结采样坐标、滚动/临时 detach 保留与显式重新分析规则不变；设置变更不重新抓取快照。动画参数不变。

## 验证

- 全量离线单测：**454 passed，0 failures / errors / skipped**。
- 范围、默认值与设置更新：CardOpacityTest 3 项；palette 解析、uiMode 与 SettingsBridge → Session → 已挂载表面状态更新：CardPaletteTest 3 项。
- 100% 跳过底衬、拖动更新与源码接线：AnalysisCardOpacityTest 3 项；配色、92% 回退与描边：AnalysisCardSurfaceTest 7 项。
- 模糊细线衰减/大色块保留：AnalysisCardBackdropTest 3 项；快照生命周期：AnalysisCardSnapshotTest 7 项；动画与接线：11 项。
- 设置布局：SettingsLayoutTest 9 项，覆盖滑块范围/初始值及配色下拉即时保存。
- Debug、Release 构建：BUILD SUCCESSFUL（1m 25s）。保留既有 AGP/SDK 与弃用 API 警告。
- 未执行安装或真实微信 UI 验证。

构建命令：

```sh
JAVA_HOME=/workspace/jdk-17.0.13+11 ANDROID_HOME=/workspace/android-sdk ANDROID_SDK_ROOT=/workspace/android-sdk GRADLE_USER_HOME=/workspace/.gradle-yanwai /workspace/gradle-8.13/bin/gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --offline --no-daemon --console=plain -Pkotlin.compiler.execution.strategy=in-process -Pandroid.aapt2FromMavenOverride=/workspace/tools/aapt2
```

测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`。

## APK

- 签名源：`/workspace/yanwai/output/releases/2.7.5/yanwai-2.7.5-glass-release.apk`。
- 交付：`/storage/emulated/0/Download/yanwai-2.7.5-glass-release.apk`。
- 大小：**16,097,797 bytes（15.35 MiB）**。
- SHA256：`a8fcfa04fa4e7667a9ecb9f490360e06b5290e66862b2a92fdb1cb06fd637923`。
- 源 APK 与交付 APK 哈希一致。
- 使用 `/workspace/keys/yanwai-release.jks`，alias=yanwai；口令仅从同目录文件读取并经临时子进程环境传入，没有打印或提交。
- 签名前 `zipalign -p -f 4`、签名后 `zipalign -c -p 4` 通过。
- `apksigner verify --verbose --print-certs` 通过 v3；使用 `--min-sdk-version 24` 显式验证 v2/v3 均通过（不改变 Manifest minSdk=28）。
- 证书 SHA256：`b8292dd14f057fbc434f2a0de57b3ffd9f4fe26ce479420396ec5bbf2a92dab5`。
- 证书 DN：`CN=Yanwai, OU=local, O=lcsauce, L=NA, ST=NA, C=CN`；RSA 2048，与既有 release 一致。
- 签名验证明细与 badging 保存在 `output/releases/2.7.5/`。

推送目标为 fork `huaqingoba/yanwai` 的 `feat/emotion-cache`，代理 `http://127.0.0.1:7080`。原有未跟踪文件 `docs/OPAQUE_CARD_2.7.2.md` 保留，不纳入本次提交。
