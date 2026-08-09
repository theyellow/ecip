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

async function parseResponse(res) {
  if (!res.ok) {
    const err = new Error(`${res.status} ${res.statusText}`)
    err.status = res.status
    try {
      err.body = await res.json()
    } catch {
      /* no JSON body */
    }
    // Prefer the server's ProblemDetail `detail` over "409 Conflict", which tells an operator
    // nothing. Pages render e.message directly, so without this the actionable sentence the API
    // took care to produce never reaches the screen.
    //
    // Safe to surface because admin-api does not put internal detail in this field: unexpected
    // exceptions become "An unexpected error occurred" in GlobalExceptionHandler. Anything that
    // does reach `detail` was written to be read by an operator.
    if (typeof err.body?.detail === 'string' && err.body.detail.trim()) {
      err.message = err.body.detail
    }
    // Stable classification (e.g. SECRET_NOT_ENCRYPTED) for callers that need to branch on the
    // kind of failure rather than match on wording.
    if (err.body?.code) {
      err.code = err.body.code
    }
    throw err
  }
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
