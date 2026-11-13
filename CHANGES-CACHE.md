# 内容缓存与补丁恢复（2.7.0 / 64）

持久缓存 key 为 UTF-8 字节的 SHA-256：

```text
analysis-v4|conversationKey|normalize(targetText)|settings.analysisFingerprint()
```

`conversationKey = AnalysisCacheKey.digest(accountScope, talker)`，以账号与会话隔离缓存。文本去首尾空白，将连续 ASCII / Unicode 空白压缩为一个空格，保留表情、变体选择符和 ZWJ。key 不包含 messageId、createdAt 或页面前文；同一会话的相同规范文本复用原结果与原证据。设置指纹包含模型、思考档位与背景设置修订，展示开关不影响缓存。

先读取 v4，再读取情绪分布 v3、稳定消息 v2、带前文的 v2；旧命中会尽力提升为 v4。兼容默认 MINIMAL 升级前未含档位的设置指纹；切换到其他档位不会读取这类旧结果。提升保留原证据和创建时间，失败的提升不会触发网络请求。

进入会话时从列表或适配器尾部读取最后一条对方消息，后台只查询缓存并预热内容内存层，包括自动分析关闭时。打开或重建卡片时同步检查内存；持久缓存查询结束前不创建 loading 卡，命中即渲染完整结果并刷新 UI，默认不请求网络。只有缓存未命中才显示 loading 并调用模型。已完成消息的长按菜单显示“重新分析”，失败卡可直接点击重试；这两条入口撤销旧任务、清除目标内存记录并强制跳过持久缓存。

失败、解析不完整和部分成功结果均可缓存，卡片显示“分析返回不完整，点击重试”。默认 TTL 为五分钟。SQLite 自动给旧表新增 `created_at`，以已有 `accessed` 初始化；读取只更新 `accessed` 用于容量淘汰，TTL 始终基于 `created_at`，提升、预读和内存恢复均不会续期。成功结果继续使用既有的 5000 条 / 32 MiB 限额与访问时间淘汰。取消的任务不写入失败缓存。

语音等待转写的占位文字不能生成内容 key，以免不同语音串用结果；完成转写后按实际文字查询和保存缓存。转写前的失败仍保留在卡片内存层，并可重试。

## 恢复记录

- 实际 `/workspace/yanwai` 已存在，是 origin 指向 `YIRC99/yanwai` 的干净 checkout，HEAD 为 `ddb420e`（2.6.9）。遵守保留目录要求，在此 checkout 继续工作。
- `existing.diff`：执行 `git apply --3way --whitespace=nowarn`，15 个文件均干净应用。
- `yanwai-thinking.diff`：执行同一命令；浅克隆缺少补丁基础 blob，Git 回退到直接应用，21 个文件全部成功，无冲突。
- 情绪补丁未收录三个未跟踪文件；从旁边的 `existing-files.tar` 恢复 `EmotionDistribution.kt`、`EmotionDistributionTest.kt` 和 `CHANGES.md`，保留原归一化逻辑和相关测试。
- 无逐文件冲突合并，无放弃项。旧测试中“消息 ID / 时间 / 背景自身改变必然造成缓存 miss”和“失败不缓存”的断言按本次内容缓存要求更新；账号、会话与模型隔离仍保留。

## 验证

`ContentAnalysisCacheTest` 使用真实 SQLite 文件验证内容 key、旧 key 提升、原始证据与创建时间、失败 TTL、表迁移、内存预读和容量淘汰。`AnalysisCacheWiringTest` 保护 Android / Xposed 卡片与重试入口接线；原 JVM 测试全部纳入 `:app:testDebugUnitTest`。Android / 微信真机交互留给设备测试。

构建与完整单测已通过：415 项（原有 399 项 + 新增内容缓存 14 项 + 新增 UI 缓存接线 2 项），失败 / 错误 / 跳过均为 0。Debug APK 由 JDK 17、Gradle 8.13 与指定 AAPT2 构建。
