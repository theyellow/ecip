import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { DataTable } from './DataTable'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'code', label: 'Code', mono: true },
]

const ROWS = [
  { id: '1', name: 'Alpha', code: 'A1' },
  { id: '2', name: 'Beta', code: 'B2' },
]

describe('DataTable', () => {
  it('renders title and system ID', () => {
    render(<DataTable title="Items" systemId="test · 2 items" columns={COLUMNS} rows={ROWS} />)
    expect(screen.getByText('Items')).toBeInTheDocument()
    expect(screen.getByText('test · 2 items')).toBeInTheDocument()
  })

  it('renders column headers and row data', () => {
    render(<DataTable title="Items" columns={COLUMNS} rows={ROWS} />)
    expect(screen.getByText('Name')).toBeInTheDocument()
    expect(screen.getByText('Code')).toBeInTheDocument()
    expect(screen.getByText('Alpha')).toBeInTheDocument()
    expect(screen.getByText('B2')).toBeInTheDocument()
  })

  it('shows empty state when no rows', () => {
    render(<DataTable title="Items" columns={COLUMNS} rows={[]} emptyText="Nothing here" />)
    expect(screen.getByText('Nothing here')).toBeInTheDocument()
  })

  it('calls onEdit when row is clicked', async () => {
    const onEdit = vi.fn()
    render(<DataTable title="Items" columns={COLUMNS} rows={ROWS} onEdit={onEdit} />)
    await userEvent.click(screen.getByText('Alpha'))
    expect(onEdit).toHaveBeenCalledWith(ROWS[0])
  })

  it('calls onDelete when delete button is clicked and confirmed', async () => {
    const onDelete = vi.fn()
    render(<DataTable title="Items" columns={COLUMNS} rows={ROWS} onDelete={onDelete} />)
    const deleteButtons = screen.getAllByRole('button', { name: /delete/i })
    await userEvent.click(deleteButtons[0])
    // ConfirmDialog appears — click the danger Delete button inside it
    const confirmBtn = screen.getAllByRole('button', { name: /delete/i }).at(-1)
    await userEvent.click(confirmBtn)
    expect(onDelete).toHaveBeenCalledWith(ROWS[0])
  })

  it('renders add button when addLabel and onAdd provided', async () => {
    const onAdd = vi.fn()
    render(<DataTable title="Items" columns={COLUMNS} rows={ROWS} addLabel="+ Add" onAdd={onAdd} />)
    await userEvent.click(screen.getByText('+ Add'))
    expect(onAdd).toHaveBeenCalled()
  })

  it('renders custom cell via column render function', () => {
    const columns = [
      { key: 'name', label: 'Name', render: (val) => `[${val}]` },
    ]
    render(<DataTable title="Items" columns={columns} rows={[{ id: '1', name: 'Test' }]} />)
    expect(screen.getByText('[Test]')).toBeInTheDocument()
  })

  it('renders filter dropdowns', async () => {
    const onChange = vi.fn()
    const filters = [{
      value: '',
      onChange,
      options: [{ value: '', label: 'All' }, { value: 'A', label: 'Type A' }],
    }]
    render(<DataTable title="Items" columns={COLUMNS} rows={ROWS} filters={filters} />)
    expect(screen.getByText('All')).toBeInTheDocument()
    expect(screen.getByText('Type A')).toBeInTheDocument()
  })

  it('renders emptyText without uppercase transform (body font class)', () => {
    const { container } = render(
      <DataTable
        columns={[{ key: 'name', label: 'Name' }]}
        rows={[]}
        emptyText="No rules defined. Create a rule to get started."
      />
    )
    const emptyTd = container.querySelector('td')
    expect(emptyTd?.textContent).toBe('No rules defined. Create a rule to get started.')
    // The cell must NOT carry the display-font / uppercase class
    // In jsdom computed styles don't resolve, so assert class names instead
    expect(emptyTd?.className).not.toMatch(/display/i)
  })

  describe('DataTable — sticky actions', () => {
    it('action cell has actionsSticky class when onDelete is provided', () => {
      const cols = [{ key: 'name', label: 'Name' }]
      const rows = [{ id: '1', name: 'Row 1' }]
      const { container } = render(
        <DataTable
          columns={cols}
          rows={rows}
          onDelete={vi.fn()}
          deleteMessage={() => 'Delete?'}
        />
      )
      const allTds = container.querySelectorAll('td')
      const actionTd = allTds[allTds.length - 1]
      expect(actionTd.className).toContain('actionsSticky')
    })

    it('action column header also has actionsSticky class', () => {
      const cols = [{ key: 'name', label: 'Name' }]
      const rows = [{ id: '1', name: 'Row 1' }]
      const { container } = render(
        <DataTable columns={cols} rows={rows} onDelete={vi.fn()} deleteMessage={() => ''} />
      )
      const allThs = container.querySelectorAll('th')
      const actionTh = allThs[allThs.length - 1]
      expect(actionTh.className).toContain('actionsSticky')
    })
  })
})
