# #41b — Reply Composer v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the Decisions reply composer from a 2-mode inline textarea to an extracted 4-mode `ReplyComposer` component with mode hints, chip-row templates, character counter, and structured footer. Add backend NOTE target for internal operator notes.

**Architecture:** Backend adds a `NOTE` intercept in `FlagService.reply()` — skips TDLib, publishes an `OPERATOR_NOTE` audit event via Kafka, returns `messageId: 0`. Frontend extracts all reply state/UI from `FlagDetailModal` into a new `ReplyComposer` component with 4-mode SegmentedControl (Group, Quote, DM, Note), mode hints, hardcoded chip-row templates, visible character counter, and Discard/Send footer.

**Tech Stack:** Java 21 / Spring Boot 4 / Reactor / Kafka (backend); React / CSS Modules / Vite (frontend)

**Spec:** `docs/superpowers/specs/2026-06-15-reply-composer-v2-design.md`

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `emcip-admin-api/src/main/java/io/emcip/admin/api/service/FlagService.java` | Modify | Add NOTE intercept in `reply()`, add `publishNoteAuditEvent()` |
| `emcip-admin-api/src/test/java/io/emcip/admin/api/service/FlagServiceTest.java` | Modify | Add test for NOTE target |
| `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/FlagControllerTest.java` | Modify | Add test for NOTE reply via controller |
| `emcip-admin-ui/src/main/frontend/src/pages/Flags/ReplyComposer.jsx` | Create | Extracted 4-mode composer component |
| `emcip-admin-ui/src/main/frontend/src/pages/Flags/ReplyComposer.module.css` | Create | Composer styles |
| `emcip-admin-ui/src/main/frontend/src/pages/Flags/Flags.jsx` | Modify | Remove reply state/handler/JSX, render `<ReplyComposer>` |
| `emcip-admin-ui/src/main/frontend/src/pages/Flags/Flags.module.css` | Modify | Remove unused reply classes |
| `documentation/architecture-guide.adoc` | Modify | Document NOTE target |

---

### Task 1: Backend — NOTE code path in FlagService

