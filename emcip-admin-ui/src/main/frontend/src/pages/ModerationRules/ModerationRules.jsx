import { useEffect, useState } from 'react'
import { useAuth, useAuthRequest } from '../../auth/AuthContext'
import { moderationRulesApi } from '../../api/moderationRules'
import { Badge } from '../../components/Badge/Badge'
import { DataTable } from '../../components/DataTable/DataTable'
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

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'tenantId', label: 'Tenant', mono: true, width: 100, render: v => v ? v.slice(0, 8) + '\u2026' : '\u2014' },
  { key: 'ruleType', label: 'Type', render: v => <Badge variant="gray">{v}</Badge> },
  { key: 'pattern', label: 'Pattern', mono: true, render: (v, row) => <span className={styles.pattern} title={v}>{v}</span> },
  { key: 'severity', label: 'Severity', render: v => <Badge variant={SEVERITY_VARIANT[v] ?? 'gray'}>{v}</Badge> },
  { key: 'action', label: 'Action', width: 110, render: v => <Badge variant={ACTION_VARIANT[v] ?? 'gray'}>{v}</Badge> },
  { key: 'enabled', label: 'Enabled', width: 80, render: v => v ? <Badge variant="green">ON</Badge> : <Badge variant="gray">OFF</Badge> },
]

function RuleModal({ rule, onClose, onSave, currentTenant }) {
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
      <div className={styles.field}>
        <label>Rule Name *</label>
        <input type="text" className={styles.input} value={form.name}
          onChange={e => set('name', e.target.value)} required disabled={!!rule} />
      </div>
      <div className={styles.field}>
        <label>Rule Type</label>
        <select className={styles.input} value={form.ruleType}
          onChange={e => set('ruleType', e.target.value)}>
          {RULE_TYPES.map(t => <option key={t}>{t}</option>)}
        </select>
      </div>
      <div className={styles.field}>
        <label>Pattern *</label>
        <input type={form.ruleType === 'LENGTH' ? 'number' : 'text'}
          className={styles.input} value={form.pattern}
          onChange={e => set('pattern', e.target.value)} required
          placeholder={PATTERN_HINT[form.ruleType]} />
        <p className={styles.hint}>{PATTERN_HINT[form.ruleType]}</p>
      </div>
      <div className={styles.field}>
        <label>Severity</label>
        <select className={styles.input} value={form.severity}
          onChange={e => set('severity', e.target.value)}>
          {SEVERITIES.map(s => <option key={s}>{s}</option>)}
        </select>
      </div>
      <div className={styles.field}>
        <label>Action</label>
        <select className={styles.input} value={form.action}
          onChange={e => set('action', e.target.value)}>
          {ACTIONS.map(a => <option key={a}>{a}</option>)}
        </select>
      </div>
      {rule && (
        <div className={styles.field}>
          <label>Enabled</label>
          <select className={styles.input} value={form.enabled ? 'true' : 'false'}
            onChange={e => set('enabled', e.target.value === 'true')}>
            <option value="true">Yes</option>
            <option value="false">No</option>
          </select>
        </div>
      )}
      <div className={styles.field}>
        <label>Tenant</label>
        <p className={styles.hint}>
          {rule?.tenantId
            ? rule.tenantId.slice(0, 8) + '\u2026'
            : currentTenant
              ? currentTenant.name
              : 'All tenants \u2014 select a tenant in the sidebar to scope this rule'}
        </p>
      </div>
    </Modal>
  )
}

export function ModerationRules() {
  const { currentTenant } = useAuth()
  const api = moderationRulesApi(useAuthRequest())
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
    if (!confirm(`Delete rule "${rule.name}"?`)) return
    try { await api.remove(rule.id); load() }
    catch (e) { setError(e.message) }
  }

  return (
    <>
      {error && <p style={{ color: 'var(--signal-stop-fg)', background: 'rgba(248,113,113,0.08)', border: '1px solid rgba(248,113,113,0.25)', padding: '8px 12px', fontFamily: 'var(--font-mono)', fontSize: '12px', marginBottom: 'var(--sp-3)' }} role="alert">{error}</p>}

      <DataTable
        title="Moderation Rules"
        systemId={`\u2298 moderation-service \u00b7 ${rules.length} rules`}
        addLabel="+ Create Rule"
        onAdd={() => setModal('add')}
        columns={COLUMNS}
        rows={rules}
        onEdit={setModal}
        onDelete={remove}
        emptyText="No moderation rules defined"
      />

      {modal && (
        <RuleModal
          rule={modal === 'add' ? null : modal}
          onClose={() => setModal(null)}
          onSave={save}
          currentTenant={currentTenant}
        />
      )}
    </>
  )
}
