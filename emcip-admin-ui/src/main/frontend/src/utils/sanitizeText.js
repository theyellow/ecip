import DOMPurify from 'dompurify'

// C0 (minus \t \n \r) + C1 control chars.
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]/g
// Zero-width space/non-joiner/joiner, word-joiner, BOM.
const ZERO_WIDTH = /[\u200B-\u200D\u2060\uFEFF]/g
// Bidi embeddings/overrides + isolates (Trojan-Source).
const BIDI = /[\u202A-\u202E\u2066-\u2069]/g

/**
 * Sanitize LLM-generated text for display, download, or clipboard.
 *
 * There is no HTML sink in this app (content is rendered via React text nodes),
 * so the DOMPurify pass is defense-in-depth forward-cover for any future HTML
 * sink. The active mitigation is the Unicode hygiene pass, which removes
 * bidi/zero-width/control characters that React escaping does NOT neutralize.
 *
 * DOMPurify is invoked with RETURN_DOM + textContent (not the default string
 * return) so its HTML-encoded output is not double-escaped by React.
 */
export function sanitizeText(raw) {
  if (typeof raw !== 'string') return ''
  const stripped =
    DOMPurify.sanitize(raw, {
      ALLOWED_TAGS: [],
      ALLOWED_ATTR: [],
      KEEP_CONTENT: true,
      RETURN_DOM: true,
    }).textContent ?? ''
  return stripped.replace(CONTROL, '').replace(ZERO_WIDTH, '').replace(BIDI, '')
}
