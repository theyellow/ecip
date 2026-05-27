export function flagsApi(request) {
  return {
    list: (page = 0, size = 50, decision = '') => {
      const params = new URLSearchParams({ page, size })
      if (decision) params.set('decision', decision)
      return request(`/api/flags?${params}`)
    },
    updateStatus: (id, status) =>
      request(`/api/flags/${encodeURIComponent(id)}/status`, {
        method: 'PATCH',
        body: JSON.stringify({ status }),
      }),
    reply: (id, body) =>
      request(`/api/flags/${encodeURIComponent(id)}/reply`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
  }
}
