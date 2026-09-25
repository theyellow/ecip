import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { JobActions } from './JobActions'

const row = { jobId: 'j1', rawStatus: 'COMPLETED', rawTenantId: null }

describe('JobActions', () => {
  it('offers delete and re-ingest when the item can be changed', () => {
    render(<JobActions row={row} canChange onDelete={vi.fn()} onReingest={vi.fn()} />)
    expect(screen.getByTitle('Delete')).toBeInTheDocument()
    expect(screen.getByTitle('Re-ingest')).toBeInTheDocument()
  })
  it('offers no change and explains why on a global item', () => {
    render(<JobActions row={row} canChange={false} onDelete={vi.fn()} onReingest={vi.fn()} />)
    expect(screen.queryByTitle('Delete')).not.toBeInTheDocument()
    expect(screen.queryByTitle('Re-ingest')).not.toBeInTheDocument()
    expect(screen.getByTitle(/managed by a platform admin/i)).toBeInTheDocument()
  })
})
