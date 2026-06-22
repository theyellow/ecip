// emcip-admin-ui/src/main/frontend/src/api/research.js

export function researchApi(request) {
  return {
    /**
     * Start a new research session.
     * body: { question, tenantId?, maxIterations?, maxLlmCalls?, costLimitUsd?,
     *         webSearchEnabled?, reportTemplate? }
     * Returns ResearchSessionDto (synchronous — may take 10-30s).
     */
    startSession: (body) =>
      request('/api/admin/knowledge/research', {
        method: 'POST',
        body: JSON.stringify(body),
      }),

    /**
     * List all sessions, optionally filtered by tenantId.
     * Returns ResearchSessionDto[] (without evidence array populated).
     */
    listSessions: (tenantId) => {
      const params = tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ''
      return request(`/api/admin/knowledge/research${params}`)
    },

    /**
     * Get a single session with its full evidence list.
     * Returns ResearchSessionDto (with evidence array).
     */
    getSession: (id) => request(`/api/admin/knowledge/research/${id}`),

    /**
     * Pause a RUNNING session.
     * Returns updated ResearchSessionDto.
     */
    pauseSession: (id) =>
      request(`/api/admin/knowledge/research/${id}/pause`, { method: 'POST' }),

    /**
     * Resume a PAUSED session.
     * Returns updated ResearchSessionDto.
     */
    resumeSession: (id) =>
      request(`/api/admin/knowledge/research/${id}/resume`, { method: 'POST' }),

    /**
     * Get the compiled report for a completed session.
     * Returns ResearchReportDto: { id, tenantId, sessionId, template, title, content, version, createdAt }
     * content is the full Markdown text.
     */
    getReport: (id) => request(`/api/admin/knowledge/research/${id}/report`),
  }
}
