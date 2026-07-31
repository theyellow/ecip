import { sanitizeText, sanitizeHtml } from './sanitizeText'

// --- sanitizeText: Unicode hygiene for React text-node sinks ---

test('removes bidi override characters (Trojan-Source)', () => {
  expect(sanitizeText('user\u202Eevil')).toBe('userevil')
})

test('removes zero-width and BOM characters', () => {
  expect(sanitizeText('a\u200Bb\uFEFFc')).toBe('abc')
})

test('removes C0/C1 control chars but preserves tab and newline', () => {
  expect(sanitizeText('a\u0000b')).toBe('ab')
  expect(sanitizeText('line1\nline2\tend')).toBe('line1\nline2\tend')
})

test('preserves benign angle-bracket content (generics, templates, autolinks)', () => {
  expect(sanitizeText('List<String> and Map<K,V>')).toBe('List<String> and Map<K,V>')
  expect(sanitizeText('vector<int> v;')).toBe('vector<int> v;')
  expect(sanitizeText('see <https://example.com> for more')).toBe('see <https://example.com> for more')
})

test('leaves HTML tags as literal text (React escapes them at the text-node sink)', () => {
  expect(sanitizeText('<script>alert(1)</script>Hello')).toBe('<script>alert(1)</script>Hello')
})

test('does not alter entity-like characters', () => {
  const input = 'A & B where x > y and q="z"'
  expect(sanitizeText(input)).toBe(input)
})

test('leaves plain multi-line markdown text unchanged', () => {
  const md = '# Title\n\n- one\n- two\n\nBody & more.'
  expect(sanitizeText(md)).toBe(md)
})

test('returns empty string for non-string input', () => {
  expect(sanitizeText(null)).toBe('')
  expect(sanitizeText(undefined)).toBe('')
  expect(sanitizeText(42)).toBe('')
  expect(sanitizeText({})).toBe('')
})

// --- sanitizeHtml: reserved DOMPurify helper for a future HTML sink ---

test('sanitizeHtml strips script tags and drops their payload', () => {
  expect(sanitizeHtml('<script>alert(1)</script>Hello')).toBe('Hello')
})

test('sanitizeHtml strips event-handler attributes', () => {
  expect(sanitizeHtml('<img src=x onerror=alert(1)>')).not.toContain('onerror')
})

test('sanitizeHtml keeps safe formatting tags', () => {
  expect(sanitizeHtml('<b>hi</b>')).toBe('<b>hi</b>')
})

test('sanitizeHtml also strips hostile Unicode', () => {
  expect(sanitizeHtml('a\u200Bb\u202Ec')).toBe('abc')
})

test('sanitizeHtml returns empty string for non-string input', () => {
  expect(sanitizeHtml(null)).toBe('')
})
