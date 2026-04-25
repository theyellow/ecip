import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { AIConfig } from './AIConfig'

vi.mock('../../auth/AuthContext', () => ({
  useAuth: () => ({ token: 'test-token' }),
}))

const mockModels = [
  { id: 'uuid-1', modelKey: 'gpt-4o', provider: 'openai', modelName: 'GPT-4o', taskType: 'GENERAL', active: true, priority: 10 },
]
const mockTemplates = [
  { id: 'tmpl-1', name: 'moderation-v1', version: '1.0', modelProvider: 'openai', modelName: 'GPT-4o', systemPrompt: 'You are a moderator', active: true },
]

vi.mock('../../api/aiConfig', () => ({
  aiConfigApi: () => ({
    listModels: vi.fn().mockResolvedValue(mockModels),
    listTemplates: vi.fn().mockResolvedValue(mockTemplates),
    createModel: vi.fn().mockResolvedValue({}),
    updateModel: vi.fn().mockResolvedValue({}),
    deleteModel: vi.fn().mockResolvedValue(null),
    createTemplate: vi.fn().mockResolvedValue({}),
    updateTemplate: vi.fn().mockResolvedValue({}),
    deleteTemplate: vi.fn().mockResolvedValue(null),
  }),
}))

vi.mock('../../api/client', () => ({ makeRequest: () => vi.fn() }))

describe('AIConfig page', () => {
  it('renders Models section heading', async () => {
    render(<AIConfig />)
    await waitFor(() => {
      expect(screen.getByText('AI Models')).toBeInTheDocument()
    })
  })

  it('lists model keys from API', async () => {
    render(<AIConfig />)
    await waitFor(() => {
      expect(screen.getByText('gpt-4o')).toBeInTheDocument()
    })
  })

  it('renders Templates section heading', async () => {
    render(<AIConfig />)
    await waitFor(() => {
      expect(screen.getByText('Prompt Templates')).toBeInTheDocument()
    })
  })

  it('lists template names from API', async () => {
    render(<AIConfig />)
    await waitFor(() => {
      expect(screen.getByText('moderation-v1')).toBeInTheDocument()
    })
  })
})
