import { useEffect, useState } from 'react'
import { useAuth, useAuthRequest } from '../../auth/AuthContext'
import { intentRulesApi } from '../../api/intentRules'
import { policyRulesApi } from '../../api/policyRules'
import { tenantsApi } from '../../api/tenants'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { DataTable } from '../../components/DataTable/DataTable'
import { Modal } from '../../components/Modal/Modal'
import { ConditionGroupBuilder } from './ConditionGroupBuilder'
import { DryRunPanel } from './DryRunPanel'
import { RuleHistoryTab } from './RuleHistoryTab'
import styles from './PolicyRules.module.css'

const ACTIONS = ['ALLOW', 'FLAG', 'BLOCK', 'RESPOND', 'ESCALATE', 'EXECUTE', 'REVIEW']
const ACTION_VARIANT = { ALLOW: 'green', FLAG: 'blue', BLOCK: 'red', RESPOND: 'gray', ESCALATE: 'yellow', EXECUTE: 'red', REVIEW: 'yellow' }
const LIVE_EFFECT_ACTIONS = new Set(['RESPOND', 'EXECUTE', 'BLOCK'])

const TAB_EDIT = 'edit'
const TAB_HISTORY = 'history'

const WILDCARD = '*'
const CUSTOM_SENTINEL = '__custom__'

function IntentSelect({ value, onChange, knownIntents }) {
  const isCustom = value !== WILDCARD && value !== '' && !knownIntents.includes(value)
  const [custom, setCustom] = useState(isCustom ? value : '')
  const selectValue = isCustom ? CUSTOM_SENTINEL : value

  const handleSelect = e => {
    const v = e.target.value
    if (v === CUSTOM_SENTINEL) {
      onChange(custom)
    } else {
      setCustom('')
      onChange(v)
    }
  }

  const handleCustom = e => {
    setCustom(e.target.value)
    onChange(e.target.value)
  }

  return (
    <>
      <select className={styles.input} value={selectValue} onChange={handleSelect}>
        <option value="">— Any intent —</option>
        <option value={WILDCARD}>* (wildcard)</option>
        {knownIntents.map(i => (
          <option key={i} value={i}>{i}</option>
        ))}
        <option value={CUSTOM_SENTINEL}>Custom…</option>
      </select>
      {(selectValue === CUSTOM_SENTINEL || isCustom) && (
        <input
          type="text"
          className={styles.input}
          value={custom}
          onChange={handleCustom}
          placeholder="Enter custom intent"
          style={{ marginTop: 6 }}
        />
      )}
    </>
  )
}

function RuleModal({ rule, onClose, onSave, tenants, knownIntents, api }) {
  const [tab, setTab] = useState(TAB_EDIT)
  const [form, setForm] = useState({
    name: rule?.name ?? '',
    targetIntent: rule?.targetIntent ?? '',
    action: rule?.action ?? 'FLAG',
    priority: rule?.priority ?? 0,
    description: rule?.description ?? '',
    effectiveFrom: rule?.effectiveFrom?.slice(0, 16) ?? '',
    effectiveTo: rule?.effectiveTo?.slice(0, 16) ?? '',
    tenantId: rule?.tenantId ?? '',
    conditions: rule?.conditions ?? { groups: [] },
  })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))
  const isExisting = !!rule?.id

  const currentRuleForDryRun = {
    ...form,
    effectiveFrom: form.effectiveFrom ? new Date(form.effectiveFrom).toISOString() : null,
    effectiveTo: form.effectiveTo ? new Date(form.effectiveTo).toISOString() : null,
    tenantId: form.tenantId || null,
    minConfidence: rule?.minConfidence ?? 0,
    maxConfidence: rule?.maxConfidence ?? null,
  }

  return (
    <Modal
      title={isExisting ? 'Edit Rule' : 'Create Rule'}
      onClose={onClose}
      onSubmit={tab === TAB_EDIT ? () => onSave(form) : undefined}
    >
      {isExisting && (
        <div className={styles.tabs}>
          <button className={`${styles.tab} ${tab === TAB_EDIT ? styles.tabActive : ''}`}
            onClick={() => setTab(TAB_EDIT)}>Edit Rule</button>
          <button className={`${styles.tab} ${tab === TAB_HISTORY ? styles.tabActive : ''}`}
            onClick={() => setTab(TAB_HISTORY)}>History</button>
        </div>
      )}

      {tab === TAB_EDIT && (
        <>
          <div className={styles.field}>
            <label>Rule Name *</label>
            <input type="text" className={styles.input} value={form.name}
              onChange={e => set('name', e.target.value)} required disabled={isExisting} />
          </div>
          <div className={styles.field}>
            <label>Target Intent</label>
            <IntentSelect
              value={form.targetIntent}
              onChange={v => set('targetIntent', v)}
              knownIntents={knownIntents}
            />
          </div>
          <div className={styles.field}>
            <label>Action</label>
            <select className={styles.input} value={form.action}
              onChange={e => set('action', e.target.value)}>
              {ACTIONS.map(a => <option key={a}>{a}</option>)}
            </select>
            {LIVE_EFFECT_ACTIONS.has(form.action) && (
              <div className={styles.actionWarn} role="alert">
                This action will take effect in Telegram. Use FLAG or REVIEW for safe observation-only rules.
              </div>
            )}
          </div>
          <div className={styles.field}>
            <label>Priority</label>
            <input type="number" className={styles.input} value={form.priority}
              onChange={e => set('priority', parseInt(e.target.value) || 0)} min={0} />
          </div>
          <div className={styles.field}>
            <label>Description</label>
            <textarea className={styles.input} value={form.description}
              onChange={e => set('description', e.target.value)} rows={3} placeholder="Optional" />
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
          <div className={styles.field}>
            <label>Conditions</label>
            <ConditionGroupBuilder
              groups={form.conditions?.groups ?? []}
              onChange={groups => set('conditions', { groups })}
            />
          </div>
          <DryRunPanel rule={currentRuleForDryRun} api={api} />
        </>
      )}

      {tab === TAB_HISTORY && (
        <RuleHistoryTab ruleId={rule?.id} api={api} />
      )}
    </Modal>
  )
}

