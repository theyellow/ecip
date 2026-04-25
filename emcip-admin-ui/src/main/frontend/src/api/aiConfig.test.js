import { describe, it, expect, vi, beforeEach } from 'vitest'
import { aiConfigApi } from './aiConfig'

describe('aiConfigApi', () => {
  let request

  beforeEach(() => {
    request = vi.fn().mockResolvedValue({})
  })

  it('listModels calls /api/ai/models', async () => {
    await aiConfigApi(request).listModels()
    expect(request).toHaveBeenCalledWith('/api/ai/models')
  })

  it('createModel calls POST /api/ai/models', async () => {
    await aiConfigApi(request).createModel({ modelKey: 'gpt-4o' })
    expect(request).toHaveBeenCalledWith(
      '/api/ai/models',
      expect.objectContaining({ method: 'POST' })
    )
  })

  it('updateModel calls PUT /api/ai/models/:id', async () => {
    await aiConfigApi(request).updateModel('abc-123', { modelKey: 'gpt-4o' })
    expect(request).toHaveBeenCalledWith(
      '/api/ai/models/abc-123',
      expect.objectContaining({ method: 'PUT' })
    )
  })

  it('deleteModel calls DELETE /api/ai/models/:id', async () => {
    await aiConfigApi(request).deleteModel('abc-123')
    expect(request).toHaveBeenCalledWith(
      '/api/ai/models/abc-123',
      expect.objectContaining({ method: 'DELETE' })
    )
  })

  it('listTemplates calls /api/ai/templates', async () => {
    await aiConfigApi(request).listTemplates()
    expect(request).toHaveBeenCalledWith('/api/ai/templates')
  })

  it('deleteTemplate calls DELETE /api/ai/templates/:id', async () => {
    await aiConfigApi(request).deleteTemplate('tmpl-1')
    expect(request).toHaveBeenCalledWith(
      '/api/ai/templates/tmpl-1',
      expect.objectContaining({ method: 'DELETE' })
    )
  })
})
