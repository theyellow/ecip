/**
 * integrations.js — API client for Epic 42 enrichment integrations.
 *
 * Follows the same factory-function pattern as knowledge.js.
 * All key responses return { maskedKey } — the raw value is never sent by the backend.
 */

export function integrationsApi(request) {
  return {
    // --- Global key management (INTEGRATIONS_GLOBAL_MANAGE) ---

    /** GET /api/v1/admin/integrations/keys — list all global keys */
    listGlobalKeys: () =>
      request('/api/v1/admin/integrations/keys'),

    /** GET /api/v1/admin/integrations/keys?tenantId=... — list keys for a specific tenant */
    listKeysByTenant: (tenantId) =>
      request(`/api/v1/admin/integrations/keys?tenantId=${encodeURIComponent(tenantId)}`),

    /** POST /api/v1/admin/integrations/keys */
    createKey: (vendorId, apiKey, enabled = true) =>
      request('/api/v1/admin/integrations/keys', {
        method: 'POST',
        body: JSON.stringify({ vendorId, apiKey, enabled }),
      }),

    /** PUT /api/v1/admin/integrations/keys/{id} */
    updateKey: (id, vendorId, apiKey, enabled) =>
      request(`/api/v1/admin/integrations/keys/${encodeURIComponent(id)}`, {
        method: 'PUT',
        body: JSON.stringify({ vendorId, apiKey, enabled }),
      }),

    /** DELETE /api/v1/admin/integrations/keys/{id} */
    deleteKey: (id) =>
      request(`/api/v1/admin/integrations/keys/${encodeURIComponent(id)}`, {
        method: 'DELETE',
      }),

    // --- Tenant key management (INTEGRATIONS_TENANT_MANAGE) ---

    /** GET /api/v1/tenant/integrations/keys — list own tenant keys */
    listOwnKeys: () =>
      request('/api/v1/tenant/integrations/keys'),

    /** PUT /api/v1/tenant/integrations/keys/{vendorId} — upsert own key */
    upsertOwnKey: (vendorId, apiKey, enabled = true) =>
      request(`/api/v1/tenant/integrations/keys/${encodeURIComponent(vendorId)}`, {
        method: 'PUT',
        body: JSON.stringify({ vendorId, apiKey, enabled }),
      }),

    /** DELETE /api/v1/tenant/integrations/keys/{vendorId} */
    deleteOwnKey: (vendorId) =>
      request(`/api/v1/tenant/integrations/keys/${encodeURIComponent(vendorId)}`, {
        method: 'DELETE',
      }),

    // --- Sources & schedule (INTEGRATIONS_GLOBAL_MANAGE) ---

    /** GET /api/v1/admin/integrations/sources */
    listSources: () =>
      request('/api/v1/admin/integrations/sources'),

    /**
     * POST /api/v1/admin/integrations/sources/{id}/trigger
     * Returns { runId: "uuid" }
     */
    triggerSource: (sourceId) =>
      request(`/api/v1/admin/integrations/sources/${encodeURIComponent(sourceId)}/trigger`, {
        method: 'POST',
      }),

    /** GET /api/v1/admin/integrations/sources/{id}/runs */
    listRuns: (sourceId, page = 0, size = 20) =>
      request(
        `/api/v1/admin/integrations/sources/${encodeURIComponent(sourceId)}/runs?page=${page}&size=${size}`
      ),

    /** GET /api/v1/admin/integrations/sources/{id}/runs/{runId} */
    getRun: (sourceId, runId) =>
      request(
        `/api/v1/admin/integrations/sources/${encodeURIComponent(sourceId)}/runs/${encodeURIComponent(runId)}`
      ),
  }
}
