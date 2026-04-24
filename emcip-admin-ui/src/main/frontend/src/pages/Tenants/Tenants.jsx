import { useEffect, useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { makeRequest } from '../../api/client'
import { tenantsApi } from '../../api/tenants'
import { Button } from '../../components/Button/Button'
import { Modal } from '../../components/Modal/Modal'
import styles from './Tenants.module.css'

function TenantModal({ onClose, onSave }) {
  const [form, setForm] = useState({ name: '', description: '', llmModelOverride: '' })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  return (
    <Modal title="Create Tenant" onClose={onClose} onSubmit={() => onSave(form)}>
      <label>Name *</label>
      <input type="text" value={form.name} onChange={e => set('name', e.target.value)}
        className={styles.input} required />
      <label>Description</label>
      <textarea value={form.description} onChange={e => set('description', e.target.value)}
        className={styles.input} rows={3} />
      <label>LLM Model Override</label>
      <input type="text" value={form.llmModelOverride}
        onChange={e => set('llmModelOverride', e.target.value)}
        className={styles.input} placeholder="e.g. gpt-4o, claude-3-5-sonnet" />
    </Modal>
  )
}

export function Tenants() {
  const { token } = useAuth()
  const api = tenantsApi(makeRequest(token))
  const [tenants, setTenants] = useState([])
  const [showModal, setShowModal] = useState(false)
  const [error, setError] = useState('')

  const load = () => api.list().then(setTenants).catch(e => setError(e.message))
  useEffect(() => { load() }, [])

  const save = async form => {
    try { await api.create(form); setShowModal(false); load() }
    catch (e) { setError(e.message) }
  }

  const remove = async tenant => {
    if (!confirm(`Delete tenant "${tenant.name}"?`)) return
    try { await api.remove(tenant.id); load() }
    catch (e) { setError(e.message) }
  }

  return (
    <div>
      <div className={styles.header}>
        <h2>Tenants</h2>
        <Button onClick={() => setShowModal(true)}>+ Create Tenant</Button>
      </div>
      {error && <p className={styles.error} role="alert">{error}</p>}
      <table className={styles.table}>
        <thead>
          <tr><th>ID</th><th>Name</th><th>Description</th><th>LLM Override</th><th>Created</th><th></th></tr>
        </thead>
        <tbody>
          {tenants.map(t => (
            <tr key={t.id}>
              <td className={styles.mono}>{t.id?.slice(0, 8)}\u2026</td>
              <td>{t.name}</td>
              <td className={styles.desc}>{t.description ?? '\u2014'}</td>
              <td className={styles.mono}>{t.llmModelOverride ?? '\u2014'}</td>
              <td>{t.createdAt ? new Date(t.createdAt).toLocaleDateString() : '\u2014'}</td>
              <td><Button variant="danger" onClick={() => remove(t)}>Delete</Button></td>
            </tr>
          ))}
        </tbody>
      </table>
      {showModal && <TenantModal onClose={() => setShowModal(false)} onSave={save} />}
    </div>
  )
}
