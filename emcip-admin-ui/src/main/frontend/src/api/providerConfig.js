export function providerConfigApi(request) {
  return {
    listProviderConfigs: () => request('/api/ai/provider-config'),

    createProviderConfig: (form) =>
      request('/api/ai/provider-config', { method: 'POST', body: JSON.stringify(form) }),

    updateProviderConfig: (id, form) =>
      request(`/api/ai/provider-config/${encodeURIComponent(id)}`, {
        method: 'PUT',
        body: JSON.stringify(form),
      }),

    deleteProviderConfig: (id) =>
      request(`/api/ai/provider-config/${encodeURIComponent(id)}`, { method: 'DELETE' }),

    /** Lists models on the active provider, or on an ad-hoc URL for testing before save. */
    getProxyModels: (params) => {
      const qs = params
        ? '?' + new URLSearchParams(Object.fromEntries(
            Object.entries(params).filter(([, v]) => v != null && v !== '')
          ))
        : ''
      return request(`/api/ai/provider-config/models${qs}`)
    },
  }
}
