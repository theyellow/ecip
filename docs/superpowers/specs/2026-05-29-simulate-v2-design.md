# Simulate Page v2 Redesign — Design Spec

**Goal:** Restyle the Simulate page with v2 design tokens. No structural changes.

**Approach:** Replace v1 tokens in CSS, add v2 page header with system-id, restyle error/success with v2 signal tokens.

---

## 1. Page Header

Add v2 page header with system-id:

`▶ intent-classifier · trace mode`

Replace the hand-built `<h2>` with standard page header pattern.

---

## 2. Form Card

Keep structure. Restyle with v2 tokens:
- Card: `var(--bg-card)`, 1px `var(--border)`, `border-radius: 0`
- Fields: v2 `.field`/`.input` pattern (10px uppercase tracked labels, mono input, gold focus glow)
- Textarea: same `.input` class, resize vertical
- Select: same `.input` class

---

## 3. Error/Success States

- Error: v2 inline styles with `role="alert"`, signal-stop tokens
- Success: `var(--signal-ok-fg)` text
- Result JSON: `var(--code-bg)` background, `var(--font-mono)`

---

## 4. Pipeline Info

Keep the `<ol>` pipeline description. Restyle:
- Panel: `var(--bg-card)`, 1px `var(--border)`, `border-radius: 0`
- Heading: use SectionLabel component
- Code elements: `var(--font-mono)`, `var(--code-bg)`

---

## 5. Testing

2 existing tests. The test pattern uses `AuthProvider`/`ThemeProvider` wrappers and `global.fetch` mock — different from other pages. Tests should still pass since we're not changing the form structure or API call.

---

## 6. Excluded

- Design handoff's two-column layout with animated pipeline stages — that's a future feature, not a restyle
- No backend changes
