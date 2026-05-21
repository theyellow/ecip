import { useEffect, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { usersApi } from '../../api/usersApi'
import { tenantsApi } from '../../api/tenants'

const ROLES = ['ADMIN', 'TENANT_ADMIN']

export function Users() {
  const request = useAuthRequest()
  const api = usersApi(request)
  const tApi = tenantsApi(request)

  const [users, setUsers] = useState([])
  const [tenants, setTenants] = useState([])
  const [error, setError] = useState(null)

  // Modal state
  const [modal, setModal] = useState(null) // null | 'create' | 'edit' | 'password'
  const [selected, setSelected] = useState(null)
  const [form, setForm] = useState({ username: '', email: '', password: '', role: 'TENANT_ADMIN', tenantId: '' })
  const [newPassword, setNewPassword] = useState('')

  useEffect(() => {
    Promise.all([api.list(), tApi.list()])
      .then(([u, t]) => { setUsers(u); setTenants(t) })
      .catch(e => setError(e.message))
  }, [])

  const reload = () => api.list().then(setUsers).catch(e => setError(e.message))

  const openCreate = () => {
    setForm({ username: '', email: '', password: '', role: 'TENANT_ADMIN', tenantId: '' })
    setModal('create')
  }

  const openEdit = (user) => {
    setSelected(user)
    setForm({ username: user.username, email: user.email, password: '', role: user.role, tenantId: user.tenantId ?? '' })
    setModal('edit')
  }

  const openPasswordReset = (user) => { setSelected(user); setNewPassword(''); setModal('password') }

  const handleSubmit = async () => {
    const body = { ...form, tenantId: form.tenantId || null, password: form.password || undefined }
    try {
      if (modal === 'create') await api.create(body)
      else await api.update(selected.id, body)
      setModal(null)
      reload()
    } catch (e) { setError(e.message) }
  }

  const handleDelete = async (user) => {
    if (!confirm(`Delete user "${user.username}"?`)) return
    try { await api.remove(user.id); reload() } catch (e) { setError(e.message) }
  }

  const handlePasswordReset = async () => {
    try { await api.resetPassword(selected.id, newPassword); setModal(null) }
    catch (e) { setError(e.message) }
  }

  return (
    <div style={{ padding: '1.5rem' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
        <h2 style={{ margin: 0 }}>Users</h2>
        <button onClick={openCreate}>+ Add User</button>
      </div>

      {error && <p style={{ color: 'var(--color-error, red)' }}>{error}</p>}

      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr>
            {['Username', 'Email', 'Role', 'Tenant', 'Enabled', 'Actions'].map(h => (
              <th key={h} style={{ textAlign: 'left', padding: '0.5rem', borderBottom: '1px solid rgba(201,168,76,0.2)' }}>{h}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {users.map(u => (
            <tr key={u.id}>
              <td style={{ padding: '0.5rem' }}>{u.username}</td>
              <td style={{ padding: '0.5rem' }}>{u.email}</td>
              <td style={{ padding: '0.5rem' }}>{u.role}</td>
              <td style={{ padding: '0.5rem' }}>{u.tenantName ?? '—'}</td>
              <td style={{ padding: '0.5rem' }}>{u.enabled ? '✓' : '✗'}</td>
              <td style={{ padding: '0.5rem', display: 'flex', gap: '0.5rem' }}>
                <button onClick={() => openEdit(u)}>Edit</button>
                <button onClick={() => openPasswordReset(u)}>Password</button>
                <button onClick={() => handleDelete(u)}>Delete</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {(modal === 'create' || modal === 'edit') && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <div style={{ background: 'var(--bg-surface, #1e1e2e)', padding: '2rem', borderRadius: '8px', minWidth: '360px' }}>
            <h3>{modal === 'create' ? 'Add User' : 'Edit User'}</h3>
            <label>Username<br />
              <input value={form.username} onChange={e => setForm(f => ({ ...f, username: e.target.value }))}
                disabled={modal === 'edit'} style={{ width: '100%', marginBottom: '0.75rem' }} />
            </label>
            <label>Email<br />
              <input value={form.email} onChange={e => setForm(f => ({ ...f, email: e.target.value }))}
                style={{ width: '100%', marginBottom: '0.75rem' }} />
            </label>
            {modal === 'create' && (
              <label>Password<br />
                <input type="password" value={form.password} onChange={e => setForm(f => ({ ...f, password: e.target.value }))}
                  style={{ width: '100%', marginBottom: '0.75rem' }} />
              </label>
            )}
            <label>Role<br />
              <select value={form.role} onChange={e => setForm(f => ({ ...f, role: e.target.value, tenantId: '' }))}
                style={{ width: '100%', marginBottom: '0.75rem' }}>
                {ROLES.map(r => <option key={r} value={r}>{r}</option>)}
              </select>
            </label>
            {form.role === 'TENANT_ADMIN' && (
              <label>Tenant<br />
                <select value={form.tenantId} onChange={e => setForm(f => ({ ...f, tenantId: e.target.value }))}
                  style={{ width: '100%', marginBottom: '0.75rem' }}>
                  <option value="">— select —</option>
                  {tenants.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
                </select>
              </label>
            )}
            <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end' }}>
              <button onClick={() => setModal(null)}>Cancel</button>
              <button onClick={handleSubmit}>Save</button>
            </div>
          </div>
        </div>
      )}

      {modal === 'password' && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <div style={{ background: 'var(--bg-surface, #1e1e2e)', padding: '2rem', borderRadius: '8px', minWidth: '320px' }}>
            <h3>Reset Password — {selected?.username}</h3>
            <label>New Password<br />
              <input type="password" value={newPassword} onChange={e => setNewPassword(e.target.value)}
                style={{ width: '100%', marginBottom: '0.75rem' }} />
            </label>
            <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end' }}>
              <button onClick={() => setModal(null)}>Cancel</button>
              <button onClick={handlePasswordReset}>Save</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