export function PolicyRules() {
  const authRequest = useAuthRequest()
  const { currentTenant } = useAuth()
  const api = policyRulesApi(authRequest)
  const [rules, setRules] = useState([])
  const [modal, setModal] = useState(null)
  const [error, setError] = useState('')
  const [tenants, setTenants] = useState([])
  const [knownIntents, setKnownIntents] = useState([])

  const load = () => api.list().then(setRules).catch(e => setError(e.message))
  useEffect(() => { load() }, [])
  useEffect(() => { tenantsApi(authRequest).list().then(setTenants).catch(() => {}) }, [])
  useEffect(() => {
    intentRulesApi(authRequest)
      .list()
      .then(rules => {
        const intents = [...new Set(rules.map(r => r.intent).filter(Boolean))].sort()
        setKnownIntents(intents)
      })
      .catch(() => {})
  }, [])

  const save = async form => {
    try {
      const payload = {
        ...form,
        tenantId: form.tenantId || null,
        effectiveFrom: form.effectiveFrom ? new Date(form.effectiveFrom).toISOString() : null,
        effectiveTo: form.effectiveTo ? new Date(form.effectiveTo).toISOString() : null,
      }
      if (modal === 'add') await api.create(payload)
      else await api.update(modal.id, payload)
      setModal(null); load()
    } catch (e) { setError(e.message) }
  }

  const remove = async rule => {
    try { await api.remove(rule.id); load() }
    catch (e) { setError(e.message) }
  }

  const columns = [
    { key: 'name', label: 'Rule Name' },
    { key: 'targetIntent', label: 'Intent', render: v => <Badge variant="gray">{v}</Badge> },
    { key: 'action', label: 'Action', width: 110, render: v => <Badge variant={ACTION_VARIANT[v] ?? 'gray'}>{v}</Badge> },
    { key: 'priority', label: 'Priority', mono: true, width: 80 },
    { key: 'effectiveFrom', label: 'From', mono: true, width: 110, render: v => v ? new Date(v).toLocaleDateString() : '\u2014' },
    { key: 'effectiveTo', label: 'To', mono: true, width: 110, render: v => v ? new Date(v).toLocaleDateString() : '\u2014' },
  ]

  return (
    <>
      {error && <p className={styles.errorMsg} role="alert">{error}</p>}
      <DataTable
        title="Policy Rules"
        systemId={`\u2696 policy-rules \u00b7 ${rules.length} rules`}
        addLabel="+ Create Rule"
        onAdd={() => setModal('add')}
        columns={columns}
        rows={rules}
        onEdit={setModal}
        onDelete={remove}
        deleteMessage={r => `Delete rule "${r.name}"? This cannot be undone.`}
        emptyText="No policy rules defined"
      />
      {modal && (
        <RuleModal
          rule={modal === 'add' ? { tenantId: currentTenant?.id ?? null } : modal}
          onClose={() => setModal(null)}
          onSave={save}
          tenants={tenants}
          knownIntents={knownIntents}
          api={api}
        />
      )}
    </>
  )
}
