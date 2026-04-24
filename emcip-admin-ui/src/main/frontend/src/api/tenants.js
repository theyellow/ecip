export function tenantsApi(request) {
  return {
    list: () => request('/api/tenants'),
    create: body => request('/api/tenants', { method: 'POST', body: JSON.stringify(body) }),
    remove: id => request(`/api/tenants/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  }
}
