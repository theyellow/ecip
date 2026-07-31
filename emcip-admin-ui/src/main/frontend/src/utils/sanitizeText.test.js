import { sanitizeText } from './sanitizeText'

test('strips a script tag and drops its payload text', () => {
  expect(sanitizeText('<script>alert(1)</script>Hello')).toBe('Hello')
})

test('drops an img element and its event-handler attribute entirely', () => {
  expect(sanitizeText('<img src=x onerror=alert(1)>')).toBe('')
})

test('does NOT double-encode entity-like characters (regression guard)', () => {
  const input = 'A & B where x > y and q="z"'
  expect(sanitizeText(input)).toBe('A & B where x > y and q="z"')
})

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
