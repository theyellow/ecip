import DOMPurify from 'dompurify'

// C0 (minus \t \n \r) + C1 control chars.
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]/g
// Zero-width space/non-joiner/joiner, word-joiner, BOM.
const ZERO_WIDTH = /[\u200B-\u200D\u2060\uFEFF]/g
// Bidi embeddings/overrides + isolates (Trojan-Source).
const BIDI = /[\u202A-\u202E\u2066-\u2069]/g

function unicodeHygiene(text) {
  return text.replace(CONTROL, '').replace(ZERO_WIDTH, '').replace(BIDI, '')
}

/**
 * Sanitize LLM-generated text for a React TEXT-NODE sink (render, .md download,
 * clipboard). These are NOT HTML sinks — React escapes text nodes — so the only
 * residual risk React does not cover is hostile Unicode (bidi/zero-width/control
 * chars), which this strips. It deliberately does NOT strip HTML tags: doing so
 * would delete benign angle-bracket content (e.g. `List<String>`,
 * `<https://autolinks>`) from research reports. Returns '' for non-strings.
 */
export function sanitizeText(raw) {
  if (typeof raw !== 'string') return ''
  return unicodeHygiene(raw)
}

/**
 * Sanitize untrusted content destined for an actual HTML sink
 * (dangerouslySetInnerHTML / innerHTML). RESERVED: no such sink exists today —
 * every LLM sink renders through React text nodes and must use sanitizeText().
 * If one is ever introduced, route its content through this: it applies Unicode
 * hygiene, then DOMPurify to strip scripts / event handlers / dangerous markup
 * while keeping safe formatting. Returns '' for non-strings.
 */
export function sanitizeHtml(raw) {
  if (typeof raw !== 'string') return ''
  return DOMPurify.sanitize(unicodeHygiene(raw))
}
