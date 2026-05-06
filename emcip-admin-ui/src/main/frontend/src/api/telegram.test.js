import { describe, it, expect, vi, beforeEach } from 'vitest'
import { telegramApi } from './telegram'

describe('telegramApi', () => {
  let request

  beforeEach(() => {
    request = vi.fn().mockResolvedValue({})
  })

  it('listAccounts calls /api/telegram/accounts', async () => {
    await telegramApi(request).listAccounts()
    expect(request).toHaveBeenCalledWith('/api/telegram/accounts')
  })

  it('getStatus calls /api/telegram/accounts/:id/status', async () => {
    await telegramApi(request).getStatus('abc')
    expect(request).toHaveBeenCalledWith('/api/telegram/accounts/abc/status')
  })

  it('reconnect calls POST /api/telegram/accounts/:id/reconnect', async () => {
    await telegramApi(request).reconnect('abc')
    expect(request).toHaveBeenCalledWith(
      '/api/telegram/accounts/abc/reconnect',
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('discoverChats calls /api/telegram/accounts/:id/chats', async () => {
    await telegramApi(request).discoverChats('abc')
    expect(request).toHaveBeenCalledWith('/api/telegram/accounts/abc/chats')
  })

  it('listWatched calls /api/telegram/accounts/:id/watched', async () => {
    await telegramApi(request).listWatched('abc')
    expect(request).toHaveBeenCalledWith('/api/telegram/accounts/abc/watched')
  })

  it('watchGroup calls POST /api/telegram/accounts/:id/watch', async () => {
    await telegramApi(request).watchGroup('abc', { chatId: 111, title: 'G' })
    expect(request).toHaveBeenCalledWith(
      '/api/telegram/accounts/abc/watch',
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('unwatchGroup calls DELETE /api/telegram/accounts/:id/watch/:chatId', async () => {
    await telegramApi(request).unwatchGroup('abc', 111)
    expect(request).toHaveBeenCalledWith(
      '/api/telegram/accounts/abc/watch/111',
      expect.objectContaining({ method: 'DELETE' }),
    )
  })
})
