export function tenantsApi(request) {
  return {
    list: () => request('/api/tenants'),
    create: body => request('/api/tenants', { method: 'POST', body: JSON.stringify(body) }),
    update: (id, body) => request(`/api/tenants/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(body) }),
    remove: id => request(`/api/tenants/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  }
}
