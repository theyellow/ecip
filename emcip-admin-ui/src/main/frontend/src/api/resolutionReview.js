export function resolutionReviewApi(request) {
  return {
    list: (page = 0, size = 20, status = '', conceptType = '', tenantId = null) => {
      const params = new URLSearchParams({ page, size })
      if (status) params.set('status', status)
      if (conceptType) params.set('conceptType', conceptType)
      if (tenantId) params.set('tenantId', tenantId)
      return request(`/api/resolution-review?${params}`)
    },
    merge: (id) =>
      request(`/api/resolution-review/${encodeURIComponent(id)}/merge`, { method: 'PATCH' }),
    dismiss: (id) =>
      request(`/api/resolution-review/${encodeURIComponent(id)}/dismiss`, { method: 'PATCH' }),
  }
}
