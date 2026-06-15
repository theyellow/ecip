export function costsApi(request) {
  return {
    totals: (from, to) =>
      request(`/api/costs/totals?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
    byModel: (from, to) =>
      request(`/api/costs/by-model?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
    byDay: (from, to) =>
      request(`/api/costs/by-day?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
  }
}
