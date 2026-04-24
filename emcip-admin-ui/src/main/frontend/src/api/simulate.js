// Bug A1 fix: API_BASE is applied by makeRequest — no hardcoded relative URL.
export function simulateApi(request) {
  return {
    publish: body =>
      request('/api/simulate/message', { method: 'POST', body: JSON.stringify(body) }),
  }
}
