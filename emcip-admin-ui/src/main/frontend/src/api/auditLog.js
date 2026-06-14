export function auditLogApi(request) {
  return {
    list: (page = 0, size = 50, eventType = '', from = null, to = null) => {
      const params = new URLSearchParams({ page, size })
      if (eventType) params.set('eventType', eventType)
      if (from) params.set('from', from)
      if (to) params.set('to', to)
      return request(`/api/audit/events?${params}`)
    },
  }
}
