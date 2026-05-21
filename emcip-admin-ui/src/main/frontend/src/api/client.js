const API_BASE = import.meta.env.VITE_API_BASE ?? ''

function tenantHeader(role, currentTenant) {
  // Only ADMIN sends X-Tenant-Id (when a specific tenant is selected).
  // TENANT_ADMIN: tenantId is in JWT; backend reads it from there.
  if (role === 'ADMIN' && currentTenant?.id) {
    return { 'X-Tenant-Id': currentTenant.id }
  }
  return {}
}

async function doFetch(token, role, currentTenant, path, options) {
  return fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      ...tenantHeader(role, currentTenant),
      ...options.headers,
    },
  })
}

function parseResponse(res) {
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
  if (res.status === 204 || res.headers?.get('content-length') === '0') return null
  return res.json()
}

export function makeRequest(token, role, currentTenant) {
  return async function request(path, options = {}) {
    const res = await doFetch(token, role, currentTenant, path, options)
    return parseResponse(res)
  }
}

/**
 * Like makeRequest, but on 401 calls onRefresh() to get a new token and retries once.
 * If the retry also fails, calls onLogout() and rethrows.
 */
export function makeRefreshableRequest(token, role, currentTenant, onRefresh, onLogout) {
  return async function request(path, options = {}) {
    const res = await doFetch(token, role, currentTenant, path, options)
    if (res.status !== 401) return parseResponse(res)

    try {
      const newToken = await onRefresh()
      const retryRes = await doFetch(newToken, role, currentTenant, path, options)
      return parseResponse(retryRes)
    } catch {
      onLogout()
      throw new Error('Session expired. Please log in again.')
    }
  }
}
