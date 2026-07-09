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

    /** POST /api/ai/warm-up — primes the knowledge engine for specified task types */
    warmUp: (taskTypes) =>
      request('/api/ai/warm-up', {
        method: 'POST',
        body: JSON.stringify({ taskTypes }),
      }),

    /**
     * POST /api/admin/knowledge/search
     * Returns { graphResults: [...], documentResults: [...] }
     */
    search: (query, searchType = 'HYBRID', tenantId, conceptTypes, limit = 20) =>
      request('/api/admin/knowledge/search', {
        method: 'POST',
        body: JSON.stringify({
          query,
          searchType,
          tenantId: tenantId ?? null,
          conceptTypes: conceptTypes ?? null,
          limit,
        }),
      }),

    /** GET /api/admin/knowledge/graph/topics */
    graphTopics: (tenantId, limit = 50) => {
      const params = new URLSearchParams({ limit })
      if (tenantId) params.append('tenantId', tenantId)
      return request(`/api/admin/knowledge/graph/topics?${params}`)
    },

    /** GET /api/admin/knowledge/graph/persons */
    graphPersons: (tenantId, limit = 50) => {
      const params = new URLSearchParams({ limit })
      if (tenantId) params.append('tenantId', tenantId)
      return request(`/api/admin/knowledge/graph/persons?${params}`)
    },

    /** GET /api/admin/knowledge/graph/node/{id}/neighbors */
    graphNeighbors: (nodeId, relationshipType, depth = 1) => {
      const params = new URLSearchParams({ depth })
      if (relationshipType) params.append('relationshipType', relationshipType)
      return request(
        `/api/admin/knowledge/graph/node/${encodeURIComponent(nodeId)}/neighbors?${params}`
      )
    },

    /** GET /api/admin/knowledge/ingest/{jobId}/details — returns IngestionJobDetailDto */
    jobDetails: jobId =>
      request(`/api/admin/knowledge/ingest/${encodeURIComponent(jobId)}/details`),

    /** DELETE /api/admin/knowledge/ingest/{jobId} — returns 204 */
    deleteJob: jobId =>
      request(`/api/admin/knowledge/ingest/${encodeURIComponent(jobId)}`, {
        method: 'DELETE',
      }),

    /** POST /api/admin/knowledge/ingest/{jobId}/reingest — returns { jobId } or 400 */
    reingest: jobId =>
      request(`/api/admin/knowledge/ingest/${encodeURIComponent(jobId)}/reingest`, {
        method: 'POST',
      }),
  }
}
