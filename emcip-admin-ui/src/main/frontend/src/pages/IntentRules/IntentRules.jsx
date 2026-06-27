import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth, useAuthRequest } from '../../auth/AuthContext'
import { intentRulesApi } from '../../api/intentRules'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { DataTable } from '../../components/DataTable/DataTable'
import { Modal } from '../../components/Modal/Modal'
import styles from './IntentRules.module.css'

const MATCH_MODES = ['KEYWORD', 'REGEX']

const PATTERN_HINT = {
  KEYWORD: 'Pipe-separated keywords, e.g. hello|hi|hey',
  REGEX:   'Java regex, e.g. (?i)click\\s+here',
}

const INTENT_VARIANT = {
  GREETING: 'blue',
  QUESTION: 'gray',
  COMMAND:  'blue',
  THANKS:   'green',
  GOODBYE:  'gray',
  SPAM:     'red',
}

const MATCH_MODE_VARIANT = {
  KEYWORD: 'gray',
  REGEX:   'yellow',
}

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'intent', label: 'Intent', render: v => <Badge variant={INTENT_VARIANT[v] ?? 'gray'}>{v}</Badge> },
  { key: 'matchMode', label: 'Match Mode', width: 120, render: v => <Badge variant={MATCH_MODE_VARIANT[v] ?? 'gray'}>{v}</Badge> },
  { key: 'pattern', label: 'Pattern', mono: true, render: (v) => <span className={styles.pattern} title={v}>{v}</span> },
  { key: 'confidence', label: 'Confidence', width: 110, render: v => (v ?? 0).toFixed(2) },
  { key: 'priority', label: 'Priority', width: 90 },
  { key: 'active', label: 'Active', width: 80, render: v => v ? <Badge variant="green">ON</Badge> : <Badge variant="gray">OFF</Badge> },
]

function RuleModal({ rule, onClose, onSave }) {
  const [form, setForm] = useState({
    name:        rule?.name        ?? '',
    description: rule?.description ?? '',
    intent:      rule?.intent      ?? '',
    matchMode:   rule?.matchMode   ?? 'KEYWORD',
    pattern:     rule?.pattern     ?? '',
    confidence:  rule?.confidence  ?? 0.5,
    priority:    rule?.priority    ?? 100,
    active:      rule?.active      ?? true,
  })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  return (
    <Modal title={rule ? 'Edit Rule' : 'Create Rule'} onClose={onClose} onSubmit={() => onSave(form)}>
      <div className={styles.field}>
        <label>Rule Name *</label>
        <input type="text" className={styles.input} value={form.name}
          onChange={e => set('name', e.target.value)} required />
      </div>
      <div className={styles.field}>
        <label>Description</label>
        <textarea className={styles.input} value={form.description}
          onChange={e => set('description', e.target.value)} rows={3} />
      </div>
      <div className={styles.field}>
        <label>Intent</label>
        <input type="text" className={styles.input} value={form.intent}
          onChange={e => set('intent', e.target.value)}
          placeholder="GREETING / SPAM / custom" />
      </div>
      <div className={styles.field}>
        <label>Match Mode</label>
        <select className={styles.input} value={form.matchMode}
          onChange={e => set('matchMode', e.target.value)}>
          {MATCH_MODES.map(m => <option key={m}>{m}</option>)}
        </select>
      </div>
      <div className={styles.field}>
        <label>Pattern *</label>
        <input type="text" className={styles.input} value={form.pattern}
          onChange={e => set('pattern', e.target.value)} required
          placeholder={PATTERN_HINT[form.matchMode]} />
        <p className={styles.hint}>{PATTERN_HINT[form.matchMode]}</p>
      </div>
      <div className={styles.field}>
        <label>Confidence (0.00 – 1.00)</label>
        <input type="number" className={styles.input} value={form.confidence}
          onChange={e => set('confidence', parseFloat(e.target.value))}
          min={0} max={1} step={0.05} />
      </div>
      <div className={styles.field}>
        <label>Priority</label>
        <input type="number" className={styles.input} value={form.priority}
          onChange={e => set('priority', parseInt(e.target.value, 10))} />
      </div>
      <div className={styles.field}>
        <label>Active</label>
        <select className={styles.input} value={form.active ? 'true' : 'false'}
          onChange={e => set('active', e.target.value === 'true')}>
          <option value="true">Yes</option>
          <option value="false">No</option>
        </select>
      </div>
    </Modal>
  )
}

export function IntentRules() {
  const { currentTenant } = useAuth()
  const api = intentRulesApi(useAuthRequest())
  const navigate = useNavigate()
  const [rules, setRules] = useState([])
  const [modal, setModal] = useState(null)
  const [error, setError] = useState('')

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
    try { await api.remove(rule.id); load() }
    catch (e) { setError(e.message) }
  }

  return (
    <>
      {error && <p style={{ color: 'var(--signal-stop-fg)', background: 'rgba(248,113,113,0.08)', border: '1px solid rgba(248,113,113,0.25)', padding: '8px 12px', fontFamily: 'var(--font-mono)', fontSize: '12px', marginBottom: 'var(--sp-3)' }} role="alert">{error}</p>}

      <div className={styles.signalConfigRow}>
        <Button variant="secondary" onClick={() => navigate('/intent-signal-config')}>Signal Config →</Button>
      </div>

      <DataTable
        title="Intent Rules"
        systemId={`\u2726 intent-classifier \u00b7 ${rules.length} rules`}
        addLabel="+ Create Rule"
        onAdd={() => setModal('add')}
        columns={COLUMNS}
        rows={rules}
        onEdit={setModal}
        onDelete={remove}
        deleteMessage={r => `Delete rule "${r.name}"? This cannot be undone.`}
        emptyText="No intent rules defined"
      />

      {modal && (
        <RuleModal
          rule={modal === 'add' ? null : modal}
          onClose={() => setModal(null)}
          onSave={save}
        />
      )}
    </>
  )
}
