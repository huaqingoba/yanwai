# 本次修改说明

恢复情绪概率环改动，并叠加思考强度与内容缓存优化。版本更新为 2.7.0（64）；缓存与恢复过程见 `CHANGES-CACHE.md`，思考参数见 `CHANGES-THINKING.md`。

## 浮层内容

- `hook/AnalysisCardContent.kt`：把聊天分析浮层投影为固定结构：情绪概率环、`智能分析`、`意图解析`、`可能在意`、`情绪倾向`。缓存详情中的 `yanwai + 版本号`、`仅供参考` 和旧式 `情绪：……（LLM 定性，非概率）` 不再进入浮层，因此不会留下空行或额外底部占位。
- `hook/BubbleDecorator.kt`：加载、未配置、超时或失败时也构造可显示的兜底结果，始终保留“不明确 100%”概率环；同时移除旧卡片最小高度，避免删行后仍保留空白。
- `hook/AnalysisCardText.kt`、`hook/EmotionRingSpan.kt`：所有线路统一用原生 `TextView` 的 `ReplacementSpan` 和 `Canvas.drawArc` 绘制整数百分比圆环，标签可在可用宽度内换行，未引入依赖。

## 情绪数据与解析

- `core/EmotionDistribution.kt`：新增唯一的情绪归一化边界，统一输出十个规范标签的 `List<EmotionScore>`，整数百分比严格合计 100。支持已有概率、0～1 小数、0～100 数字、百分比字符串、单一定性标签、英文/中文标签、Markdown 代码块、前后杂文和 Chat Completions 包装；无法解析时输出“不明确 100%”。单标签旧结果使用“主标签 70%，其余标签均分 30%”的可解释先验。
- `core/MoodStore.kt`、`core/EmotionIndicator.kt`：`Mood` 在构造及 `copy` 时集中生成规范分布，UI 只消费该分布；颜色固定为平静绿、生气红、失落/委屈紫灰、缓和青、疲惫黄、不明确灰等。
- `analysis/LlmEmotionProtocol.kt`：提示词要求返回与解析器一致的 `probabilities` JSON；解析缺字段或脏响应时保留兜底分布并标记可重试，不再生成旧式“LLM 定性，非概率”行。
- `analysis/JevProtocol.kt`：JEV 的完整概率保持原精度用于既有决策，显示前统一归一化；不完整、定性或兼容响应也可退化成完整分布。
- `analysis/SignalAnalyzer.kt`：解析异常提示与新兜底行为保持一致，不再错误宣称“本次不显示判断”。
- `core/SqliteAnalysisCache.kt`：分析缓存格式升级为情绪分布版，同时保留 v2 键的读取与提升，避免已有结果因键计算错误而无谓重跑。

## 测试

- `core/EmotionDistributionTest.kt`：覆盖单标签、完整分布、脏 JSON、空/非法输入，并断言每次输出总和为 100；另覆盖最大余数取整和异常数值。
- 更新 JEV、LLM、卡片投影、颜色/圆环及传输测试，验证失败态仍有概率环，浮层不显示版本行、`仅供参考` 或旧式定性行。

## 接入 decision-model-preview

若后续接入 `decision-model-preview` 的 `choice/probabilities`，在协议解析处把原始响应交给 `EmotionDistribution.normalize(text = responseBody)` 即可：

- `choice` 可使用英文键（如 `calm`、`annoyed`、`unknown`）或中文标签；
- `probabilities` 可直接是对象，也可使用 `{label, probability|percent}` 数组；
- 值可为 0～1 小数、0～100 数字或百分比字符串；
- 不要在 UI 中单独解析或补概率，只把归一化后的分布放入 `Mood.emotions`，现有 `Mood.distribution` 和圆环渲染会自动接管。
