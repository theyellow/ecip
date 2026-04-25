const API_BASE = import.meta.env.VITE_API_BASE ?? ''

export function makeRequest(token) {
  return async function request(path, options = {}) {
    const res = await fetch(`${API_BASE}${path}`, {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
        ...options.headers,
      },
    })
    if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
    if (res.status === 204) return null
    return res.json()
  }
}
