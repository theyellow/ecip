import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import ToastProvider from '../../components/Toast/ToastProvider'

let mockRole = 'TENANT_ADMIN'
vi.mock('../../auth/AuthContext', () => ({ useAuth: () => ({ role: mockRole }) }))

import { IngestionModal } from './IngestionModal'

const api = { warmUp: () => Promise.resolve({}) }
const tenants = [{ id: 't1', name: 'Tenant One' }]

function renderModal() {
  return render(
    <ToastProvider>
      <IngestionModal api={api} tenants={tenants} onClose={vi.fn()} onJobCreated={vi.fn()} />
    </ToastProvider>
  )
}

describe('IngestionModal tenant choice', () => {
  it('is not offered to tenant roles', () => {
    mockRole = 'TENANT_ADMIN'
    renderModal()
    expect(screen.queryByRole('option', { name: /Global/ })).not.toBeInTheDocument()
    expect(screen.getByText(/ingested into your tenant/i)).toBeInTheDocument()
  })
  it('is offered to ADMIN', () => {
    mockRole = 'ADMIN'
    renderModal()
    expect(screen.getByRole('option', { name: /Global/ })).toBeInTheDocument()
  })
})