**Files:**
- Modify: `emcip-admin-api/src/main/java/io/emcip/admin/api/service/FlagService.java:80-130`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/service/FlagServiceTest.java`
- Modify: `emcip-admin-api/src/test/java/io/emcip/admin/api/controller/FlagControllerTest.java`

**Context:** `FlagService.reply()` currently fetches flag metadata from the policy engine, resolves a Telegram account, calls `sendAndAudit()` to send via TDLib and publish an `OPERATOR_REPLY` audit event. For `NOTE` target, we intercept after fetching metadata (we need `chatId` for the audit event) but before resolving accounts (no account needed).

- [ ] **Step 1: Write the failing test for NOTE target in FlagServiceTest**

Add this test to `FlagServiceTest.java` after the existing `reply_missingMetadata_returnsError` test:

```java
@Test
void reply_noteTarget_skipsAccountResolutionAndReturnsSuccess() {
    ObjectNode flag = JsonNodeFactory.instance.objectNode();
    ObjectNode meta = flag.putObject("metadata");
    meta.put("chatId", 12345L);
    meta.put("senderId", "user:999");
    when(policyEngineClient.getDecision("flag-1")).thenReturn(Mono.just(flag));

    StepVerifier.create(flagService.reply("flag-1", "Internal note", "NOTE", false, false, null))
            .expectNextMatches(
                    resp ->
                            resp.messageId() == 0L
                                    && "NOTE".equals(resp.target())
                                    && !resp.markedActioned())
            .verifyComplete();

    org.mockito.Mockito.verify(kafkaTemplate)
            .send(org.mockito.ArgumentMatchers.any(ProducerRecord.class));
    org.mockito.Mockito.verifyNoInteractions(groupProfileRepository);
}
```

Add the missing import at the top of the file:

```java
import org.apache.kafka.clients.producer.ProducerRecord;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-admin-api -Dtest="FlagServiceTest#reply_noteTarget_skipsAccountResolutionAndReturnsSuccess" -Dsurefire.useFile=false -q 2>&1 | cat`

Expected: FAIL — the current `reply()` method doesn't handle NOTE specially, so it tries to resolve metadata and accounts normally.

- [ ] **Step 3: Implement NOTE intercept in FlagService.reply()**

In `FlagService.java`, replace the `reply()` method (lines 80–130) with this version that intercepts NOTE after metadata extraction:

```java
public Mono<FlagController.ReplyResponse> reply(
        String flagId,
        String text,
        String target,
        boolean replyToOriginal,
        boolean prefixModerator,
        UUID accountId) {

    return policyEngineClient
            .getDecision(flagId)
            .flatMap(
                    flag -> {
                        JsonNode meta = flag.get("metadata");
                        if (meta == null || meta.isNull()) {
                            return Mono.error(
                                    new IllegalArgumentException("Flag has no metadata"));
                        }
                        long chatId = meta.get("chatId").asLong();

                        if ("NOTE".equalsIgnoreCase(target)) {
                            publishNoteAuditEvent(flagId, text, chatId);
                            return Mono.just(
                                    new FlagController.ReplyResponse(0L, "NOTE", false));
                        }

                        String senderId =
                                meta.has("senderId") ? meta.get("senderId").asText() : null;
                        long telegramMessageId =
                                meta.has("telegramMessageId")
                                        ? meta.get("telegramMessageId").asLong()
                                        : 0L;

                        return groupProfileRepository
                                .findByTelegramChatId(chatId)
                                .switchIfEmpty(
                                        Mono.error(
                                                new IllegalArgumentException(
                                                        "No group profile found for chatId "
                                                                + chatId)))
                                .flatMap(profile -> resolveAccount(accountId, profile, chatId))
                                .map(
                                        account ->
                                                new AccountWithMeta(
                                                        account,
                                                        chatId,
                                                        senderId,
                                                        telegramMessageId));
                    })
            .flatMap(
                    awm ->
                            sendAndAudit(
                                    awm,
                                    flagId,
                                    text,
                                    target,
                                    replyToOriginal,
                                    prefixModerator));
}
```

Add the `publishNoteAuditEvent` method after the existing `publishAuditEvent` method (after line 361):

```java
private void publishNoteAuditEvent(String flagId, String noteText, long chatId) {
    try {
        ObjectNode event = JsonNodeFactory.instance.objectNode();
        event.put("eventType", "OPERATOR_NOTE");
        event.put("action", "ADD_NOTE");
        event.put("sourceService", "admin-api");
        event.put("resourceId", flagId);
        event.put("outcome", "SUCCESS");

        ObjectNode details = event.putObject("details");
        details.put("target", "NOTE");
        details.put("noteText", noteText);
        details.put("chatId", chatId);

        String json = objectMapper.writeValueAsString(event);
        kafkaTemplate.send(new ProducerRecord<>("audit.events", flagId, json));
    } catch (JacksonException e) {
        log.error("Failed to publish note audit event for flag {}", flagId, e);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-admin-api -Dtest="FlagServiceTest#reply_noteTarget_skipsAccountResolutionAndReturnsSuccess" -Dsurefire.useFile=false -q 2>&1 | cat`

Expected: PASS

- [ ] **Step 5: Write the controller-level test for NOTE reply**

Add this test to `FlagControllerTest.java` after the existing `reply_returns201` test:

```java
@Test
void reply_noteTarget_returns201WithMessageIdZero() {
    when(flagService.reply("flag-1", "Internal note", "NOTE", false, false, null))
            .thenReturn(Mono.just(new FlagController.ReplyResponse(0L, "NOTE", false)));

    webTestClient
            .post()
            .uri("/api/flags/flag-1/reply")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                    Map.of(
                            "text",
                            "Internal note",
                            "target",
                            "NOTE",
                            "replyToOriginal",
                            false,
                            "prefixModerator",
                            false))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody()
            .jsonPath("$.messageId")
            .isEqualTo(0)
            .jsonPath("$.target")
            .isEqualTo("NOTE");
}
```

- [ ] **Step 6: Run all FlagService and FlagController tests**

Run: `cd /home/ben/Development/ecip && mvn test -pl emcip-admin-api -Dtest="FlagServiceTest,FlagControllerTest" -Dsurefire.useFile=false -q 2>&1 | cat`

Expected: All tests PASS (5 in FlagServiceTest, 9 in FlagControllerTest)

- [ ] **Step 7: Run Spotless and commit**

```bash
cd /home/ben/Development/ecip
mvn spotless:apply -pl emcip-admin-api -q 2>&1 | cat
git add emcip-admin-api/src/main/java/io/emcip/admin/api/service/FlagService.java \
        emcip-admin-api/src/test/java/io/emcip/admin/api/service/FlagServiceTest.java \
        emcip-admin-api/src/test/java/io/emcip/admin/api/controller/FlagControllerTest.java
git commit -m "feat(admin-api): add NOTE target in FlagService.reply()

Skip TDLib send for NOTE target, publish OPERATOR_NOTE audit event
to audit.events Kafka topic with note text. Return messageId: 0.
Part of #41b — reply composer v2."
```

---

### Task 2: Frontend — ReplyComposer.module.css

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Flags/ReplyComposer.module.css`

**Context:** This CSS module provides styles for the extracted ReplyComposer component. It follows the project's design system: semantic tokens only, no hex values, no border-radius on data surfaces, mono font for counters/hints, display font for labels.

- [ ] **Step 1: Create ReplyComposer.module.css**

```css
/* Reply Composer — extracted from Flags.module.css + new styles */

.composer {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  margin-top: var(--sp-2);
}

.modeHint {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
  margin: 0;
}

/* Chip row */
.chipRow {
  display: flex;
  gap: var(--sp-1);
  flex-wrap: wrap;
}

.chip {
  padding: 3px 10px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-2);
  background: transparent;
  border: 1px solid var(--border);
  border-radius: 0;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s;
}

.chip:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.chipGhost {
  composes: chip;
  border-style: dashed;
}

/* Textarea */
.textarea {
  width: 100%;
  min-height: 80px;
  padding: 9px 12px;
  border: 1px solid var(--border);
  border-radius: 0;
  background: var(--bg-input);
  color: var(--fg-1);
  font-family: var(--font-mono);
  font-size: 13px;
  resize: vertical;
  box-sizing: border-box;
  outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.textarea:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 1px var(--accent), 0 0 12px var(--orb-glow);
}

/* Options row (checkboxes) */
.options {
  display: flex;
  gap: var(--sp-3);
  align-items: center;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--fg-2);
}

.options label {
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  font-family: var(--font-mono);
  font-size: 12px;
}

/* Account selector */
.accountSelect {
  padding: 9px 12px;
  border: 1px solid var(--border);
  border-radius: 0;
  background: var(--bg-input);
  color: var(--fg-1);
  font-family: var(--font-mono);
  font-size: 13px;
  outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.accountSelect:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 1px var(--accent), 0 0 12px var(--orb-glow);
}

/* Footer */
.footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.footerActions {
  display: flex;
  gap: var(--sp-2);
  align-items: center;
}

.charCounter {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

/* Success + actioned prompt */
.success {
  color: var(--signal-ok-fg);
  font-family: var(--font-mono);
  font-size: 12px;
}

.actionedPrompt {
  display: flex;
  gap: var(--sp-2);
  align-items: center;
}

/* Alert banner */
.alertBanner {
  color: var(--signal-stop-fg);
  background: rgba(248, 113, 113, 0.08);
  border: 1px solid rgba(248, 113, 113, 0.25);
  padding: 8px 12px;
  font-family: var(--font-mono);
  font-size: 12px;
}
```

- [ ] **Step 2: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-admin-ui/src/main/frontend/src/pages/Flags/ReplyComposer.module.css
git commit -m "style: add ReplyComposer.module.css

Styles for the extracted reply composer: mode hints, chip row,
textarea, options, footer with char counter, success/error states.
Part of #41b — reply composer v2."
```

---

### Task 3: Frontend — ReplyComposer component

**Files:**
- Create: `emcip-admin-ui/src/main/frontend/src/pages/Flags/ReplyComposer.jsx`

**Context:** This component extracts all reply state and UI from `FlagDetailModal` in `Flags.jsx`. It receives `flagId`, `api`, and `onActioned` as props. It manages its own state for reply text, target mode, sending, errors, success, account selection, and the "mark as actioned" prompt. The SegmentedControl component accepts `options: [{ value, label }]`, `value`, and `onChange` props.

**Important:** `QUOTE` is a frontend-only mode. When sending, it maps to `target: 'GROUP'` with `replyToOriginal: true`. The API call uses `api.reply(flagId, body)` which POSTs to `/api/flags/{id}/reply`.

- [ ] **Step 1: Create ReplyComposer.jsx**

```jsx
import { useState } from 'react'
import { Button } from '../../components/Button/Button'
import { SegmentedControl } from '../../components/SegmentedControl/SegmentedControl'
import styles from './ReplyComposer.module.css'

const MODES = [
  { value: 'GROUP', label: 'Group' },
  { value: 'QUOTE', label: 'Quote' },
  { value: 'DM', label: 'DM' },
  { value: 'NOTE', label: 'Note' },
]

const MODE_HINTS = {
  GROUP: 'Posts in the group chat',
  QUOTE: 'Replies to the original message in the group',
  DM: 'Sends a direct message to the sender',
  NOTE: 'Internal note \u2014 not sent to Telegram',
}

const TEMPLATES = [
  'Thank you for reporting.',
  'No action needed.',
  'This has been reviewed.',
  'Warning issued.',
]

const MAX_LENGTH = 4096

export function ReplyComposer({ flagId, api, onActioned }) {
  const [text, setText] = useState('')
  const [mode, setMode] = useState('GROUP')
  const [replyToOriginal, setReplyToOriginal] = useState(true)
  const [prefixModerator, setPrefixModerator] = useState(false)
  const [sending, setSending] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState(false)
  const [accounts, setAccounts] = useState(null)
  const [selectedAccountId, setSelectedAccountId] = useState(null)
  const [promptActioned, setPromptActioned] = useState(false)

  const isNote = mode === 'NOTE'

  const handleSend = async () => {
    setSending(true)
    setError('')
    setSuccess(false)
    try {
      const target = mode === 'QUOTE' ? 'GROUP' : mode
      const forceReplyToOriginal = mode === 'QUOTE' ? true : replyToOriginal
      await api.reply(flagId, {
        text,
        target,
        replyToOriginal: forceReplyToOriginal,
        prefixModerator: isNote ? false : prefixModerator,
        accountId: selectedAccountId,
      })
      setSuccess(true)
      setPromptActioned(true)
      setText('')
    } catch (e) {
      if (e.status === 409 && e.body?.accounts) {
        setAccounts(e.body.accounts)
        setError('Multiple accounts watch this chat \u2014 select one below.')
      } else {
        setError(e.message || 'Failed to send reply')
      }
    } finally {
      setSending(false)
    }
  }

  const handleDiscard = () => {
    setText('')
    setMode('GROUP')
    setReplyToOriginal(true)
    setPrefixModerator(false)
    setError('')
    setSuccess(false)
    setAccounts(null)
    setSelectedAccountId(null)
    setPromptActioned(false)
  }

  const handleMarkActioned = async () => {
    try {
      await onActioned()
      setPromptActioned(false)
    } catch (e) {
      setError(e.message)
    }
  }

  const modeLabel = MODES.find(m => m.value === mode)?.label ?? mode

  return (
    <div className={styles.composer}>
      <SegmentedControl options={MODES} value={mode} onChange={setMode} />

      <p className={styles.modeHint}>{MODE_HINTS[mode]}</p>

      <div className={styles.chipRow}>
        {TEMPLATES.map(t => (
          <button key={t} type="button" className={styles.chip} onClick={() => setText(t)}>
            {t}
          </button>
        ))}
        <button type="button" className={styles.chipGhost} onClick={() => setText('')}>
          Clear
        </button>
      </div>

      <textarea
        className={styles.textarea}
        placeholder={isNote ? 'Write an internal note\u2026' : 'Type your response\u2026'}
        value={text}
        onChange={e => setText(e.target.value)}
        maxLength={MAX_LENGTH}
      />

      {mode !== 'NOTE' && mode !== 'QUOTE' && (
        <div className={styles.options}>
          {mode === 'GROUP' && (
            <label>
              <input type="checkbox" checked={replyToOriginal} onChange={e => setReplyToOriginal(e.target.checked)} />
              Reply to original
            </label>
          )}
          {mode === 'DM' && (
            <label>
              <input type="checkbox" checked={replyToOriginal} onChange={e => setReplyToOriginal(e.target.checked)} />
              Reply to original
            </label>
          )}
          <label>
            <input type="checkbox" checked={prefixModerator} onChange={e => setPrefixModerator(e.target.checked)} />
            Prefix [Moderator]
          </label>
        </div>
      )}

      {mode === 'QUOTE' && (
        <div className={styles.options}>
          <label>
            <input type="checkbox" checked={prefixModerator} onChange={e => setPrefixModerator(e.target.checked)} />
            Prefix [Moderator]
          </label>
        </div>
      )}

      {accounts && !isNote && (
        <select
          className={styles.accountSelect}
          value={selectedAccountId ?? ''}
          onChange={e => setSelectedAccountId(e.target.value || null)}
        >
          <option value="">Select account...</option>
          {accounts.map(a => (
            <option key={a.id} value={a.id}>{a.displayName} ({a.phoneNumber})</option>
          ))}
        </select>
      )}

      <div className={styles.footer}>
        <span className={styles.charCounter}>
          {text.length} / {MAX_LENGTH} {'\u00b7'} {modeLabel}
        </span>
        <div className={styles.footerActions}>
          {promptActioned ? (
            <div className={styles.actionedPrompt}>
              <span className={styles.success}>{isNote ? 'Saved!' : 'Sent!'} Mark as actioned?</span>
              <Button variant="secondary" onClick={handleMarkActioned}>Yes</Button>
              <Button variant="secondary" onClick={() => setPromptActioned(false)}>No</Button>
            </div>
          ) : (
            <>
              <Button variant="secondary" onClick={handleDiscard}>Discard</Button>
              <Button onClick={handleSend} disabled={sending || !text.trim()}>
                {sending ? (isNote ? 'Saving\u2026' : 'Sending\u2026') : (isNote ? 'Save note' : 'Send reply')}
              </Button>
            </>
          )}
        </div>
      </div>

      {error && (
        <p role="alert" className={styles.alertBanner}>{error}</p>
      )}
    </div>
  )
}
```

- [ ] **Step 2: Verify the component renders without errors**

Run: `cd /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend && npx vite build --logLevel error 2>&1 | cat`

Expected: Build succeeds (the component isn't wired into any page yet, but imports should resolve).

- [ ] **Step 3: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-admin-ui/src/main/frontend/src/pages/Flags/ReplyComposer.jsx
git commit -m "feat(admin-ui): add ReplyComposer component

4-mode SegmentedControl (Group, Quote, DM, Note), mode hints,
chip-row templates, char counter, Discard/Send footer.
Quote maps to GROUP + replyToOriginal. Note skips TDLib.
Part of #41b — reply composer v2."
```

---

### Task 4: Frontend — Wire ReplyComposer into Flags.jsx and clean up

**Files:**
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Flags/Flags.jsx:4-9,84-134,254-317`
- Modify: `emcip-admin-ui/src/main/frontend/src/pages/Flags/Flags.module.css:137-215`

**Context:** Replace all reply state, handler, and JSX in `FlagDetailModal` with a single `<ReplyComposer>` render. Remove unused reply CSS classes from `Flags.module.css`. The `SegmentedControl` import in `Flags.jsx` is no longer needed (only used by the reply section). The `ReplyComposer` receives `flagId`, `api` (the flags API object), and `onActioned` (callback to mark as actioned).

- [ ] **Step 1: Add ReplyComposer import to Flags.jsx**

At the top of `Flags.jsx`, add the import after the existing component imports (after line 9):

```jsx
import { ReplyComposer } from './ReplyComposer'
```

Remove the `SegmentedControl` import (line 8) since it's no longer used in this file:

```jsx
// DELETE this line:
import { SegmentedControl } from '../../components/SegmentedControl/SegmentedControl'
```

- [ ] **Step 2: Remove reply state from FlagDetailModal**

In `FlagDetailModal`, remove lines 84–94 (the reply state declarations):

```jsx
// DELETE these lines:
  const [showReply, setShowReply] = useState(false)
  const [replyText, setReplyText] = useState('')
  const [replyTarget, setReplyTarget] = useState('GROUP')
  const [replyToOriginal, setReplyToOriginal] = useState(true)
  const [prefixModerator, setPrefixModerator] = useState(false)
  const [replySending, setReplySending] = useState(false)
  const [replyError, setReplyError] = useState('')
  const [replySuccess, setReplySuccess] = useState(false)
  const [accounts, setAccounts] = useState(null)
  const [selectedAccountId, setSelectedAccountId] = useState(null)
  const [promptActioned, setPromptActioned] = useState(false)
```

Keep `showReply` and `setShowReply` — they control the collapsible section toggle:

```jsx
  const [showReply, setShowReply] = useState(false)
```

- [ ] **Step 3: Remove handleReply and handleMarkActioned functions**

Delete the `handleReply` function (lines 109–134) and `handleMarkActioned` function (lines 190–198) from `FlagDetailModal`. These are now handled inside `ReplyComposer`.

- [ ] **Step 4: Replace reply section JSX**

Replace the reply section (lines 258–317, from `{showReply && (` to its closing `)}`) with:

```jsx
      {showReply && (
        <ReplyComposer
          flagId={flag.id}
          api={api}
          onActioned={() => onStatusChange(flag.id, 'ACTIONED')}
        />
      )}
```

- [ ] **Step 5: Remove unused reply CSS classes from Flags.module.css**

Remove these class blocks from `Flags.module.css` (lines 142–215):

- `.replySection` (lines 142–147)
- `.replyOptions` (lines 149–156)
- `.replyOptions label` (lines 158–165)
- `.replyTextarea` (lines 167–181)
- `.replyTextarea:focus` (lines 183–186)
- `.replyActions` (lines 188–192)
- `.replySuccess` (lines 194–198)
- `.accountSelect` (lines 200–210)
- `.accountSelect:focus` (lines 212–215)

Keep `.replyHeader` (lines 137–140) — it's still used for the collapsible header toggle.

- [ ] **Step 6: Verify the build succeeds**

Run: `cd /home/ben/Development/ecip/emcip-admin-ui/src/main/frontend && npx vite build --logLevel error 2>&1 | cat`

Expected: Build succeeds with no errors.

- [ ] **Step 7: Commit**

```bash
cd /home/ben/Development/ecip
git add emcip-admin-ui/src/main/frontend/src/pages/Flags/Flags.jsx \
        emcip-admin-ui/src/main/frontend/src/pages/Flags/Flags.module.css
git commit -m "refactor(admin-ui): wire ReplyComposer, remove inline reply code

Replace reply state/handler/JSX in FlagDetailModal with
<ReplyComposer>. Remove unused reply CSS classes.
Part of #41b — reply composer v2."
```

---

### Task 5: Documentation — update architecture guide

**Files:**
- Modify: `documentation/architecture-guide.adoc`

**Context:** Document the new NOTE target in `FlagService.reply()` and the `OPERATOR_NOTE` audit event. The architecture guide already has sections for admin-api and audit events.

- [ ] **Step 1: Read the current architecture guide to find the right section**

Read `documentation/architecture-guide.adoc` and find the section that describes the reply/flag functionality or audit events.

- [ ] **Step 2: Add NOTE target documentation**

Find the paragraph that describes `FlagService` or the reply flow (near the `CostsProxyController` section added in #7) and add a paragraph documenting the NOTE target:

```asciidoc
==== Operator Notes (NOTE target)

`FlagService.reply()` supports a `NOTE` target for internal operator notes.
When `target` equals `"NOTE"`, the service skips TDLib send entirely and publishes an `OPERATOR_NOTE` audit event to the `audit.events` Kafka topic with the note text in the event details.
The response returns `messageId: 0`, `target: "NOTE"`, and `markedActioned: false`.
No Telegram account resolution is performed for notes.
```

- [ ] **Step 3: Commit**

```bash
cd /home/ben/Development/ecip
git add documentation/architecture-guide.adoc
git commit -m "docs: document NOTE target and OPERATOR_NOTE audit event

Add section describing FlagService NOTE code path: skip TDLib,
publish OPERATOR_NOTE to audit.events, no account resolution.
Part of #41b — reply composer v2."
```

---

## Self-Review Checklist

**Spec coverage:**
- [x] §1 Backend NOTE code path → Task 1
- [x] §2 ReplyComposer extraction → Tasks 3, 4
- [x] §3 4-mode SegmentedControl → Task 3
- [x] §4 Mode hints → Task 3
- [x] §5 Chip-row templates → Task 3
- [x] §6 Character counter and footer → Task 3
- [x] §7 Options and account selector → Task 3 (visibility rules per mode)
- [x] §8 CSS → Task 2
- [x] Affected files match spec table → All covered
- [x] Documentation update → Task 5

**Placeholder scan:** No TBD, TODO, or vague steps found.

**Type consistency:**
- `ReplyComposer` props: `{ flagId, api, onActioned }` — consistent across Tasks 3 and 4
- `api.reply(flagId, body)` — matches existing `flags.js` API module
- `FlagController.ReplyResponse(messageId, target, markedActioned)` — matches existing record
- Mode values: `GROUP`, `QUOTE`, `DM`, `NOTE` — consistent across all tasks
- `TEMPLATES` array: same 4 strings in Task 3 as spec §5
