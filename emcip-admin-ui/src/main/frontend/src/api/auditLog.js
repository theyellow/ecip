export function auditLogApi(request) {
  return {
    list: (size = 50, eventType = '') => {
      const params = new URLSearchParams({ size })
      if (eventType) params.set('eventType', eventType)
      return request(`/api/audit/events?${params}`)
    },
  }
}
