export function intentRulesApi(request) {
  return {
    list: () => request('/api/intent-rules'),
    create: body =>
      request('/api/intent-rules', { method: 'POST', body: JSON.stringify(body) }),
    update: (id, body) =>
      request(`/api/intent-rules/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
    remove: id =>
      request(`/api/intent-rules/${id}`, { method: 'DELETE' }),
  }
}
