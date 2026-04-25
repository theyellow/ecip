export function telegramApi(request) {
  return {
    getConfig: () => request('/api/telegram/config'),
    saveConfig: body =>
      request('/api/telegram/config', { method: 'PUT', body: JSON.stringify(body) }),
    getStatus: () => request('/api/telegram/status'),
    reconnect: () => request('/api/telegram/reconnect', { method: 'POST' }),
  }
}
