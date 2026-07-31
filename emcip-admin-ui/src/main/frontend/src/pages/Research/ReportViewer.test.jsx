import { render, screen } from '@testing-library/react'
import { ReportViewer } from './ReportViewer'

const HOSTILE = 'Safe <script>bad()</script> A & B where x > y\u202Eevil\u200B'

const report = {
  title: 'Test Report',
  template: 'default',
  version: 1,
  createdAt: null,
  sessionId: 's1',
  content: `## Heading\n${HOSTILE}`,
}

test('renders report content without a script element and without double-encoding', () => {
  const { container } = render(<ReportViewer report={report} />)
  expect(container.querySelector('script')).toBeNull()
  expect(screen.getByText('Heading')).toBeInTheDocument()
  // & / > survive literally (no &amp; / &gt;); bidi + zero-width are gone.
  const text = container.textContent
  expect(text).toContain('A & B where x > y')
  expect(text).not.toContain('&amp;')
  expect(text).not.toContain('\u202E')
  expect(text).not.toContain('\u200B')
})

test('download blob contains sanitized content', async () => {
  const created = []
  const origCreate = URL.createObjectURL
  const origRevoke = URL.revokeObjectURL
  URL.createObjectURL = blob => { created.push(blob); return 'blob:mock' }
  URL.revokeObjectURL = () => {}
  try {
    render(<ReportViewer report={report} />)
    await screen.getByRole('button', { name: /download/i }).click()
    expect(created).toHaveLength(1)
    const text = await created[0].text()
    expect(text).not.toContain('<script')
    expect(text).not.toContain('\u202E')
    expect(text).toContain('A & B where x > y')
  } finally {
    URL.createObjectURL = origCreate
    URL.revokeObjectURL = origRevoke
  }
})
