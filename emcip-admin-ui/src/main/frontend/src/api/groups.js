export function groupsApi(request) {
  return {
    list: () => request('/api/groups'),
    create: body => request('/api/groups', { method: 'POST', body: JSON.stringify(body) }),
    update: (chatId, body) =>
      request(`/api/groups/${encodeURIComponent(chatId)}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      }),
    remove: chatId =>
      request(`/api/groups/${encodeURIComponent(chatId)}`, { method: 'DELETE' }),
    watchers: chatId => request(`/api/groups/${encodeURIComponent(chatId)}/watchers`),
    backfill: (chatId, body) =>
      request(`/api/groups/${encodeURIComponent(chatId)}/backfill`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    backfillStatus: (chatId, backfillId) =>
      request(
        `/api/groups/${encodeURIComponent(chatId)}/backfill/${encodeURIComponent(backfillId)}`
      ),
  }
}
