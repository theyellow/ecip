export function telegramApi(request) {
  return {
    listAccounts: () => request('/api/telegram/accounts'),
    createAccount: body =>
      request('/api/telegram/accounts', { method: 'POST', body: JSON.stringify(body) }),
    deleteAccount: id =>
      request(`/api/telegram/accounts/${id}`, { method: 'DELETE' }),
    getStatus: id => request(`/api/telegram/accounts/${id}/status`),
    reconnect: id =>
      request(`/api/telegram/accounts/${id}/reconnect`, { method: 'POST' }),
    submitCode: (id, code) =>
      request(`/api/telegram/accounts/${id}/code`, {
        method: 'POST',
        body: JSON.stringify({ code }),
      }),
    submitPassword: (id, password) =>
      request(`/api/telegram/accounts/${id}/password`, {
        method: 'POST',
        body: JSON.stringify({ password }),
      }),
    logout: id =>
      request(`/api/telegram/accounts/${id}/logout`, { method: 'POST' }),
  }
}
