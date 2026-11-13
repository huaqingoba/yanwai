# 思考强度设置与兼容降级

默认档位为 **MINIMAL / 最低**。新增 `ThinkingEffort { OFF, MINIMAL, LOW, MEDIUM, HIGH }`，存入原 `wechatmood_config` SharedPreferences 的 `reply_thinking_effort`，值为枚举名。旧用户没有该值或值无效时读取 MINIMAL，原三参数 `ReplySettings.fromInput()` 与 `empty()` 仍可使用。

意图分析页新增「思考强度」下拉项：关闭 / 最低（默认）/ 低 / 中 / 高。说明为「越低越快越省钱，情绪概率分布通常不需要深度思考」。该档位统一用于独立 LLM 分析、复用回复模型的分析、回复建议和话题推荐。保存分析设置或保存并检测分析模型会落盘、同步到运行中的微信进程，并在下一次请求生效。切换或保存模型配置会保留当前档位；分析与回复的地址、Key、模型配置仍分别保存。

## 档位 → 请求参数

| 后端识别 | 关闭 OFF | 最低 MINIMAL（默认） | 低 LOW | 中 MEDIUM | 高 HIGH |
| --- | --- | --- | --- | --- | --- |
| OpenAI 官方或兼容网关，模型为 gpt-5 / gpt-5.x / gpt-6 系列、o 数字系列（支持 `openai/` 等模型前缀） | `reasoning_effort="none"` | `reasoning_effort="minimal"` | `reasoning_effort="low"` | `reasoning_effort="medium"` | `reasoning_effort="high"` |
| DashScope / 百炼：`dashscope.aliyuncs.com`、国际站 `dashscope-intl.aliyuncs.com`、`*.maas.aliyuncs.com` | `enable_thinking=false, thinking_budget=1` | 同 OFF | `enable_thinking=true, thinking_budget=1024` | `enable_thinking=true, thinking_budget=4096` | `enable_thinking=true, thinking_budget=16384` |
| Anthropic：`api.anthropic.com` 或完整路径以 `/v1/messages` 结尾 | `thinking={"type":"disabled"}` | 同 OFF | `thinking={"type":"enabled","budget_tokens":1024}` | `thinking={"type":"enabled","budget_tokens":4096}` | `thinking={"type":"enabled","budget_tokens":16384}` |
| Gemini：`generativelanguage.googleapis.com` | `generationConfig.thinkingConfig.thinkingBudget=0` | 同 OFF | 同字段 `1024` | 同字段 `4096` | 同字段 `16384` |
| 火山方舟 `ark.*.volces.com`；DeepSeek、Moonshot / Kimi、BigModel / GLM、Z.ai、Xiaomi MiMo 的域名 | `thinking={"type":"disabled"}` | 同 OFF | 不下发 `thinking`，交服务默认 | 同 LOW | 同 LOW |
| 其它自定义网关 / 中转（包括非 OpenAI 模型的 OpenRouter 请求） | `reasoning_effort="minimal"` + `thinking={"type":"disabled"}` | 同 OFF | `reasoning_effort="low"` | `reasoning_effort="medium"` | `reasoning_effort="high"` |
| 已缓存拒绝思考参数的自定义端点 | 不下发思考参数 | 不下发思考参数 | 不下发思考参数 | 不下发思考参数 | 不下发思考参数 |

已知服务的 host / 路径优先于模型名称，不再限制国产服务的模型白名单，火山 `ep-` 接入点与后续新模型也获得控制。OpenAI 官方非推理系列（例如 gpt-4o）没有该思考控制项，不附加推理字段。MINIMAL 始终映射 `minimal`，不会复用 OFF 的 `none`。

这张表描述下发参数，不能保证服务端对每个模型接受并执行。原生 Anthropic / Gemini 所需的完整消息格式、认证与响应格式转换不属于此次修改，连接仍应使用应用现有协议可调用的服务。保留完整 `/v1/messages` 地址，避免原地址规范化额外拼接 `/chat/completions`；Gemini 的配置合并保留其它已有 `generationConfig` 字段。

## 一次降级重试

实际共用传输类为 `reply/ReplyHttpClient.kt`，原实现只有网络失败提示，没有应用层自动重试。

1. 四种 LLM payload 都调用 `AnalysisThinking.apply()`；传输层再次在深拷贝上应用，兼容请求创建后才发生的端点能力缓存更新，不修改调用方原 payload。
2. 仅对未知自定义网关或用 OpenAI 模型的兼容网关，在 HTTP **400 / 422** 且错误正文包含 `reasoning`、`thinking` 或 `unknown field`（允许空格、下划线、连字符，忽略大小写），且本次实际带思考字段时触发降级。
3. 缓存该完整规范化 URL（包括端口与路径）的“不支持思考参数”判定，再深拷贝同一 payload、移除 `reasoning_effort` 与 `thinking`，重试 **一次**。模型、消息、地址和认证保持一致。
4. 第二次失败直接使用现有错误提示，不递归重试。401 / 403 / 429 / 5xx、其它状态码、不涉及这些字段的 400 / 422，以及已知服务端点均不触发此降级。
5. 后续请求即使由不同 `ReplyHttpClient` 实例发出、换档位、换模型，或复用旧 payload，也不再发送这些参数，避免重复的失败往返。缓存是线程安全的进程内缓存，进程退出后清空，不保存凭据。不同端点路径互不影响；并发的已在途首次请求仍可能各自降级一次。
6. 重试沿用原异步可取消调用；取消会取消正在进行的首次或降级请求。成功响应仍限制为 1 MiB，错误正文最多读取 64 KiB，仅用于判定，不直接显示或写日志。

