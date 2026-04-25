export function aiConfigApi(request) {
  return {
    listModels: () => request('/api/ai/models'),
    createModel: body =>
      request('/api/ai/models', { method: 'POST', body: JSON.stringify(body) }),
    updateModel: (id, body) =>
      request(`/api/ai/models/${encodeURIComponent(id)}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      }),
    deleteModel: id =>
      request(`/api/ai/models/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    listTemplates: () => request('/api/ai/templates'),
    createTemplate: body =>
      request('/api/ai/templates', { method: 'POST', body: JSON.stringify(body) }),
    updateTemplate: (id, body) =>
      request(`/api/ai/templates/${encodeURIComponent(id)}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      }),
    deleteTemplate: id =>
      request(`/api/ai/templates/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  }
}
