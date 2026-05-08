import { useEffect, useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { makeRequest } from '../../api/client'
import { moderationRulesApi } from '../../api/moderationRules'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { Modal } from '../../components/Modal/Modal'
import styles from './ModerationRules.module.css'

const RULE_TYPES = ['KEYWORD', 'REGEX', 'LENGTH']
const SEVERITIES = ['LOW', 'MEDIUM', 'HIGH']
const ACTIONS = ['FLAG', 'WARN', 'MUTE', 'BAN', 'DELETE', 'ESCALATE']

const PATTERN_HINT = {
  KEYWORD: 'Case-insensitive substring match — e.g. spam',
  REGEX:   'Case-insensitive regex — e.g. buy\\s+now',
  LENGTH:  'Maximum message length in characters — e.g. 1000',
}

const SEVERITY_VARIANT = { LOW: 'gray', MEDIUM: 'yellow', HIGH: 'red' }
const ACTION_VARIANT   = { FLAG: 'blue', WARN: 'yellow', MUTE: 'yellow', BAN: 'red', DELETE: 'red', ESCALATE: 'gray' }

function RuleModal({ rule, onClose, onSave }) {
  const [form, setForm] = useState({
    name:     rule?.name     ?? '',
    ruleType: rule?.ruleType ?? 'KEYWORD',
    pattern:  rule?.pattern  ?? '',
    severity: rule?.severity ?? 'MEDIUM',
    action:   rule?.action   ?? 'FLAG',
    enabled:  rule?.enabled  ?? true,
  })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  return (
    <Modal title={rule ? 'Edit Rule' : 'Create Rule'} onClose={onClose} onSubmit={() => onSave(form)}>
      <label>Rule Name *</label>
      <input type="text" value={form.name} onChange={e => set('name', e.target.value)}
        className={styles.input} required disabled={!!rule} />

      <label>Rule Type</label>
      <select value={form.ruleType} onChange={e => set('ruleType', e.target.value)} className={styles.input}>
        {RULE_TYPES.map(t => <option key={t}>{t}</option>)}
      </select>

      <label>Pattern *</label>
      <input type={form.ruleType === 'LENGTH' ? 'number' : 'text'}
        value={form.pattern} onChange={e => set('pattern', e.target.value)}
        className={styles.input} required
        placeholder={PATTERN_HINT[form.ruleType]} />
      <p className={styles.hint}>{PATTERN_HINT[form.ruleType]}</p>

      <label>Severity</label>
      <select value={form.severity} onChange={e => set('severity', e.target.value)} className={styles.input}>
        {SEVERITIES.map(s => <option key={s}>{s}</option>)}
      </select>

      <label>Action</label>
      <select value={form.action} onChange={e => set('action', e.target.value)} className={styles.input}>
        {ACTIONS.map(a => <option key={a}>{a}</option>)}
      </select>

      {rule && (
        <>
          <label>Enabled</label>
          <select value={form.enabled ? 'true' : 'false'}
            onChange={e => set('enabled', e.target.value === 'true')} className={styles.input}>
            <option value="true">Yes</option>
            <option value="false">No</option>
          </select>
        </>
      )}
    </Modal>
  )
}

export function ModerationRules() {
  const { token } = useAuth()
  const api = moderationRulesApi(makeRequest(token))
  const [rules, setRules]   = useState([])
  const [modal, setModal]   = useState(null)
  const [error, setError]   = useState('')

  const load = () => api.list().then(setRules).catch(e => setError(e.message))
  useEffect(() => { load() }, [])

  const save = async form => {
    try {
      if (modal === 'add') await api.create(form)
      else await api.update(modal.id, form)
      setModal(null)
      load()
    } catch (e) { setError(e.message) }
  }

  const remove = async rule => {
    if (!confirm(`Delete rule "${rule.name}"?`)) return
    try { await api.remove(rule.id); load() }
    catch (e) { setError(e.message) }
  }

  return (
    <div>
      <div className={styles.header}>
        <h2>Moderation Rules</h2>
        <Button onClick={() => setModal('add')}>+ Create Rule</Button>
      </div>
      {error && <p className={styles.error} role="alert">{error}</p>}
      <table className={styles.table}>
        <thead>
          <tr>
            <th>Rule Name</th>
            <th>Type</th>
            <th>Pattern</th>
            <th>Severity</th>
            <th>Action</th>
            <th>Enabled</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {rules.map(r => (
            <tr key={r.id}>
              <td>{r.name}</td>
              <td><Badge variant="gray">{r.ruleType}</Badge></td>
              <td className={styles.pattern} title={r.pattern}>{r.pattern}</td>
              <td><Badge variant={SEVERITY_VARIANT[r.severity] ?? 'gray'}>{r.severity}</Badge></td>
              <td><Badge variant={ACTION_VARIANT[r.action] ?? 'gray'}>{r.action}</Badge></td>
              <td>{r.enabled ? '✓' : '—'}</td>
              <td className={styles.actions}>
                <Button variant="secondary" onClick={() => setModal(r)}>Edit</Button>
                <Button variant="danger" onClick={() => remove(r)}>Delete</Button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {modal && (
        <RuleModal
          rule={modal === 'add' ? null : modal}
          onClose={() => setModal(null)}
          onSave={save}
        />
      )}
    </div>
  )
}
