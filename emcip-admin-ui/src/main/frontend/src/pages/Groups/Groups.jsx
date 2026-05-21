import { useEffect, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { groupsApi } from '../../api/groups'
import { tenantsApi } from '../../api/tenants'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { Modal } from '../../components/Modal/Modal'
import styles from './Groups.module.css'

const LEVEL_VARIANT = { LOW: 'green', MEDIUM: 'blue', HIGH: 'yellow', STRICT: 'red' }

function GroupModal({ group, onClose, onSave, tenants }) {
  const [form, setForm] = useState({
    telegramChatId: group?.telegramChatId ?? '',
    name: group?.name ?? '',
    description: group?.description ?? '',
    moderationLevel: group?.moderationLevel ?? 'LOW',
    autoRespond: group?.autoRespond ?? false,
    welcomeMessage: group?.welcomeMessage ?? '',
    tenantId: group?.tenantId ?? '',
  })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  return (
    <Modal title={group ? 'Edit Group' : 'Add Group'} onClose={onClose} onSubmit={() => onSave(form)}>
      {!group && (
        <>
          <label>Telegram Chat ID *</label>
          <input type="number" value={form.telegramChatId}
            onChange={e => set('telegramChatId', parseInt(e.target.value, 10))}
            className={styles.input} required />
        </>
      )}
      <label>Name *</label>
      <input type="text" value={form.name} onChange={e => set('name', e.target.value)}
        className={styles.input} required />
      <label>Description</label>
      <input type="text" value={form.description}
        onChange={e => set('description', e.target.value)} className={styles.input} />
      <label>Moderation Level</label>
      <select value={form.moderationLevel}
        onChange={e => set('moderationLevel', e.target.value)} className={styles.input}>
        {['LOW', 'MEDIUM', 'HIGH', 'STRICT'].map(l => <option key={l}>{l}</option>)}
      </select>
      <label>
        <input type="checkbox" checked={form.autoRespond}
          onChange={e => set('autoRespond', e.target.checked)} /> Auto-respond
      </label>
      <label>Welcome Message</label>
      <textarea value={form.welcomeMessage}
        onChange={e => set('welcomeMessage', e.target.value)} className={styles.input} rows={3} />
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

export function Groups() {
  const authRequest = useAuthRequest()
  const api = groupsApi(authRequest)
  const [groups, setGroups] = useState([])
  const [modal, setModal] = useState(null)
  const [error, setError] = useState('')
  const [tenants, setTenants] = useState([])

  const load = () => api.list().then(setGroups).catch(e => setError(e.message))
  useEffect(() => { load() }, [])

  useEffect(() => {
    tenantsApi(authRequest).list().then(setTenants).catch(() => {})
  }, [])

  const save = async form => {
    try {
      if (modal !== 'add') await api.update(modal.telegramChatId, form)
      else await api.create(form)
      setModal(null); load()
    } catch (e) { setError(e.message) }
  }

  const remove = async group => {
    if (!confirm(`Delete group "${group.name}"?`)) return
    try { await api.remove(group.telegramChatId); load() }
    catch (e) { setError(e.message) }
  }

  return (
    <div>
      <div className={styles.header}>
        <h2>Groups</h2>
        <Button onClick={() => setModal('add')}>+ Add Group</Button>
      </div>
      {error && <p className={styles.error} role="alert">{error}</p>}
      <table className={styles.table}>
        <thead>
          <tr><th>Chat ID</th><th>Name</th><th>Moderation</th><th>Auto-respond</th><th>Description</th><th></th></tr>
        </thead>
        <tbody>
          {groups.map(g => (
            <tr key={g.telegramChatId}>
              <td className={styles.mono}>{g.telegramChatId}</td>
              <td>{g.name}</td>
              <td><Badge variant={LEVEL_VARIANT[g.moderationLevel] ?? 'gray'}>{g.moderationLevel}</Badge></td>
              <td><Badge variant={g.autoRespond ? 'green' : 'red'}>{g.autoRespond ? 'Yes' : 'No'}</Badge></td>
              <td className={styles.desc}>{g.description ?? '\u2014'}</td>
              <td className={styles.actions}>
                <Button variant="secondary" onClick={() => setModal(g)}>Edit</Button>
                <Button variant="danger" onClick={() => remove(g)}>Delete</Button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {modal && <GroupModal group={modal === 'add' ? null : modal} onClose={() => setModal(null)} onSave={save} tenants={tenants} />}
    </div>
  )
}
