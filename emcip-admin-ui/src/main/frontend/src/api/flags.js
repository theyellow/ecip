export function flagsApi(request) {
  return {
    list: (page = 0, size = 50, decision = '', intent = '', from = null, to = null, minConfidence = null) => {
      const params = new URLSearchParams({ page, size })
      if (decision) params.set('decision', decision)
      if (intent) params.set('intent', intent)
      if (from) params.set('from', from)
      if (to) params.set('to', to)
      if (minConfidence != null) params.set('minConfidence', minConfidence)
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
    analyse: id =>
      request(`/api/flags/${encodeURIComponent(id)}/analyse`, { method: 'POST' }),
    chat: (id, messages) =>
      request(`/api/flags/${encodeURIComponent(id)}/chat`, {
        method: 'POST',
        body: JSON.stringify({ messages }),
      }),
  }
}
