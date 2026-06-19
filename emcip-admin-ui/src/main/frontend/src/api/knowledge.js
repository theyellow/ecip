export function knowledgeApi(request, rawFetch) {
  return {
    /** POST /api/admin/knowledge/ingest/url — returns { jobId } */
    ingestUrl: (url, tenantId) =>
      request('/api/admin/knowledge/ingest/url', {
        method: 'POST',
        body: JSON.stringify({ url, tenantId: tenantId ?? null }),
      }),

    /**
     * POST /api/admin/knowledge/ingest/upload — multipart form data.
     * Uses rawFetch to avoid the JSON Content-Type header collision.
     */
    ingestUpload: (file, tenantId) => {
      const form = new FormData()
      form.append('file', file)
      if (tenantId) form.append('tenantId', tenantId)
      return rawFetch('/api/admin/knowledge/ingest/upload', {
        method: 'POST',
        body: form,
      })
    },

    /** GET /api/admin/knowledge/ingest/{jobId} — returns IngestionJobDto */
    status: jobId =>
      request(`/api/admin/knowledge/ingest/${encodeURIComponent(jobId)}`),

    /** GET /api/admin/knowledge/ingest — returns Spring Page<IngestionJobDto> */
    jobs: (page = 0, size = 20, tenantId) => {
      const params = new URLSearchParams({ page, size })
      if (tenantId) params.append('tenantId', tenantId)
      return request(`/api/admin/knowledge/ingest?${params}`)
    },
  }
}
