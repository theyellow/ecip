import { describe, it, expect, vi, beforeEach } from 'vitest'
import { telegramApi } from './telegram'

describe('telegramApi', () => {
  let request

  beforeEach(() => {
    request = vi.fn().mockResolvedValue({})
  })

  it('getConfig calls /api/telegram/config', async () => {
    await telegramApi(request).getConfig()
    expect(request).toHaveBeenCalledWith('/api/telegram/config')
  })

  it('saveConfig calls PUT /api/telegram/config', async () => {
    await telegramApi(request).saveConfig({ phoneNumber: '+1234' })
    expect(request).toHaveBeenCalledWith(
      '/api/telegram/config',
      expect.objectContaining({ method: 'PUT' })
    )
  })

  it('getStatus calls /api/telegram/status', async () => {
    await telegramApi(request).getStatus()
    expect(request).toHaveBeenCalledWith('/api/telegram/status')
  })

  it('reconnect calls POST /api/telegram/reconnect', async () => {
    await telegramApi(request).reconnect()
    expect(request).toHaveBeenCalledWith(
      '/api/telegram/reconnect',
      expect.objectContaining({ method: 'POST' })
    )
  })
})
