const API_BASE = import.meta.env.VITE_API_BASE ?? ''

async function doFetch(token, path, options) {
  return fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      ...options.headers,
    },
  })
}

function parseResponse(res) {
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
  if (res.status === 204 || res.headers?.get('content-length') === '0') return null
  return res.json()
}

export function makeRequest(token) {
  return async function request(path, options = {}) {
    const res = await doFetch(token, path, options)
    return parseResponse(res)
  }
}

/**
 * Like makeRequest, but on 401 calls onRefresh() to get a new token and retries once.
 * If the retry also fails, calls onLogout() and rethrows.
 */
export function makeRefreshableRequest(token, onRefresh, onLogout) {
  return async function request(path, options = {}) {
    const res = await doFetch(token, path, options)
    if (res.status !== 401) return parseResponse(res)

    try {
      const newToken = await onRefresh()
      const retryRes = await doFetch(newToken, path, options)
      return parseResponse(retryRes)
    } catch {
      onLogout()
      throw new Error('Session expired. Please log in again.')
    }
  }
}
