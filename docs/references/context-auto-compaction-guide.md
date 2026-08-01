# 上下文自动压缩改造指导意见

审阅日期：2026-08-01  
范围：记录改造前基线、参考实现和后续设计。本文所述“当前实现”指改造前代码。

> 2026-08-01 实施更新：手动压缩已改为写入独立摘要与消息边界，原始
> `message_node` 不再删除。模型请求读取“摘要 + 边界后的原始消息”。设置页已增加
> 自动压缩开关和触发百分比，已知 `Model.contextLength` 的模型会在请求前检查预算。
> 流式生成结果按消息 ID 合并回完整会话，摘要前缀不会写入或选中任何原始消息节点。

## 1. 现象确认

### 1.1 改造前：手动压缩会让旧消息从当前会话中消失

现象已由代码直接确认：

- `CompressContextDialog.kt` 的警告文案明确写着“压缩上下文将重置当前对话中的所有消息”。
- `ChatService.compressConversation()` 将旧消息分成“待压缩部分”和最近消息，调用压缩模型后构造新的 `messageNodes`，最后用 `conversation.copy(messageNodes = newMessageNodes)` 覆盖原会话。
- `ConversationRepository.updateConversation()` 在写入新节点前执行 `messageNodeDAO.deleteByConversation()`，再插入新节点。
- `message_node` 表没有归档副本，全文索引也会按新会话重建。因此旧节点并非只从界面隐藏，当前实现下确实没有可恢复的历史副本。

关键位置：

```text
app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:1252-1341
app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt:296-306
app/src/main/java/me/rerere/rikkahub/data/db/dao/MessageNodeDAO.kt:37-38
app/src/main/res/values/strings.xml:273
```

### 1.2 改造前：没有通用的自动压缩或溢出恢复

`compressConversation()` 的调用链只有聊天页的手动入口：

```text
FilesPicker -> ChatPage -> ChatVM.handleCompressContext -> ChatService.compressConversation
```

生成链路直接把 `conversation.currentMessages` 交给 `GenerationHandler`。发送失败时，`ChatService.handleMessageComplete()` 只保存状态并调用 `addError()`，没有检测上下文溢出、调用压缩模型、重建请求或自动重试。因此当提供商拒绝过长上下文时，用户看到的“执行到一半停止”是当前设计的合理结果。

补充限制：

- `Assistant.contextMessageSize` 默认值为 `0`，表示不限条数。
- `limitContext()` 只按消息条数截取，保留完整工具调用依赖，不做摘要，也不按 token 预算。
- `AICoreProvider` 对 Gemini Nano 特殊保留最近 6 条消息，这是提供商专用截断，不是通用自动压缩。
- `Model.contextLength` 已存在，但目前没有接入生成请求预算。
- 半途停止也可能来自网络错误、工具墙钟预算或最大步骤数，后续应通过日志中的错误类型区分，不能把所有停止都归因于上下文溢出。

关键位置：

```text
app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:858-864, 1018-1036
app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt:990
ai/src/main/java/me/rerere/ai/ui/Message.kt:272-315
app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt:27
ai/src/main/java/me/rerere/ai/provider/Model.kt:21
```

### 1.3 改造前：设置页没有自动压缩开关和阈值

设置中已有压缩模型和压缩提示词：

```text
compressModelId
compressPrompt
```

它们只决定手动压缩时使用的模型和提示词。`Settings` 没有 `enabled`、触发比例、保留预算、失败重试等自动压缩配置，设置页也没有对应控件。因此该现象确认成立。

## 2. 参考实现

已将以下项目浅克隆到 `D:\Temp`：

- OpenCode：`D:\Temp\opencode`，提交 `19231fce4b70aa5f7894a0a0eb20ff29bd417db5`
- Aider：`D:\Temp\aider`，提交 `5dc9490bb35f9729ef2c95d00a19ccd30c26339c`

### OpenCode 的可借鉴点

参考文件：

```text
D:\Temp\opencode\packages\opencode\src\session\compaction.ts
D:\Temp\opencode\packages\opencode\src\session\overflow.ts
D:\Temp\opencode\packages\opencode\src\session\processor.ts
D:\Temp\opencode\packages\core\src\session\compaction.ts
D:\Temp\opencode\packages\core\src\session\history.ts
```

核心做法：

1. 用模型的 context limit 减去输出预算和安全缓冲，得到可用输入预算。
2. 在请求前按 token 估算触发自动压缩，在提供商返回 overflow 时再走一次恢复路径。
3. 按完整 user turn 保留尾部，工具调用和工具结果不可拆开。尾部优先按 token 预算选择，而不是固定消息条数。
4. 压缩摘要是一个带边界标记的持久化事件。旧消息仍保存在数据库，加载给模型时从最近的压缩边界开始读取。
5. 摘要失败时保留原会话并报告错误；成功后生成 synthetic continuation，让原请求最多自动继续一次。
6. 对旧工具输出做单独截断或标记，防止一条工具结果占满整个压缩请求。
7. 每次压缩带 `auto`、`overflow`、尾部起点等元数据，便于 UI 展示、回退和诊断。

### Aider 的可借鉴点

参考文件：

```text
D:\Temp\aider\aider\history.py
```

核心做法：

1. 用模型 tokenizer 统计每条消息的 token 数，按总预算判断是否过大。
2. 先保留尾部约一半预算，头部发送给摘要模型。
3. 摘要加尾部仍超限时递归压缩，设置深度上限，避免压缩循环。
4. 摘要请求预留安全空间，并支持多个模型依次重试。
5. 摘要结果以明确的 summary 前缀作为后续上下文的一部分。

本项目应优先采用 OpenCode 的“持久化边界 + 自动继续”结构，再吸收 Aider 的 tokenizer、递归压缩和多模型失败回退。

