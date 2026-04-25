import { useEffect, useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { makeRequest } from '../../api/client'
import { policyRulesApi } from '../../api/policyRules'
import { tenantsApi } from '../../api/tenants'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { Modal } from '../../components/Modal/Modal'
import styles from './PolicyRules.module.css'

function RuleModal({ rule, onClose, onSave, tenants }) {
  const [form, setForm] = useState({
    ruleName: rule?.ruleName ?? '',
    ruleType: rule?.ruleType ?? 'KEYWORD',
    action: rule?.action ?? 'FLAG',
    parameters: rule?.parameters ?? '',
    effectiveFrom: rule?.effectiveFrom?.slice(0, 16) ?? '',
    effectiveTo: rule?.effectiveTo?.slice(0, 16) ?? '',
    tenantId: rule?.tenantId ?? '',
  })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  return (
    <Modal title={rule ? 'Edit Rule' : 'Create Rule'} onClose={onClose} onSubmit={() => onSave(form)}>
      <label>Rule Name *</label>
      <input type="text" value={form.ruleName} onChange={e => set('ruleName', e.target.value)}
        className={styles.input} required disabled={!!rule} />
      <label>Rule Type</label>
      <select value={form.ruleType} onChange={e => set('ruleType', e.target.value)} className={styles.input}>
        {['KEYWORD', 'REGEX', 'SENTIMENT', 'INTENT', 'COMPOSITE'].map(t => <option key={t}>{t}</option>)}
      </select>
      <label>Action</label>
      <select value={form.action} onChange={e => set('action', e.target.value)} className={styles.input}>
        {['FLAG', 'WARN', 'MUTE', 'BAN', 'DELETE', 'ESCALATE'].map(a => <option key={a}>{a}</option>)}
      </select>
      <label>Parameters (JSON)</label>
      <textarea value={form.parameters} onChange={e => set('parameters', e.target.value)}
        className={styles.input} rows={4} placeholder='{"keywords":["spam"]}' />
      <label>Effective From</label>
      <input type="datetime-local" value={form.effectiveFrom}
        onChange={e => set('effectiveFrom', e.target.value)} className={styles.input} />
      <label>Effective To</label>
      <input type="datetime-local" value={form.effectiveTo}
        onChange={e => set('effectiveTo', e.target.value)} className={styles.input} />
      <label>Tenant</label>
      <select value={form.tenantId ?? ''}
        onChange={e => set('tenantId', e.target.value || null)} className={styles.input}>
        <option value="">— none —</option>
        {tenants.map(t => (
          <option key={t.id} value={t.id}>
            {t.name} ({t.id.slice(0, 8)})
          </option>
        ))}
      </select>
    </Modal>
  )
}

function HistoryModal({ ruleName, history, onClose }) {
  return (
    <Modal title={`History \u2014 ${ruleName}`} onClose={onClose}>
      {history.length === 0 ? <p>No history.</p> : history.map((h, i) => (
        <div key={i} className={styles.historyItem}>
          <span className={styles.mono}>v{h.version}</span>
          <span>{h.action}</span>
          <span className={styles.mono}>{h.changedAt ? new Date(h.changedAt).toLocaleString() : ''}</span>
        </div>
      ))}
    </Modal>
  )
}

const ACTION_VARIANT = { FLAG: 'blue', WARN: 'yellow', MUTE: 'yellow', BAN: 'red', DELETE: 'red', ESCALATE: 'gray' }

export function PolicyRules() {
  const { token } = useAuth()
  const api = policyRulesApi(makeRequest(token))
  const [rules, setRules] = useState([])
  const [modal, setModal] = useState(null)
  const [history, setHistory] = useState(null)
  const [error, setError] = useState('')
  const [tenants, setTenants] = useState([])

  const load = () => api.list().then(setRules).catch(e => setError(e.message))
  useEffect(() => { load() }, [])

  useEffect(() => {
    tenantsApi(makeRequest(token)).list().then(setTenants).catch(() => {})
  }, [])

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
    if (!confirm(`Delete rule "${rule.ruleName}"?`)) return
    try { await api.remove(rule.id); load() }
    catch (e) { setError(e.message) }
  }

  const showHistory = async rule => {
    const h = await api.history(rule.ruleName).catch(() => [])
    setHistory({ ruleName: rule.ruleName, items: h })
  }

  return (
    <div>
      <div className={styles.header}>
        <h2>Policy Rules</h2>
        <Button onClick={() => setModal('add')}>+ Create Rule</Button>
      </div>
      {error && <p className={styles.error} role="alert">{error}</p>}
      <table className={styles.table}>
        <thead>
          <tr><th>Rule Name</th><th>Type</th><th>Action</th><th>Effective From</th><th>Effective To</th><th></th></tr>
        </thead>
        <tbody>
          {rules.map(r => (
            <tr key={r.id}>
              <td>{r.ruleName}</td>
              <td><Badge variant="gray">{r.ruleType}</Badge></td>
              <td><Badge variant={ACTION_VARIANT[r.action] ?? 'gray'}>{r.action}</Badge></td>
              <td className={styles.mono}>{r.effectiveFrom ? new Date(r.effectiveFrom).toLocaleDateString() : '\u2014'}</td>
              <td className={styles.mono}>{r.effectiveTo ? new Date(r.effectiveTo).toLocaleDateString() : '\u2014'}</td>
              <td className={styles.actions}>
                <Button variant="secondary" onClick={() => showHistory(r)}>History</Button>
                <Button variant="secondary" onClick={() => setModal(r)}>Edit</Button>
                <Button variant="danger" onClick={() => remove(r)}>Delete</Button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {modal && <RuleModal rule={modal === 'add' ? null : modal} onClose={() => setModal(null)} onSave={save} tenants={tenants} />}
      {history && <HistoryModal ruleName={history.ruleName} history={history.items} onClose={() => setHistory(null)} />}
    </div>
  )
}