降级后无法继续保证最低思考强度；诊断明确显示该端点已拒绝参数、后续不下发。国产服务 LOW 以上也明确显示交服务默认预算，避免声称有精确预算控制。

## 本轮文件

- `app/src/main/java/dev/jev/wechatmood/reply/ThinkingEffort.kt`：新增五档枚举、标签、预算及缺省值解析。
- `app/src/main/java/dev/jev/wechatmood/reply/ReplySettings.kt`：effort、读写 key、兼容构造入口与完整 messages 路径保留。
- `app/src/main/java/dev/jev/wechatmood/analysis/AnalysisThinking.kt`：统一映射、幂等参数应用、端点能力缓存、降级判断和实际参数说明。
- `app/src/main/java/dev/jev/wechatmood/reply/ReplyHttpClient.kt`：payload 深拷贝、一次降级、错误正文读取、取消与原状态提示。
- `app/src/main/java/dev/jev/wechatmood/reply/ReplyProtocol.kt`、`TopicProtocol.kt`：接入统一 apply；现有 `analysis/LlmEmotionProtocol.kt`、`IntentProtocol.kt` 已有调用，无需修改。
- `app/src/main/java/dev/jev/wechatmood/reply/ReplyProvider.kt`、`core/IntentProfiles.kt`、`core/IntentSettings.kt`：独立模型配置读取和保存共同档位。
- `app/src/main/java/dev/jev/wechatmood/core/SettingsProvider.kt`、`SettingsSync.kt`：持久化设置快照和跨进程解码携带档位。
- `app/src/main/java/dev/jev/wechatmood/core/SettingsSession.kt`：档位加入分析指纹，保存后撤销旧分析缓存及在途结果。
- `app/src/main/java/dev/jev/wechatmood/hook/ReplyHostUi.kt`：回复发送前和结果接收时的配置检查加入 effort，避免异步准备阶段使用已变更的档位。
- `app/src/main/res/layout/intent_settings.xml`、`app/src/main/java/dev/jev/wechatmood/ui/IntentSettingsUi.kt`：档位 UI、保存、检测指纹与回页读取。
- `app/src/main/java/dev/jev/wechatmood/ui/ReplySettingsUi.kt`：读取现有档位，保存回复配置不会重置为默认。
- `app/src/main/java/dev/jev/wechatmood/core/Diagnostics.kt`：展示情绪分析、混合模式意图分析和回复模型的档位及实际参数。
- `app/src/test/java/dev/jev/wechatmood/analysis/AnalysisThinkingTest.kt`：新增纯 Kotlin / JVM 单测，覆盖默认值、持久化、所有后端映射、OFF/HIGH、缓存、诊断和四种协议。
- `app/src/test/java/dev/jev/wechatmood/analysis/ThinkingFallbackTransportTest.kt`：新增 JVM MockWebServer 测试源码，覆盖 400/422 一次降级、跨实例缓存、旧 payload、不重复重试、其它 HTTP 错误和重试取消。
- `app/src/test/java/dev/jev/wechatmood/analysis/LlmEmotionTest.kt`：仅增量更新两处已过时的白名单断言，保留上一轮情绪概率相关修改。
- `CHANGES-THINKING.md`：本说明；原 `CHANGES.md` 保持原内容。

## 静态自检与验证范围

- `rg` 确认 `LlmEmotionProtocol`、`IntentProtocol`、`ReplyProtocol`、`TopicProtocol` 四个 LLM payload 均调用 `AnalysisThinking.apply()`；共用 `ReplyHttpClient.request()` 也应用一次。JEV 是单独的决策模型协议，模型列表是 GET，均不属于思考参数的 LLM 请求入口。
- XML 解析、`git diff --check` 与新增字段/同步链路静态检查；对照本轮开始前的文件快照，确认无关已有修改原样保留。
- 恢复后已通过 Android Debug 构建和完整 JVM 单测（415 项，失败 / 错误 / 跳过均为 0），包含 10 项思考参数测试与 5 项降级传输测试。未进行真实 API 或微信设备交互测试；映射沿用原补丁提供的规范。
- 本轮增量 diff（包括新增文件）另存于 `/workspace/yanwai-thinking.diff`，便于与上一轮未提交修改分开审阅；该文件位于仓库外。
