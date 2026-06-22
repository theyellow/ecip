import styles from './ReportViewer.module.css'

function renderMarkdownLines(content) {
  if (!content) return null
  const lines = content.split('\n')
  const elements = []
  let listBuffer = []
  let key = 0

  function flushList() {
    if (listBuffer.length > 0) {
      elements.push(
        <ul key={key++} className={styles.list}>
          {listBuffer.map((item, i) => (
            <li key={i} className={styles.listItem}>
              {item}
            </li>
          ))}
        </ul>
      )
      listBuffer = []
    }
  }

  for (const line of lines) {
    if (line.startsWith('## ')) {
      flushList()
      elements.push(
        <h3 key={key++} className={styles.h2}>
          {line.slice(3)}
        </h3>
      )
    } else if (line.startsWith('### ')) {
      flushList()
      elements.push(
        <h4 key={key++} className={styles.h3}>
          {line.slice(4)}
        </h4>
      )
    } else if (line.startsWith('# ')) {
      flushList()
      elements.push(
        <h2 key={key++} className={styles.h1}>
          {line.slice(2)}
        </h2>
      )
    } else if (line.startsWith('- ')) {
      listBuffer.push(line.slice(2))
    } else if (line.trim() === '') {
      flushList()
      elements.push(<div key={key++} className={styles.spacer} />)
    } else {
      flushList()
      elements.push(
        <p key={key++} className={styles.para}>
          {line}
        </p>
      )
    }
  }
  flushList()
  return elements
}

export function ReportViewer({ report }) {
  if (!report) return null

  function handleDownload() {
    const blob = new Blob([report.content], { type: 'text/markdown;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `research-report-${report.sessionId}.md`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }

  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <div>
          <div className={styles.title}>{report.title}</div>
          <div className={styles.meta}>
            {report.template} &middot; v{report.version} &middot;{' '}
            {report.createdAt ? new Date(report.createdAt).toLocaleString() : ''}
          </div>
        </div>
        <button className={styles.downloadBtn} onClick={handleDownload} type="button">
          &#8595; Download .md
        </button>
      </div>

      <div className={styles.content}>{renderMarkdownLines(report.content)}</div>
    </div>
  )
}
