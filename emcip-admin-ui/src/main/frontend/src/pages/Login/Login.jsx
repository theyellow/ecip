import { useEffect, useState } from 'react'
import { Logo } from '../../logo/Logo'
import { useAuth } from '../../auth/AuthContext'
import { useTheme } from '../../theme/ThemeContext'
import { Button } from '../../components/Button/Button'
import styles from './Login.module.css'

export function Login({ onSuccess }) {
  const { login } = useAuth()
  const { theme, toggleTheme } = useTheme()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [showAnswer, setShowAnswer] = useState(false)

  useEffect(() => {
    const seq = []
    const handler = e => {
      if (e.target.tagName === 'INPUT') return
      seq.push(e.key)
      if (seq.length > 2) seq.shift()
      if (seq.join('') === '42') setShowAnswer(true)
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [])

  const handleSubmit = async e => {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      await login(username, password)
      onSuccess()
    } catch {
      setError('Invalid credentials')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className={styles.container}>
      <div className={styles.card}>
        <button
          type="button"
          className={styles.themeToggle}
          onClick={toggleTheme}
          aria-label={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
          title={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
        >
          {theme === 'light' ? '☽' : '☀'}
        </button>
        <div className={styles.brand}>
          <Logo size={48} />
          <h1 className="emcip-wordmark">EMCIP</h1>
          <p className={styles.subtitle}>Community Intelligence Platform</p>
        </div>
        <form onSubmit={handleSubmit} className={styles.form}>
          {error && (
            <p role="alert" style={{
              color: 'var(--signal-stop-fg)',
              background: 'rgba(248,113,113,0.08)',
              border: '1px solid rgba(248,113,113,0.25)',
              padding: '8px 12px',
              fontFamily: 'var(--font-mono)',
              fontSize: '12px',
            }}>{error}</p>
          )}
          <div className={styles.field}>
            <label htmlFor="username">Operator</label>
            <input id="username" type="text" value={username}
              onChange={e => setUsername(e.target.value)}
              className={styles.input} autoComplete="username" required />
          </div>
          <div className={styles.field}>
            <label htmlFor="password">Passphrase</label>
            <input id="password" type="password" value={password}
              onChange={e => setPassword(e.target.value)}
              className={styles.input} autoComplete="current-password" required />
          </div>
          <Button type="submit" disabled={loading}>
            {loading ? 'Entering\u2026' : 'Enter the Construct'}
          </Button>
        </form>
        <p className={styles.footer}>
          Mostly Harmless.
          {showAnswer && <span className={styles.footerAnswer}> The answer is 42.</span>}
        </p>
      </div>
    </div>
  )
}
