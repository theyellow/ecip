export function ReportViewer({ report }) {
  if (!report) return null
  return (
    <div style={{ fontFamily: 'var(--font-mono)', fontSize: 13, color: 'var(--fg-2)', padding: 'var(--sp-4)' }}>
      Loading report\u2026
    </div>
  )
}
