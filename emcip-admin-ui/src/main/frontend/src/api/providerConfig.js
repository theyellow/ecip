export function providerConfigApi(request) {
  return {
    listProviderConfigs: () => request('GET', '/api/ai/provider-config'),

    createProviderConfig: (form) => request('POST', '/api/ai/provider-config', form),

    updateProviderConfig: (id, form) => request('PUT', `/api/ai/provider-config/${id}`, form),

    deleteProviderConfig: (id) => request('DELETE', `/api/ai/provider-config/${id}`),

    getProxyModels: () => request('GET', '/api/ai/provider-config/models'),
  }
}
