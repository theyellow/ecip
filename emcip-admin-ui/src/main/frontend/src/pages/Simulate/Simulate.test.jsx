import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AuthProvider } from '../../auth/AuthContext'
import { ThemeProvider } from '../../theme/ThemeContext'
import { Simulate } from './Simulate'
import { PipelineTrace } from './PipelineTrace'

beforeEach(() => {
  global.fetch = vi.fn()
})

const wrap = ui => render(<ThemeProvider><AuthProvider>{ui}</AuthProvider></ThemeProvider>)

const FULL_TRACE = {
  eventId: 'abc-123',
  partial: false,
  stages: [
    { stage: 'PUBLISH',    data: { topic: 'telegram.raw.messages', eventId: 'abc-123' } },
    { stage: 'CLASSIFIER', data: { intent: 'SPAM', confidence: 0.95, matchedRules: ['SPAM'] } },
    { stage: 'POLICY',     data: { policyId: 'spam-policy', decision: 'BLOCK', actions: ['BLOCK'], reason: 'keyword match' } },
    { stage: 'MODERATION', data: { flagType: 'SPAM', severity: 'HIGH', reason: 'blocked' } },
  ],
}

test('publishes via POST to /api/simulate/message', async () => {
  fetch.mockResolvedValueOnce({
    ok: true,
    status: 202,
    json: async () => FULL_TRACE,
  })

  wrap(<Simulate />)

  await userEvent.type(screen.getByLabelText(/chat id/i), '-1001234567890')
  await userEvent.type(screen.getByLabelText(/message text/i), 'Hello world')
  await userEvent.click(screen.getByRole('button', { name: /publish/i }))

  await waitFor(() => {
    const call = fetch.mock.calls.find(c => c[0].includes('/api/simulate/message'))
    expect(call).toBeDefined()
    expect(call[1].method).toBe('POST')
    expect(call[0]).toMatch(/\/api\/simulate\/message$/)
  })
})

test('shows error when fields are empty', async () => {
  wrap(<Simulate />)
  await userEvent.click(screen.getByRole('button', { name: /publish/i }))
  await waitFor(() =>
    expect(screen.getByRole('alert')).toHaveTextContent(/required/i)
  )
})

test('shows pipeline trace stages after successful publish', async () => {
  fetch.mockResolvedValueOnce({
    ok: true,
    status: 202,
    json: async () => FULL_TRACE,
  })

  wrap(<Simulate />)

  await userEvent.type(screen.getByLabelText(/chat id/i), '12345')
  await userEvent.type(screen.getByLabelText(/message text/i), 'test spam message')
  await userEvent.click(screen.getByRole('button', { name: /publish/i }))

  await waitFor(() => {
    expect(screen.getByText(/INTENT CLASSIFIER/i)).toBeInTheDocument()
    expect(screen.getAllByText(/SPAM/).length).toBeGreaterThan(0)
  })
})

test('pipeline trace panel is always visible', () => {
  wrap(<Simulate />)
  expect(screen.getByText(/PIPELINE TRACE/i)).toBeInTheDocument()
  expect(screen.getByText(/INTENT CLASSIFIER/i)).toBeInTheDocument()
})

test('PipelineTrace panel contains a legend', () => {
  const { container } = render(<ThemeProvider><AuthProvider><PipelineTrace result={null} loading={false} /></AuthProvider></ThemeProvider>)
  expect(container.textContent).toMatch(/waiting/i)
})
