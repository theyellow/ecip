export function policyRulesApi(request) {
  return {
    list: () => request('/api/policy-rules'),
    history: name => request(`/api/policy-rules/history/${encodeURIComponent(name)}`),
    create: body =>
      request('/api/policy-rules', { method: 'POST', body: JSON.stringify(body) }),
    update: (id, body) =>
      request(`/api/policy-rules/${encodeURIComponent(id)}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      }),
    remove: id =>
      request(`/api/policy-rules/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  }
}
