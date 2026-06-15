# #23 — AI Research Chat in Flag Detail Modal

**Date**: 2026-06-15
**Status**: Draft
**Addresses**: Backlog #23 — Flag-detail: AI-research prompt interface (Phase 2)

---

## Goal

Replace the single-shot AI Analysis section in the Flag Detail modal with a multi-turn chat so operators can investigate flagged messages interactively with LLM assistance before deciding on a response.

## Current State

| Layer | Has | Missing |
|-------|-----|---------|
| UI (Flags.jsx) | Single-shot "Analyse" button, result block, copy button | Multi-turn chat, message list, user input |
| API (flags.js) | `analyse(id)` — POST, no body | `chat(id, messages)` — POST with history |
| admin-api FlagController | `POST /{id}/analyse` — no body | `POST /{id}/chat` — accepts message array |
| admin-api FlagService | `analyse()` — builds prompt, single call | `chat()` — builds system prompt, forwards history |
| llm-orchestrator OrchestratorController | `POST /api/analyse` — single prompt | `POST /api/chat` — accepts messages array |
| OpenAiCompatibleLlmClient | `call(model, systemPrompt, userContent, ...)` | `chat(model, messages, maxTokens, temperature)` |

## Design

### 1. OpenAiCompatibleLlmClient — new chat method

Add a new method that accepts a pre-built messages array instead of a single system + user pair:

```java
public String chat(String model, List<Map<String, String>> messages, int maxTokens, double temperature)
```

This passes the messages array directly to `/v1/chat/completions`. The existing `call()` method stays unchanged (used by Kafka consumers for single-turn tasks).

### 2. OrchestratorController — new POST /api/chat

```java
@PostMapping("/api/chat")
public Mono<ChatResponse> chat(@RequestBody ChatRequest request)
```

**ChatRequest:**
```java
record ChatRequest(List<ChatMessage> messages, String taskType) {}
record ChatMessage(String role, String content) {}
```

**ChatResponse:**
```java
record ChatResponse(boolean success, String content, String model) {}
```

Flow:
1. Default `taskType` to `"GENERAL"` if null
2. `selectModelForTask(taskType)` — reuses existing logic
3. Read active `LlmProviderConfig` — reuses existing logic
4. Convert `ChatMessage` list to `List<Map<String, String>>` for the client
5. Call `llmClient.chat(model, messages, maxTokens, temperature)`
6. Track cost via `CostTrackingService` — same as `analyse()`
7. Return `ChatResponse(true, assistantContent, modelName)`

`maxTokens`: 1024 (same as analyse). `temperature`: 0.3 (same as analyse).

### 3. admin-api — FlagController + FlagService

**New endpoint:**
```java
@PostMapping("/{id}/chat")
public Mono<JsonNode> chat(@PathVariable String id, @RequestBody JsonNode body)
```

**FlagService.chat():**
1. Fetch the decision from policy-engine: `PolicyEngineClient.getDecision(id)`
2. Build system prompt from flag context (same persona as current `analyse()`):
   ```
   You are a moderation analyst. You are assisting an operator investigating
   a flagged message. Here is the context:
   - Intent: {intent}
   - Decision: {decision}
   - Confidence: {confidence}%
   - Reason: {reason}
   - Message text: {messageText}

   Help the operator understand this flag and research appropriate responses.
   ```
3. Extract `messages` array from request body
4. Prepend the system message to the array
5. POST `{ messages, taskType: "GENERAL" }` to orchestrator `/api/chat`
6. Return the orchestrator response

The system prompt is always rebuilt from the current decision data — the UI never sends it.

**Deprecate `POST /{id}/analyse`:** Keep the endpoint but have it delegate to `chat()` internally, wrapping the auto-generated first user message. This avoids breaking anything if the old endpoint is called.

### 4. admin-ui — API layer (flags.js)

Replace `analyse(id)` with:
```js
chat(id, messages) // POST /api/flags/{id}/chat, body: { messages }
```

Where `messages` is an array of `{ role: 'user'|'assistant', content: '...' }`. The system prompt is not included — the backend adds it.

### 5. admin-ui — Flag Detail modal (Flags.jsx)

#### State changes

Replace the single-shot analysis state with chat state:

```js
const [chatMessages, setChatMessages] = useState([])  // { role, content }
const [chatInput, setChatInput] = useState('')
const [chatLoading, setChatLoading] = useState(false)
const [chatError, setChatError] = useState(null)
```

Remove: `analysing`, `analysisResult`, `analysisCopied`.

Clear all chat state when modal closes (reset on close).

#### Section rename

"AI Analysis" → "AI Research". Same collapsible pattern (SectionLabel with chevron).

