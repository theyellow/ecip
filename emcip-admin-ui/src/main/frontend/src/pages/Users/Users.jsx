import { useEffect, useMemo, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { usersApi } from '../../api/usersApi'
import { tenantsApi } from '../../api/tenants'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { DataTable } from '../../components/DataTable/DataTable'
import { Modal } from '../../components/Modal/Modal'
import styles from './Users.module.css'

const ROLES = ['ADMIN', 'TENANT_ADMIN']
const ROLE_VARIANT = { ADMIN: 'red', TENANT_ADMIN: 'yellow' }

export function Users() {
  const request = useAuthRequest()
  const api = useMemo(() => usersApi(request), [request])
  const tApi = useMemo(() => tenantsApi(request), [request])

  const [users, setUsers] = useState([])
  const [tenants, setTenants] = useState([])
  const [error, setError] = useState('')

  const [modal, setModal] = useState(null)
  const [selected, setSelected] = useState(null)
  const [form, setForm] = useState({ username: '', email: '', password: '', role: 'TENANT_ADMIN', tenantId: '' })
  const [newPassword, setNewPassword] = useState('')

  useEffect(() => {
    Promise.all([api.list(), tApi.list()])
      .then(([u, t]) => { setUsers(u); setTenants(t) })
      .catch(e => setError(e.message))
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const reload = () => api.list().then(setUsers).catch(e => setError(e.message))

  const openCreate = () => {
    setForm({ username: '', email: '', password: '', role: 'TENANT_ADMIN', tenantId: '' })
    setModal('create')
  }

  const openEdit = user => {
    setSelected(user)
    setForm({ username: user.username, email: user.email, password: '', role: user.role, tenantId: user.tenantId ?? '' })
    setModal('edit')
  }

  const openPasswordReset = user => {
    setSelected(user)
    setNewPassword('')
    setModal('password')
  }

  const handleSubmit = async () => {
    setError('')
    const body = { ...form, tenantId: form.tenantId || null, password: form.password || undefined }
    try {
      if (modal === 'create') await api.create(body)
      else await api.update(selected.id, body)
      setModal(null)
      reload()
    } catch (e) { setError(e.message) }
  }

  const handleDelete = async user => {
    try { await api.remove(user.id); reload() } catch (e) { setError(e.message) }
  }

  const handlePasswordReset = async () => {
    setError('')
    try {
      await api.resetPassword(selected.id, newPassword)
      setModal(null)
    } catch (e) { setError(e.message) }
  }

  const activeCount = users.filter(u => u.enabled).length

  const columns = [
    { key: 'username', label: 'Username', mono: true, width: 140 },
    { key: 'email', label: 'Email', mono: true },
    { key: 'role', label: 'Role', width: 110, render: v => <Badge variant={ROLE_VARIANT[v] ?? 'gray'}>{v}</Badge> },
    { key: 'tenantName', label: 'Tenant', render: v => v || '\u2014' },
    { key: 'enabled', label: 'Enabled', width: 80, render: v => <Badge variant={v ? 'green' : 'gray'}>{v ? 'ON' : 'OFF'}</Badge> },
    {
      key: 'id',
      label: '',
      width: 80,
      render: (_, row) => (
        <Button variant="secondary" onClick={e => { e.stopPropagation(); openPasswordReset(row) }}>
          Password
        </Button>
      ),
    },
  ]

  return (
    <>
      {error && (
        <p role="alert" style={{
          color: 'var(--signal-stop-fg)',
          background: 'rgba(248,113,113,0.08)',
          border: '1px solid rgba(248,113,113,0.25)',
          padding: '8px 12px',
          fontFamily: 'var(--font-mono)',
          fontSize: '12px',
          marginBottom: 'var(--sp-3)',
        }}>{error}</p>
      )}

      <DataTable
        title="Users"
        systemId={`\u25C9 admin-api \u00b7 ${activeCount}/${users.length} active`}
        addLabel="+ Add User"
        onAdd={openCreate}
        rows={users}
        columns={columns}
        onEdit={openEdit}
        onDelete={handleDelete}
        deleteMessage={u => `Delete user "${u.username}"? This cannot be undone.`}
        emptyText="No users configured. Add a user to get started."
      />

      {(modal === 'create' || modal === 'edit') && (
        <Modal
          title={modal === 'create' ? 'Add User' : `Edit \u00b7 ${selected?.username}`}
          onClose={() => setModal(null)}
          onSubmit={handleSubmit}
        >
          <div className={styles.field}>
            <label htmlFor="user-username">Username</label>
            <input id="user-username" className={styles.input} value={form.username}
              onChange={e => setForm(f => ({ ...f, username: e.target.value }))}
              disabled={modal === 'edit'} />
          </div>
          <div className={styles.field}>
            <label htmlFor="user-email">Email</label>
            <input id="user-email" type="email" className={styles.input} value={form.email}
              onChange={e => setForm(f => ({ ...f, email: e.target.value }))} />
          </div>
          {modal === 'create' && (
            <div className={styles.field}>
              <label htmlFor="user-password">Password</label>
              <input id="user-password" type="password" className={styles.input} value={form.password}
                onChange={e => setForm(f => ({ ...f, password: e.target.value }))} />
            </div>
          )}
          <div className={styles.field}>
            <label htmlFor="user-role">Role</label>
            <select id="user-role" className={styles.input} value={form.role}
              onChange={e => setForm(f => ({ ...f, role: e.target.value, tenantId: '' }))}>
              {ROLES.map(r => <option key={r} value={r}>{r}</option>)}
            </select>
          </div>
          {form.role === 'TENANT_ADMIN' && (
            <div className={styles.field}>
              <label htmlFor="user-tenant">Tenant</label>
              <select id="user-tenant" className={styles.input} value={form.tenantId}
                onChange={e => setForm(f => ({ ...f, tenantId: e.target.value }))}>
                <option value="">{'\u2014'} select {'\u2014'}</option>
                {tenants.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
              </select>
            </div>
          )}
        </Modal>
      )}

      {modal === 'password' && (
        <Modal
          title={`Reset Password \u00b7 ${selected?.username}`}
          onClose={() => setModal(null)}
          onSubmit={handlePasswordReset}
        >
          <div className={styles.field}>
            <label htmlFor="user-new-password">New Password</label>
            <input id="user-new-password" type="password" className={styles.input} value={newPassword}
              onChange={e => setNewPassword(e.target.value)} autoFocus />
          </div>
        </Modal>
      )}
    </>
  )
}
