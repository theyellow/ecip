import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'

let mockRole = 'MODERATOR'
vi.mock('../../auth/AuthContext', () => ({
  useAuth: () => ({ role: mockRole }),
  useAuthRequest: () => vi.fn(),
}))
vi.mock('../../api/resolutionReview', () => ({
  resolutionReviewApi: () => ({
    list: () =>
      Promise.resolve({
        content: [
          {
            id: 'f1',
            candidateLabel: 'c',
            similarLabel: 's',
            conceptType: 'Topic',
            similarityScore: 0.9,
            status: 'PENDING',
            tenantId: null,
          },
        ],
        totalElements: 1,
      }),
    merge: vi.fn(),
    dismiss: vi.fn(),
  }),
}))

import { ResolutionQueue } from './ResolutionQueue'

describe('ResolutionQueue on a global flag', () => {
  it('offers no merge/dismiss to a tenant role', async () => {
    mockRole = 'MODERATOR'
    render(<ResolutionQueue />)
    await waitFor(() =>
      expect(screen.getByTitle(/managed by a platform admin/i)).toBeInTheDocument()
    )
    expect(screen.queryByRole('button', { name: 'Merge' })).not.toBeInTheDocument()
  })
  it('offers merge/dismiss to ADMIN', async () => {
    mockRole = 'ADMIN'
    render(<ResolutionQueue />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Merge' })).toBeInTheDocument())
  })
})
