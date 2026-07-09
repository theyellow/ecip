import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { JobDetailModal } from './JobDetailModal'

const mockDetail = {
  job: {
    jobId: '123',
    sourceType: 'URL',
    sourceRef: 'https://example.com/doc.pdf',
    tenantId: null,
    status: 'COMPLETED',
    chunkCount: 2,
    errorMessage: null,
    createdAt: '2026-07-09T10:00:00Z',
    contentHash: 'abc123',
  },
  chunks: [
    { id: 'c1', chunkIndex: 0, contentPreview: 'First chunk text...', entityCount: 2, relationshipCount: 1 },
    { id: 'c2', chunkIndex: 1, contentPreview: 'Second chunk text...', entityCount: 1, relationshipCount: 0 },
  ],
  entities: [
    { label: 'Angela Merkel', conceptType: 'PERSON', nodeId: 'n1' },
  ],
  totalChunks: 2,
  totalEntities: 1,
  totalRelationships: 1,
}

describe('JobDetailModal', () => {
  it('renders job info, chunks, and entities', async () => {
    const api = { jobDetails: vi.fn().mockResolvedValue(mockDetail) }
    render(
      <JobDetailModal
        api={api}
        jobId="123"
        tenants={[]}
        onClose={() => {}}
      />
    )

    await waitFor(() => {
      expect(screen.getByText('COMPLETED')).toBeInTheDocument()
    })
    expect(screen.getByText('abc123')).toBeInTheDocument()
    expect(screen.getByText('First chunk text...')).toBeInTheDocument()
    expect(screen.getByText('Second chunk text...')).toBeInTheDocument()
    expect(screen.getByText('Angela Merkel')).toBeInTheDocument()
  })

  it('shows error block for failed jobs', async () => {
    const failedDetail = {
      ...mockDetail,
      job: { ...mockDetail.job, status: 'FAILED', errorMessage: 'Something went wrong' },
    }
    const api = { jobDetails: vi.fn().mockResolvedValue(failedDetail) }
    render(
      <JobDetailModal api={api} jobId="123" tenants={[]} onClose={() => {}} />
    )

    await waitFor(() => {
      expect(screen.getByText('Something went wrong')).toBeInTheDocument()
    })
  })
})
