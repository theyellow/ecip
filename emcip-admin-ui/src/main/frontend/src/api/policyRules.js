export function policyRulesApi(request) {
  return {
    list: () => request('/api/policy-rules'),
    getHistory: id => request(`/api/policy-rules/${encodeURIComponent(id)}/history`),
    create: body =>
      request('/api/policy-rules', { method: 'POST', body: JSON.stringify(body) }),
    update: (id, body) =>
      request(`/api/policy-rules/${encodeURIComponent(id)}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      }),
    remove: id =>
      request(`/api/policy-rules/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    dryRun: (rule, context) =>
      request('/api/policy-rules/dry-run', {
        method: 'POST',
        body: JSON.stringify({ rule, context }),
      }),
  }
}
