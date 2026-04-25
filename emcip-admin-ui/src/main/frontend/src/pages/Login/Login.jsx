import { useState } from 'react'
import { Logo } from '../../logo/Logo'
import { useAuth } from '../../auth/AuthContext'
import styles from './Login.module.css'

export function Login({ onSuccess }) {
  const { login } = useAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

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
        <div className={styles.brand}>
          <Logo size={48} />
          <h1 className="emcip-wordmark">EMCIP</h1>
          <p className={styles.subtitle}>Community Intelligence Platform</p>
        </div>
        <form onSubmit={handleSubmit} className={styles.form}>
          {error && <p className={styles.error} role="alert">{error}</p>}
          <label htmlFor="username" className={styles.label}>Username</label>
          <input id="username" type="text" value={username}
            onChange={e => setUsername(e.target.value)}
            className={styles.input} autoComplete="username" required />
          <label htmlFor="password" className={styles.label}>Password</label>
          <input id="password" type="password" value={password}
            onChange={e => setPassword(e.target.value)}
            className={styles.input} autoComplete="current-password" required />
          <button type="submit" className={styles.submit} disabled={loading}>
            {loading ? 'Signing in\u2026' : 'Sign In'}
          </button>
        </form>
      </div>
    </div>
  )
}
