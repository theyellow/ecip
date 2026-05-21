export function usersApi(request) {
  return {
    list: () => request('/api/users'),
    create: body => request('/api/users', { method: 'POST', body: JSON.stringify(body) }),
    update: (id, body) => request(`/api/users/${encodeURIComponent(id)}`, {
      method: 'PUT', body: JSON.stringify(body),
    }),
    remove: id => request(`/api/users/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    resetPassword: (id, newPassword) => request(`/api/users/${encodeURIComponent(id)}/password`, {
      method: 'POST', body: JSON.stringify({ newPassword }),
    }),
  }
}
