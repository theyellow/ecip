import { useEffect, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { policyRulesApi } from '../../api/policyRules'
import { tenantsApi } from '../../api/tenants'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { DataTable } from '../../components/DataTable/DataTable'
import { Modal } from '../../components/Modal/Modal'
import styles from './PolicyRules.module.css'

const ACTIONS = ['FLAG', 'WARN', 'MUTE', 'BAN', 'DELETE', 'ESCALATE']
const ACTION_VARIANT = { FLAG: 'blue', WARN: 'yellow', MUTE: 'yellow', BAN: 'red', DELETE: 'red', ESCALATE: 'gray' }

function RuleModal({ rule, onClose, onSave, tenants }) {
  const [form, setForm] = useState({
    name: rule?.name ?? '',
    targetIntent: rule?.targetIntent ?? 'KEYWORD',
    action: rule?.action ?? 'FLAG',
    priority: rule?.priority ?? 0,
    description: rule?.description ?? '',
    effectiveFrom: rule?.effectiveFrom?.slice(0, 16) ?? '',
    effectiveTo: rule?.effectiveTo?.slice(0, 16) ?? '',
    tenantId: rule?.tenantId ?? '',
  })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  return (
    <Modal title={rule ? 'Edit Rule' : 'Create Rule'} onClose={onClose} onSubmit={() => onSave(form)}>
      <div className={styles.field}>
        <label>Rule Name *</label>
        <input type="text" className={styles.input} value={form.name}
          onChange={e => set('name', e.target.value)} required disabled={!!rule} />
      </div>
      <div className={styles.field}>
        <label>Target Intent</label>
        <input type="text" className={styles.input} value={form.targetIntent}
          onChange={e => set('targetIntent', e.target.value)} placeholder="e.g. SPAM, GREETING, * (wildcard)" />
      </div>
      <div className={styles.field}>
        <label>Action</label>
        <select className={styles.input} value={form.action}
          onChange={e => set('action', e.target.value)}>
          {ACTIONS.map(a => <option key={a}>{a}</option>)}
        </select>
      </div>
      <div className={styles.field}>
        <label>Priority</label>
        <input type="number" className={styles.input} value={form.priority}
          onChange={e => set('priority', parseInt(e.target.value) || 0)} min={0} />
      </div>
      <div className={styles.field}>
        <label>Description</label>
        <textarea className={styles.input} value={form.description}
          onChange={e => set('description', e.target.value)} rows={4} placeholder="Optional rule description" />
      </div>
      <div className={styles.field}>
        <label>Effective From</label>
        <input type="datetime-local" className={styles.input} value={form.effectiveFrom}
          onChange={e => set('effectiveFrom', e.target.value)} />
      </div>
      <div className={styles.field}>
        <label>Effective To</label>
        <input type="datetime-local" className={styles.input} value={form.effectiveTo}
          onChange={e => set('effectiveTo', e.target.value)} />
      </div>
      <div className={styles.field}>
        <label>Tenant</label>
        <select className={styles.input} value={form.tenantId ?? ''}
          onChange={e => set('tenantId', e.target.value || null)}>
          <option value="">None</option>
          {tenants.map(t => (
            <option key={t.id} value={t.id}>{t.name} ({t.id.slice(0, 8)})</option>
          ))}
        </select>
      </div>
    </Modal>
  )
}

function HistoryModal({ ruleName, history, onClose }) {
  return (
    <Modal title={`History \u2014 ${ruleName}`} onClose={onClose}>
      {history.length === 0 ? <p style={{ color: 'var(--fg-3)', fontStyle: 'italic' }}>No history.</p> : history.map((h, i) => (
        <div key={i} className={styles.historyItem}>
          <span className={styles.mono}>v{h.version}</span>
          <span>{h.action}</span>
          <span className={styles.mono}>{h.changedAt ? new Date(h.changedAt).toLocaleString() : ''}</span>
        </div>
      ))}
    </Modal>
  )
}

export function PolicyRules() {
  const authRequest = useAuthRequest()
  const api = policyRulesApi(authRequest)
  const [rules, setRules] = useState([])
  const [modal, setModal] = useState(null)
  const [history, setHistory] = useState(null)
  const [error, setError] = useState('')
  const [tenants, setTenants] = useState([])

  const load = () => api.list().then(setRules).catch(e => setError(e.message))
  useEffect(() => { load() }, [])
  useEffect(() => { tenantsApi(authRequest).list().then(setTenants).catch(() => {}) }, [])

  const save = async form => {
    try {
      const payload = {
        ...form,
        effectiveFrom: form.effectiveFrom ? new Date(form.effectiveFrom).toISOString() : null,
        effectiveTo: form.effectiveTo ? new Date(form.effectiveTo).toISOString() : null,
      }
      if (modal === 'add') await api.create(payload)
      else await api.update(modal.id, payload)
      setModal(null); load()
    } catch (e) { setError(e.message) }
  }

  const remove = async rule => {
    if (!confirm(`Delete rule "${rule.name}"?`)) return
    try { await api.remove(rule.id); load() }
    catch (e) { setError(e.message) }
  }

  const showHistory = async rule => {
    const h = await api.history(rule.name).catch(() => [])
    setHistory({ ruleName: rule.name, items: h })
  }

  // Columns defined inside component so showHistory is in scope
  const columns = [
    { key: 'name', label: 'Rule Name' },
    { key: 'targetIntent', label: 'Intent', render: v => <Badge variant="gray">{v}</Badge> },
    { key: 'action', label: 'Action', width: 110, render: v => <Badge variant={ACTION_VARIANT[v] ?? 'gray'}>{v}</Badge> },
    { key: 'priority', label: 'Priority', mono: true, width: 80 },
    { key: 'effectiveFrom', label: 'From', mono: true, width: 110, render: v => v ? new Date(v).toLocaleDateString() : '\u2014' },
    { key: 'effectiveTo', label: 'To', mono: true, width: 110, render: v => v ? new Date(v).toLocaleDateString() : '\u2014' },
    { key: 'id', label: '', width: 80, render: (v, row) => (
      <Button variant="secondary" onClick={e => { e.stopPropagation(); showHistory(row) }}>History</Button>
    )},
  ]

  return (
    <>
      {error && <p style={{ color: 'var(--signal-stop-fg)', background: 'rgba(248,113,113,0.08)', border: '1px solid rgba(248,113,113,0.25)', padding: '8px 12px', fontFamily: 'var(--font-mono)', fontSize: '12px', marginBottom: 'var(--sp-3)' }} role="alert">{error}</p>}

      <DataTable
        title="Policy Rules"
        systemId={`\u2696 policy-rules \u00b7 ${rules.length} rules`}
        addLabel="+ Create Rule"
        onAdd={() => setModal('add')}
        columns={columns}
        rows={rules}
        onEdit={setModal}
        onDelete={remove}
        emptyText="No policy rules defined"
      />

      {modal && (
        <RuleModal
          rule={modal === 'add' ? null : modal}
          onClose={() => setModal(null)}
          onSave={save}
          tenants={tenants}
        />
      )}
      {history && (
        <HistoryModal
          ruleName={history.ruleName}
          history={history.items}
          onClose={() => setHistory(null)}
        />
      )}
    </>
  )
}
