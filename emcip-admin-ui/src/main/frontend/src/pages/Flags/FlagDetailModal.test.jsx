import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { FlagDetailModal } from './Flags'

const HOSTILE = 'Looks fine <script>x()</script> A & B > C\u202Eevil\u200B'

const flag = {
  id: 'flag-1',
  metadata: '{}',
  originalIntent: 'SPAM',
  decision: 'BLOCK',
  confidence: 0.9,
  reason: 'keyword',
  signalStatus: 'NEW',
}

function renderModal(api) {
  return render(
    <FlagDetailModal flag={flag} onClose={() => {}} onStatusChange={() => {}} api={api} />
  )
}

// NOTE: Modal renders through createPortal to document.body, so assert against
// document.body — NOT the render() container (which stays empty for a portal).
test('assistant chat bubble renders sanitized text (no script/bidi/zero-width, no double-encode)', async () => {
  const api = { chat: vi.fn().mockResolvedValue({ content: HOSTILE, model: 'gpt-x' }) }
  renderModal(api)

  // The Analyse button lives inside the collapsible "AI Research" section
  // (showResearch defaults to false) — expand it first. The header is a div
  // with an onClick, so click its text label.
  await userEvent.click(screen.getByText('AI Research', { exact: false }))
  await userEvent.click(screen.getByRole('button', { name: /analyse/i }))

  await waitFor(() => expect(api.chat).toHaveBeenCalled())
  await waitFor(() => {
    expect(document.body.textContent).toContain('A & B > C')
  })
  expect(document.body.querySelector('script')).toBeNull()
  expect(document.body.textContent).not.toContain('&amp;')
  expect(document.body.textContent).not.toContain('\u202E')
  expect(document.body.textContent).not.toContain('\u200B')
})

test('Copy button writes sanitized text to the clipboard', async () => {
  const writeText = vi.fn()
  Object.defineProperty(navigator, 'clipboard', {
    value: { writeText }, configurable: true,
  })
  const api = { chat: vi.fn().mockResolvedValue({ content: HOSTILE, model: 'gpt-x' }) }
  renderModal(api)

  // The Analyse button lives inside the collapsible "AI Research" section
  // (showResearch defaults to false) — expand it first. The header is a div
  // with an onClick, so click its text label.
  await userEvent.click(screen.getByText('AI Research', { exact: false }))
  await userEvent.click(screen.getByRole('button', { name: /analyse/i }))
  await waitFor(() => expect(screen.getByRole('button', { name: /^copy$/i })).toBeInTheDocument())
  await userEvent.click(screen.getByRole('button', { name: /^copy$/i }))

  expect(writeText).toHaveBeenCalledTimes(1)
  const arg = writeText.mock.calls[0][0]
  expect(arg).toContain('A & B > C')
  expect(arg).toContain('<script>x()</script>')
  expect(arg).not.toContain('\u202E')
  expect(arg).not.toContain('\u200B')
})