#### First turn — "Analyse" button

Clicking "Analyse" builds the same prompt as today's `buildAnalysisPrompt()` (but in JS), adds it as the first user message, sends to `chat()`, appends both user and assistant messages to `chatMessages`.

```js
const handleAnalyse = async () => {
  const userMsg = `Analyse this flagged message:\n- Intent: ${flag.intent}\n- Decision: ${flag.decision}\n- Confidence: ${(flag.confidence * 100).toFixed(1)}%\n- Reason: ${flag.reason}\n- Message: ${flag.metadata?.messageText || 'N/A'}`
  const newMessages = [...chatMessages, { role: 'user', content: userMsg }]
  setChatMessages(newMessages)
  setChatLoading(true)
  try {
    const res = await api.chat(flag.id, newMessages)
    setChatMessages([...newMessages, { role: 'assistant', content: res.content }])
  } catch (e) {
    setChatError(e.message)
  } finally {
    setChatLoading(false)
  }
}
```

#### Follow-up turns

Text input + Send button below the message list. Same pattern as `handleAnalyse` but with the operator's typed message.

```js
const handleSend = async () => {
  if (!chatInput.trim()) return
  const userMsg = { role: 'user', content: chatInput.trim() }
  const newMessages = [...chatMessages, userMsg]
  setChatMessages(newMessages)
  setChatInput('')
  setChatLoading(true)
  try {
    const res = await api.chat(flag.id, newMessages)
    setChatMessages([...newMessages, { role: 'assistant', content: res.content }])
  } catch (e) {
    setChatError(e.message)
  } finally {
    setChatLoading(false)
  }
}
```

#### Message list rendering

Simple list inside a scrollable container:

- **User messages**: right-aligned or left-aligned with "You:" prefix, lighter background
- **Assistant messages**: left-aligned, same styling as current `analysisBlock` / `analysisText`
- Each assistant message has a small "Copy" button
- Model attribution line on assistant messages (same `analysisModel` style)
- Auto-scroll to bottom on new messages

#### Analyse / Re-analyse button

- Shows "Analyse" when `chatMessages.length === 0`
- Hidden once the first analysis is done (operator uses the input field for follow-ups)
- "Clear" button to reset the chat and start over

#### Loading state

Show a "Thinking..." indicator at the bottom of the message list while waiting for a response. The Send button and input are disabled during loading.

### 6. CSS additions (Flags.module.css)

New classes needed:

| Class | Purpose |
|-------|---------|
| `.chatMessages` | Scrollable message container, max-height ~300px |
| `.chatMessage` | Single message wrapper |
| `.chatMessageUser` | User message styling (slightly different background) |
| `.chatMessageAssistant` | Assistant message styling (reuse `analysisBlock` colors) |
| `.chatMessageContent` | Pre-wrapped text content |
| `.chatMessageMeta` | Model attribution line (reuse `analysisModel` styling) |
| `.chatInputRow` | Flex row: input + send button |
| `.chatInput` | Text input (reuse `replyTextarea` styling, single line or 2-3 rows) |
| `.chatClearBtn` | Clear/reset button |

### 7. Resilience

The orchestrator WebClient call in `FlagService.chat()` uses the existing `orchestratorWebClient` bean. Add retry + circuit breaker matching the pattern from #40 (RetryOperator → CircuitBreakerOperator). No fallback — chat errors should be shown to the operator, not silently swallowed.

## Affected files

| File | Change |
|------|--------|
| `emcip-llm-orchestrator/.../client/OpenAiCompatibleLlmClient.java` | Add `chat()` method |
| `emcip-llm-orchestrator/.../controller/OrchestratorController.java` | Add `POST /api/chat` endpoint |
| `emcip-llm-orchestrator/.../dto/ChatRequest.java` | New record |
| `emcip-llm-orchestrator/.../dto/ChatResponse.java` | New record |
| `emcip-admin-api/.../controller/FlagController.java` | Add `POST /{id}/chat` |
| `emcip-admin-api/.../service/FlagService.java` | Add `chat()` method, refactor `analyse()` to delegate |
| `emcip-admin-ui/.../api/flags.js` | Replace `analyse()` with `chat()` |
| `emcip-admin-ui/.../pages/Flags/Flags.jsx` | Chat UI replacing AI Analysis section |
| `emcip-admin-ui/.../pages/Flags/Flags.module.css` | Chat styling classes |

## Not in scope

- Streaming responses (would need SSE/WebSocket — add later)
- Knowledge base integration (depends on #27 Deep Research Agent)
- Conversation persistence (reset on modal close)
- Typing indicators
- Token count display or cost feedback to operator
- Changes to the Reply section (stays as-is)
