export function flagsApi(request) {
  return {
    list: (size = 50, decision = '') =>
      request(`/api/flags?size=${size}${decision ? `&decision=${encodeURIComponent(decision)}` : ''}`),
    updateStatus: (id, status) =>
      request(`/api/flags/${encodeURIComponent(id)}/status`, {
        method: 'PATCH',
        body: JSON.stringify({ status }),
      }),
  }
}