## 3. 推荐的目标设计

### 3.1 数据层：压缩只改变模型视图，不删除原文

建议新增独立的 `conversation_compaction` 表，而不是继续覆盖 `message_node`：

- `id`
- `conversation_id`
- `sequence`
- `source_end_node_id` 或可稳定定位的源范围
- `tail_start_node_id`
- `summary`
- `summary_model_id`
- `auto`、`overflow`、`status`
- `created_at`、`completed_at`、错误信息

压缩成功时在同一 Room 事务中写入一条压缩记录和活动边界。原始 `message_node` 保留，聊天历史页仍能查看完整记录；请求构造器只读取“最新摘要 + 边界之后的原始尾部”。

手动压缩和自动压缩必须共用同一个服务。手动压缩不再调用 `updateConversation()` 删除节点，只创建一条手动压缩记录。这样用户可以查看压缩前后的差异，也能在失败时继续使用原上下文。

### 3.2 配置层：增加可解释的自动压缩设置

建议在 `Settings` 增加：

- `autoCompactionEnabled`
- `autoCompactionTriggerRatio`，默认约 `0.80`
- `compactionReservedOutputTokens`
- `compactionPreserveRecentTokens` 或保留 turn 数
- `compactionMaxRetriesPerTurn`，固定为 1，防止循环
- 继续复用已有的 `compressModelId` 和 `compressPrompt`

设置页应显示当前模型的 context length、估算的触发点和“压缩不会删除原始历史”的说明。已有用户的迁移默认值需要明确：建议升级用户默认关闭并提示一次，新安装可默认开启，避免升级后突然产生额外摘要请求和费用。

### 3.3 请求层：增加统一的 `ContextBudgetPlanner`

不要在 `ChatService` 中按消息条数猜测。建议在 `GenerationHandler` 已经组装好系统提示、历史、工具描述和输入变换之后调用规划器：

1. 获取 `Model.contextLength`，未知时只使用提供商 overflow 恢复，不做激进截断。
2. 估算完整请求 token，扣除输出上限和安全缓冲。
3. 超过触发比例时，选择完整的历史 turn 作为压缩头部。
4. 保留当前用户 turn、未完成工具调用及其结果，不能在工具审批期间压缩半个调用。
5. 生成或更新 anchored summary，摘要中合并上一次 summary，并删除已经过时的事实。
6. 返回“摘要 + 原始尾部”的模型消息视图，同时把压缩事件持久化。

优先使用各提供商的真实 usage 反馈校准估算；没有 tokenizer 时使用保守估算，并始终预留输出和工具 schema 的空间。不要把 `contextMessageSize` 当成 token limit，它只能作为用户手动限制历史条数的兼容选项。

### 3.4 自动流程与失败语义

每个用户 turn 和每个工具 step 都应走同一个预算检查：

```text
组装请求
  -> 未超阈值：正常发送
  -> 接近阈值：先压缩，再发送
  -> 提供商返回 context overflow：压缩并重试一次
  -> 摘要成功：从压缩边界重建请求并自动继续一次
  -> 摘要失败/再次 overflow：保留原始消息，显示明确错误，不删除历史
```

实现时需要：

- 每个会话一个 compaction mutex，避免用户点击手动压缩和自动压缩并发写入。
- 用 compaction epoch 或重试计数阻止“压缩后仍超限”的无限循环。
- 自动继续消息标记为 synthetic，不显示成用户真实输入。
- 压缩中断、网络失败、模型未配置、提供商禁用时都保持原始会话可继续。
- 后台任务、Telegram、子代理也必须复用该服务，不能只在聊天页接入。
- 记录开始、成功、失败、overflow、保留 token 和摘要模型，便于确认“执行到一半停止”的真实原因。

### 3.5 UI 处理

- 在模型或助手设置中加入自动压缩开关、触发比例、保留 turn/token 和压缩模型选择。
- 会话页显示“正在压缩上下文”状态，禁止重复触发。
- 压缩记录作为时间线标记展示，提供查看摘要、查看压缩前历史和回退活动边界的入口。
- 手动压缩确认文案改为“为模型请求生成摘要，原始消息仍保留”，避免继续暗示消息被重置。

## 4. 实施顺序

1. 先增加 Room 实体、迁移、DAO 和 `ConversationCompaction` 领域对象，补充“压缩失败不删除任何节点”的测试。
2. 实现 token 预算规划器、完整 turn 选择、工具输出截断和 anchored summary 提示词。
3. 将手动压缩改为写入压缩记录，并在 `GenerationHandler` 请求前接入统一规划器。
4. 在 provider overflow 错误路径接入一次自动压缩和自动继续。
5. 增加设置页、会话状态、历史查看/回退和日志指标。
6. 为 OpenAI、Claude、Google、OpenRouter、AICore、工具调用、图片附件、并发压缩和迁移分别补测试，再进行真机验证。

## 5. 验收标准

- 手动或自动压缩后，原始 `message_node` 数量不减少，压缩摘要和边界可查询。
- 下一次请求只发送摘要与尾部，且工具调用/结果保持完整。
- 已知 context length 的模型在达到阈值前自动压缩，超过阈值后的 provider overflow 最多自动恢复一次。
- 摘要模型失败、网络失败或压缩请求再次超限时，原会话可继续，用户能看到具体原因。
- 重复压缩不会重复累积旧摘要；新摘要会合并并替换上一条活动摘要。
- 设置中可关闭自动压缩，且关闭后 overflow 不会静默删除或改写历史。
- 日志能区分 context overflow、网络错误、工具墙钟预算、最大步骤数和用户主动停止。

本次实现已使用本机 Android Studio JBR 与 Android SDK 通过 Kotlin 编译、上下文压缩单元测试和完整 Debug APK 构建。
