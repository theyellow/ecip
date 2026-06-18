import { useEffect, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { groupsApi } from '../../api/groups'
import { tenantsApi } from '../../api/tenants'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { DataTable } from '../../components/DataTable/DataTable'
import { Modal } from '../../components/Modal/Modal'
import { SectionLabel } from '../../components/SectionLabel/SectionLabel'
import { BackfillModal } from './BackfillModal'
import styles from './Groups.module.css'

const LEVELS = ['LOW', 'MEDIUM', 'HIGH', 'STRICT']
const LEVEL_VARIANT = { LOW: 'green', MEDIUM: 'blue', HIGH: 'yellow', STRICT: 'red' }

const BASE_COLUMNS = [
  { key: 'name', label: 'Group' },
  { key: 'telegramChatId', label: 'Chat ID', mono: true, width: 180 },
  { key: 'moderationLevel', label: 'Mod', width: 100, render: v => <Badge variant={LEVEL_VARIANT[v] ?? 'gray'}>{v}</Badge> },
  { key: 'autoRespond', label: 'Auto-respond', width: 120, render: v => <Badge variant={v ? 'green' : 'gray'}>{v ? 'YES' : 'NO'}</Badge> },
  { key: 'knowledgeForkEnabled', label: 'Knowledge Fork', width: 130, render: v => <Badge variant={v ? 'green' : 'gray'}>{v ? 'YES' : 'NO'}</Badge> },
  { key: 'description', label: 'Description', render: v => v || '\u2014' },
]

function GroupEditModal({ group, onClose, onSave, tenants, api }) {
  const isNew = !group
  const [watchers, setWatchers] = useState([])
  useEffect(() => {
    if (group?.telegramChatId) {
      api.watchers(group.telegramChatId).then(setWatchers).catch(() => {})
    }
  }, [group?.telegramChatId])
  const [form, setForm] = useState({
    telegramChatId: group?.telegramChatId ?? '',
    name: group?.name ?? '',
    description: group?.description ?? '',
    moderationLevel: group?.moderationLevel ?? 'LOW',
    autoRespond: group?.autoRespond ?? false,
    knowledgeForkEnabled: group?.knowledgeForkEnabled ?? false,
    welcomeMessage: group?.welcomeMessage ?? '',
    tenantId: group?.tenantId ?? '',
  })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  return (
    <Modal title={isNew ? 'Add Group' : `Edit \u00b7 ${group.name}`} onClose={onClose} onSubmit={() => onSave(form)}>
      {!isNew && (
        <>
          <SectionLabel>Details</SectionLabel>
          <div className={styles.metaGrid}>
            <span className={styles.metaLabel}>Chat ID</span>
            <span className={styles.metaValue}>{group.telegramChatId}</span>
            <span className={styles.metaLabel}>Auto-respond</span>
            <span className={styles.metaValue}>{group.autoRespond ? 'Yes' : 'No'}</span>
            {group.tenantId && <>
              <span className={styles.metaLabel}>Tenant</span>
              <span className={styles.metaValue}>{group.tenantId}</span>
            </>}
            {group.createdAt && <>
              <span className={styles.metaLabel}>Created</span>
              <span className={styles.metaValue}>{new Date(group.createdAt).toLocaleString()}</span>
            </>}
            {group.rulesEnabled && <>
              <span className={styles.metaLabel}>Rules</span>
              <span className={styles.metaValue}>{group.rulesEnabled}</span>
            </>}
            {watchers.length > 0 && <>
              <span className={styles.metaLabel}>Watched by</span>
              <span className={styles.metaValue}>
                {watchers.map(w => w.displayName || w.phoneNumber).join(', ')}
              </span>
            </>}
          </div>
        </>
      )}

      {isNew && (
        <div className={styles.field}>
          <label>Telegram Chat ID</label>
          <input type="number" className={styles.input} value={form.telegramChatId}
            onChange={e => set('telegramChatId', parseInt(e.target.value, 10))} required />
        </div>
      )}

      <div className={styles.field}>
        <label>Name</label>
        <input type="text" className={styles.input} value={form.name}
          onChange={e => set('name', e.target.value)} required />
      </div>

      <div className={styles.field}>
        <label>Description</label>
        <input type="text" className={styles.input} value={form.description}
          onChange={e => set('description', e.target.value)} />
      </div>

      <div className={styles.field}>
        <label>Moderation Level</label>
        <select className={styles.input} value={form.moderationLevel}
          onChange={e => set('moderationLevel', e.target.value)}>
          {LEVELS.map(l => <option key={l}>{l}</option>)}
        </select>
      </div>

      <div className={styles.checkboxRow}>
        <input type="checkbox" checked={form.autoRespond}
          onChange={e => set('autoRespond', e.target.checked)} />
        Auto-respond
      </div>

      <div className={styles.checkboxRow}>
        <input type="checkbox" checked={form.knowledgeForkEnabled}
          onChange={e => set('knowledgeForkEnabled', e.target.checked)} />
        Knowledge Fork
      </div>

      <div className={styles.field}>
        <label>Welcome Message</label>
        <textarea className={styles.input} value={form.welcomeMessage}
          onChange={e => set('welcomeMessage', e.target.value)} rows={3} />
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

export function Groups() {
  const authRequest = useAuthRequest()
  const api = groupsApi(authRequest)
  const [groups, setGroups] = useState([])
  const [modal, setModal] = useState(null)
  const [backfillGroup, setBackfillGroup] = useState(null)
  const [error, setError] = useState('')
  const [tenants, setTenants] = useState([])
  const [levelFilter, setLevelFilter] = useState('')

  const COLUMNS = [
    ...BASE_COLUMNS,
    {
      key: '_backfill',
      label: '',
      width: 80,
      render: (_, row) => (
        <Button
          variant="secondary"
          onClick={e => { e.stopPropagation(); setBackfillGroup(row) }}
          style={{ fontSize: '10px', padding: '3px 8px' }}
        >
          &#x25B6; Backfill
        </Button>
      ),
    },
  ]

  const load = () => api.list().then(setGroups).catch(e => setError(e.message))
  useEffect(() => { load() }, [])
  useEffect(() => { tenantsApi(authRequest).list().then(setTenants).catch(() => {}) }, [])

  const filtered = groups.filter(g => !levelFilter || g.moderationLevel === levelFilter)

  const save = async form => {
    try {
      if (modal === 'add') await api.create(form)
      else await api.update(modal.telegramChatId, form)
      setModal(null)
      load()
    } catch (e) { setError(e.message) }
  }

  const remove = async group => {
    try { await api.remove(group.telegramChatId); load() }
    catch (e) { setError(e.message) }
  }

  return (
    <>
      {error && <p style={{ color: 'var(--signal-stop-fg)', background: 'rgba(248,113,113,0.08)', border: '1px solid rgba(248,113,113,0.25)', padding: '8px 12px', fontFamily: 'var(--font-mono)', fontSize: '12px', marginBottom: 'var(--sp-3)' }} role="alert">{error}</p>}

      <DataTable
        title="Groups"
        systemId={`\u25C8 groups \u00b7 ${groups.length} watched`}
        addLabel="+ Add Group"
        onAdd={() => setModal('add')}
        columns={COLUMNS}
        rows={filtered}
        rowKey={r => r.telegramChatId ?? r.id}
        onEdit={setModal}
        onDelete={remove}
        deleteMessage={g => `Stop watching "${g.name}"? This cannot be undone.`}
        filters={[{
          value: levelFilter,
          onChange: e => setLevelFilter(e.target.value),
          options: [
            { value: '', label: 'All moderation levels' },
            ...LEVELS.map(l => ({ value: l, label: l })),
          ],
        }]}
        emptyText="No groups match this filter"
      />

      {modal && (
        <GroupEditModal
          group={modal === 'add' ? null : modal}
          onClose={() => setModal(null)}
          onSave={save}
          tenants={tenants}
          api={api}
        />
      )}

      {backfillGroup && (
        <BackfillModal
          group={backfillGroup}
          onClose={() => setBackfillGroup(null)}
          api={api}
        />
      )}
    </>
  )
}
