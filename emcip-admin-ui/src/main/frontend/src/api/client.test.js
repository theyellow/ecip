import { makeRequest, makeRefreshableRequest } from './client'

beforeEach(() => {
  global.fetch = vi.fn()
})

test('makeRequest adds Authorization header', async () => {
  fetch.mockResolvedValue({ ok: true, status: 200, json: async () => ({ ok: true }) })
  const request = makeRequest('test-token', null, null)
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
  const request = makeRequest('tok', null, null)
  await expect(request('/api/missing')).rejects.toThrow('404')
})

test('makeRequest returns null for 204 No Content', async () => {
  fetch.mockResolvedValue({ ok: true, status: 204 })
  const request = makeRequest('tok', null, null)
  const result = await request('/api/groups/1', { method: 'DELETE' })
  expect(result).toBeNull()
})

test('makeRequest returns null for 202 Accepted with empty body', async () => {
  fetch.mockResolvedValue({
    ok: true,
    status: 202,
    headers: { get: () => '0' },
  })
  const request = makeRequest('tok', null, null)
  const result = await request('/api/telegram/accounts/1/code', { method: 'POST' })
  expect(result).toBeNull()
})

test('makeRefreshableRequest retries with new token on 401', async () => {
  const onRefresh = vi.fn().mockResolvedValue('new-token')
  const onLogout = vi.fn()
  fetch
    .mockResolvedValueOnce({ ok: false, status: 401, statusText: 'Unauthorized' })
    .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ ok: true }) })

  const request = makeRefreshableRequest('old-token', null, null, onRefresh, onLogout)
  const result = await request('/api/groups')

  expect(onRefresh).toHaveBeenCalledOnce()
  expect(onLogout).not.toHaveBeenCalled()
  expect(result).toEqual({ ok: true })
})

test('makeRefreshableRequest calls onLogout when refresh fails', async () => {
  const onRefresh = vi.fn().mockRejectedValue(new Error('Refresh failed'))
  const onLogout = vi.fn()
  fetch.mockResolvedValue({ ok: false, status: 401, statusText: 'Unauthorized' })

  const request = makeRefreshableRequest('old-token', null, null, onRefresh, onLogout)
  await expect(request('/api/groups')).rejects.toThrow('Session expired')
  expect(onLogout).toHaveBeenCalledOnce()
})

test('makeRequest sends X-Tenant-Id header for ADMIN with active tenant', async () => {
  fetch.mockResolvedValue({ ok: true, status: 200, json: async () => ({ ok: true }) })
  const request = makeRequest('tok', 'ADMIN', { id: 'tenant-123' })
  await request('/api/groups')
  expect(fetch).toHaveBeenCalledWith(
    expect.stringContaining('/api/groups'),
    expect.objectContaining({
      headers: expect.objectContaining({ 'X-Tenant-Id': 'tenant-123' }),
    })
  )
})

test('makeRequest does NOT send X-Tenant-Id for TENANT_ADMIN', async () => {
  fetch.mockResolvedValue({ ok: true, status: 200, json: async () => ({ ok: true }) })
  const request = makeRequest('tok', 'TENANT_ADMIN', { id: 'tenant-123' })
  await request('/api/groups')
  expect(fetch).toHaveBeenCalledWith(
    expect.stringContaining('/api/groups'),
    expect.objectContaining({
      headers: expect.not.objectContaining({ 'X-Tenant-Id': expect.anything() }),
    })
  )
})

test('makeRequest does NOT send X-Tenant-Id for ADMIN with no active tenant', async () => {
  fetch.mockResolvedValue({ ok: true, status: 200, json: async () => ({ ok: true }) })
  const request = makeRequest('tok', 'ADMIN', null)
  await request('/api/groups')
  expect(fetch).toHaveBeenCalledWith(
    expect.stringContaining('/api/groups'),
    expect.objectContaining({
      headers: expect.not.objectContaining({ 'X-Tenant-Id': expect.anything() }),
    })
  )
})
