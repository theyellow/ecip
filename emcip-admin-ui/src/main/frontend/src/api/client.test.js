import { makeRequest } from './client'

beforeEach(() => {
  global.fetch = vi.fn()
})

test('makeRequest adds Authorization header', async () => {
  fetch.mockResolvedValue({ ok: true, status: 200, json: async () => ({ ok: true }) })
  const request = makeRequest('test-token')
  await request('/api/groups')
  expect(fetch).toHaveBeenCalledWith(
    expect.stringContaining('/api/groups'),
    expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer test-token' }),
    })
  )
})

test('makeRequest throws on non-ok response', async () => {
  fetch.mockResolvedValue({ ok: false, status: 404, statusText: 'Not Found' })
  const request = makeRequest('tok')
  await expect(request('/api/missing')).rejects.toThrow('404')
})

test('makeRequest returns null for 204 No Content', async () => {
  fetch.mockResolvedValue({ ok: true, status: 204 })
  const request = makeRequest('tok')
  const result = await request('/api/groups/1', { method: 'DELETE' })
  expect(result).toBeNull()
})

test('makeRequest returns null for 202 Accepted with empty body', async () => {
  fetch.mockResolvedValue({
    ok: true,
    status: 202,
    headers: { get: () => '0' },
  })
  const request = makeRequest('tok')
  const result = await request('/api/telegram/accounts/1/code', { method: 'POST' })
  expect(result).toBeNull()
})
