import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { AuthProvider } from '../../auth/AuthContext'
import { ThemeProvider } from '../../theme/ThemeContext'
import { Groups } from './Groups'

const mockGroups = [
  { telegramChatId: -1001234567890, name: 'Test Group', moderationLevel: 'MEDIUM',
    autoRespond: true, description: 'A test group' },
]

beforeEach(() => {
  global.fetch = vi.fn().mockResolvedValue({
    ok: true, status: 200,
    json: async () => mockGroups,
  })
})

const wrap = ui => render(
  <MemoryRouter><ThemeProvider><AuthProvider>{ui}</AuthProvider></ThemeProvider></MemoryRouter>
)

test('renders groups table with name and chatId', async () => {
  wrap(<Groups />)
  await waitFor(() => expect(screen.getByText('Test Group')).toBeInTheDocument())
  expect(screen.getByText('-1001234567890')).toBeInTheDocument()
})
