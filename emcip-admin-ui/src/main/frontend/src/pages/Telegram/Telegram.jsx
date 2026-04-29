import { useCallback, useEffect, useMemo, useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { makeRequest } from '../../api/client'
import { telegramApi } from '../../api/telegram'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { Modal } from '../../components/Modal/Modal'
import styles from './Telegram.module.css'

const STATUS_VARIANT = {
  ACTIVE: 'green',
  AWAITING_CODE: 'yellow',
  AWAITING_PASSWORD: 'yellow',
  UNCONFIGURED: 'gray',
  DISCONNECTED: 'red',
}

export function Telegram() {
  const { token } = useAuth()
  const api = useMemo(() => telegramApi(makeRequest(token)), [token])

  const [accounts, setAccounts] = useState([])
  const [error, setError] = useState('')
  const [showAdd, setShowAdd] = useState(false)
  const [addForm, setAddForm] = useState({ phoneNumber: '', displayName: '' })
  const [wizard, setWizard] = useState(null) // { accountId, step: 'code'|'password', error }
  const [codeInput, setCodeInput] = useState('')
  const [passwordInput, setPasswordInput] = useState('')

  const loadAccounts = useCallback(() => {
    api.listAccounts().then(setAccounts).catch(e => setError(e.message))
  }, [api])

  useEffect(() => {
    loadAccounts()
  }, [loadAccounts])

  // Poll status when wizard is open
  useEffect(() => {
    if (!wizard) return
    const interval = setInterval(async () => {
      try {
        const s = await api.getStatus(wizard.accountId)
        if (s.status === 'ACTIVE') {
          setWizard(null)
          loadAccounts()
        } else if (s.status === 'AWAITING_PASSWORD' && wizard.step !== 'password') {
          setWizard(w => ({ ...w, step: 'password', error: null }))
        }
      } catch (_) {}
    }, 2500)
    return () => clearInterval(interval)
  }, [wizard, api, loadAccounts])

  const handleAdd = async () => {
    setError('')
    try {
      await api.createAccount({
        phoneNumber: addForm.phoneNumber,
        displayName: addForm.displayName,
      })
      setShowAdd(false)
      setAddForm({ phoneNumber: '', displayName: '' })
      loadAccounts()
    } catch (e) {
      setError(e.message)
    }
  }

  const handleReconnect = async id => {
    setError('')
    try {
      const res = await api.reconnect(id)
      if (res.accepted) {
        setWizard({ accountId: id, step: 'code', error: null })
      } else {
        setError(res.reason)
      }
    } catch (e) {
      setError(e.message)
    }
  }

  const handleSubmitCode = async () => {
    try {
      await api.submitCode(wizard.accountId, codeInput)
      setCodeInput('')
    } catch (e) {
      setWizard(w => ({ ...w, error: e.message }))
    }
  }

  const handleSubmitPassword = async () => {
    try {
      await api.submitPassword(wizard.accountId, passwordInput)
      setPasswordInput('')
    } catch (e) {
      setWizard(w => ({ ...w, error: e.message }))
    }
  }

  const handleLogout = async id => {
    setError('')
    try {
      await api.logout(id)
      loadAccounts()
    } catch (e) {
      setError(e.message)
    }
  }

  const handleDelete = async id => {
    setError('')
    try {
      await api.deleteAccount(id)
      loadAccounts()
    } catch (e) {
      setError(e.message)
    }
  }

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h2>Telegram Accounts</h2>
        <Button onClick={() => setShowAdd(true)}>Add Account</Button>
      </div>

      {error && <p className={styles.error} role="alert">{error}</p>}

      <table className={styles.table}>
        <thead>
          <tr>
            <th>Name</th>
            <th>Phone</th>
            <th>Status</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {accounts.map(a => (
            <tr key={a.id}>
              <td>{a.displayName || '—'}</td>
              <td>{a.phoneNumber}</td>
              <td>
                <Badge variant={STATUS_VARIANT[a.status] ?? 'gray'} title={a.lastError ?? ''}>
                  {a.status}
                </Badge>
              </td>
              <td className={styles.actions}>
                <Button variant="secondary" onClick={() => handleReconnect(a.id)}>Auth</Button>
                <Button variant="secondary" onClick={() => handleLogout(a.id)}>Logout</Button>
                <Button variant="danger" onClick={() => handleDelete(a.id)}>Delete</Button>
              </td>
            </tr>
          ))}
          {accounts.length === 0 && (
            <tr><td colSpan={4} className={styles.empty}>No accounts configured</td></tr>
          )}
        </tbody>
      </table>

      {showAdd && (
        <Modal title="Add Telegram Account" onClose={() => setShowAdd(false)}>
          <div className={styles.form}>
            {[
              { label: 'Display Name', key: 'displayName', type: 'text', placeholder: 'Monitor account 1' },
              { label: 'Phone Number', key: 'phoneNumber', type: 'text', placeholder: '+49123456789' },
            ].map(({ label, key, type, placeholder }) => (
              <div key={key}>
                <label className={styles.label}>{label}</label>
                <input
                  type={type}
                  className={styles.input}
                  placeholder={placeholder}
                  value={addForm[key]}
                  onChange={e => setAddForm(f => ({ ...f, [key]: e.target.value }))}
                />
              </div>
            ))}
            <div className={styles.modalActions}>
              <Button onClick={handleAdd}>Save</Button>
              <Button variant="secondary" onClick={() => setShowAdd(false)}>Cancel</Button>
            </div>
          </div>
        </Modal>
      )}

      {wizard && (
        <Modal title="Authenticate Account" onClose={() => setWizard(null)}>
          <div className={styles.form}>
            {wizard.error && <p className={styles.error}>{wizard.error}</p>}
            {wizard.step === 'code' && (
              <>
                <p>Enter the verification code sent to your Telegram app.</p>
                <input
                  type="text"
                  className={styles.input}
                  placeholder="12345"
                  value={codeInput}
                  onChange={e => setCodeInput(e.target.value)}
                  autoFocus
                />
                <div className={styles.modalActions}>
                  <Button onClick={handleSubmitCode}>Submit Code</Button>
                </div>
              </>
            )}
            {wizard.step === 'password' && (
              <>
                <p>Enter your 2FA password.</p>
                <input
                  type="password"
                  className={styles.input}
                  value={passwordInput}
                  onChange={e => setPasswordInput(e.target.value)}
                  autoFocus
                />
                <div className={styles.modalActions}>
                  <Button onClick={handleSubmitPassword}>Submit Password</Button>
                </div>
              </>
            )}
          </div>
        </Modal>
      )}
    </div>
  )
}
