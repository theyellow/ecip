export function intentSignalConfigApi(request) {
  return {
    get: () => request('/api/intent-signal-config'),
    upsert: body =>
      request('/api/intent-signal-config', { method: 'PUT', body: JSON.stringify(body) }),
  }
}
