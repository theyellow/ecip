export function auditLogApi(request) {
  return {
    list: (page = 0, size = 50, eventType = '') => {
      const params = new URLSearchParams({ page, size })
      if (eventType) params.set('eventType', eventType)
      return request(`/api/audit/events?${params}`)
    },
  }
}
