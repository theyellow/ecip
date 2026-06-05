import { useEffect, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { tenantsApi } from '../../api/tenants'
import { DataTable } from '../../components/DataTable/DataTable'
import { Modal } from '../../components/Modal/Modal'
import styles from './Tenants.module.css'

const COLUMNS = [
  { key: 'id', label: 'ID', mono: true, width: 100, render: v => `${v?.slice(0, 8)}\u2026` },
  { key: 'name', label: 'Name' },
  { key: 'description', label: 'Description', render: v => v || '\u2014' },
  { key: 'llmModelOverride', label: 'LLM Override', mono: true, render: v => v || '\u2014' },
  { key: 'createdAt', label: 'Created', mono: true, width: 110, render: v => v ? new Date(v).toLocaleDateString() : '\u2014' },
]

function TenantModal({ tenant, onClose, onSave }) {
  const isEdit = !!tenant
  const [form, setForm] = useState({
    name: tenant?.name ?? '',
    description: tenant?.description ?? '',
    llmModelOverride: tenant?.llmModelOverride ?? '',
  })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  return (
    <Modal title={isEdit ? `Edit \u00b7 ${tenant.name}` : 'Create Tenant'} onClose={onClose} onSubmit={() => onSave(form)}>
      <div className={styles.field}>
        <label htmlFor="tenant-name">Name {isEdit ? '' : '*'}</label>
        <input id="tenant-name" type="text" className={styles.input} value={form.name}
          onChange={e => set('name', e.target.value)} required disabled={isEdit} />
        {isEdit && <p className={styles.hint}>Name cannot be changed.</p>}
      </div>
      <div className={styles.field}>
        <label htmlFor="tenant-description">Description</label>
        <textarea id="tenant-description" className={styles.input} value={form.description}
          onChange={e => set('description', e.target.value)} rows={3} />
      </div>
      <div className={styles.field}>
        <label htmlFor="tenant-llm">LLM Model Override</label>
        <input id="tenant-llm" type="text" className={styles.input} value={form.llmModelOverride}
          onChange={e => set('llmModelOverride', e.target.value)}
          placeholder="e.g. gpt-4o, claude-3-5-sonnet" />
      </div>
    </Modal>
  )
}

export function Tenants() {
  const api = tenantsApi(useAuthRequest())
  const [tenants, setTenants] = useState([])
  const [modal, setModal] = useState(null)
  const [error, setError] = useState('')

  const load = () => api.list().then(setTenants).catch(e => setError(e.message))
  useEffect(() => { load() }, [])

  const save = async form => {
    try {
      if (modal === 'create') await api.create(form)
      else await api.update(modal.id, { description: form.description, llmModelOverride: form.llmModelOverride })
      setModal(null); load()
    } catch (e) { setError(e.message) }
  }

  const remove = async tenant => {
    try { await api.remove(tenant.id); load() }
    catch (e) { setError(e.message) }
  }

  return (
    <>
      {error && <p style={{ color: 'var(--signal-stop-fg)', background: 'rgba(248,113,113,0.08)', border: '1px solid rgba(248,113,113,0.25)', padding: '8px 12px', fontFamily: 'var(--font-mono)', fontSize: '12px', marginBottom: 'var(--sp-3)' }} role="alert">{error}</p>}

      <DataTable
        title="Tenants"
        systemId={`\u2B21 tenants \u00b7 ${tenants.length} registered`}
        addLabel="+ Create Tenant"
        onAdd={() => setModal('create')}
        columns={COLUMNS}
        rows={tenants}
        onEdit={setModal}
        onDelete={remove}
        deleteMessage={t => `Delete tenant "${t.name}"? This cannot be undone.`}
        emptyText="No tenants registered"
      />

      {modal && (
        <TenantModal
          tenant={modal === 'create' ? null : modal}
          onClose={() => setModal(null)}
          onSave={save}
        />
      )}
    </>
  )
}
