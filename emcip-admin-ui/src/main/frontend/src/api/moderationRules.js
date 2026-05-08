export function moderationRulesApi(request) {
  return {
    list: () => request('/api/moderation-rules'),
    create: body =>
      request('/api/moderation-rules', { method: 'POST', body: JSON.stringify(body) }),
    update: (id, body) =>
      request(`/api/moderation-rules/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
    remove: id =>
      request(`/api/moderation-rules/${id}`, { method: 'DELETE' }),
  }
}
