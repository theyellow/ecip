import styles from './ConditionGroupBuilder.module.css'

const CONDITION_TYPES = [
  { value: 'TIME_WINDOW',       label: 'Time window' },
  { value: 'MIN_THREAD_LENGTH', label: 'Min thread length' },
  { value: 'ACCOUNT_AGE_DAYS',  label: 'Account age (days)' },
  { value: 'MESSAGE_LANGUAGE',  label: 'Message language' },
  { value: 'GROUP_SIZE',        label: 'Group size' },
  { value: 'MESSAGE_LENGTH',    label: 'Message length' },
  { value: 'FLAGGED_COUNT',     label: 'Flagged count' },
]

function defaultParams(type) {
  switch (type) {
    case 'TIME_WINDOW':       return { start: '22:00', end: '06:00' }
    case 'MIN_THREAD_LENGTH': return { min: 3 }
    case 'ACCOUNT_AGE_DAYS':  return { max: 7 }
    case 'MESSAGE_LANGUAGE':  return { languages: 'en', mode: 'INCLUDE' }
    case 'GROUP_SIZE':        return { min: 50 }
    case 'MESSAGE_LENGTH':    return { min: '', max: '' }
    case 'FLAGGED_COUNT':     return { min: 3, windowDays: 30 }
    default:                  return {}
  }
}

function ConditionParams({ type, params, onChange }) {
  const set = (k, v) => onChange({ ...params, [k]: v })
  switch (type) {
    case 'TIME_WINDOW': return <>
      <span className={styles.paramLabel}>from</span>
      <input className={styles.paramInput} value={params.start ?? ''} onChange={e => set('start', e.target.value)} placeholder="22:00" />
      <span className={styles.paramLabel}>to</span>
      <input className={styles.paramInput} value={params.end ?? ''} onChange={e => set('end', e.target.value)} placeholder="06:00" />
    </>
    case 'MIN_THREAD_LENGTH': return <>
      <span className={styles.paramLabel}>min</span>
      <input type="number" className={styles.paramInput} value={params.min ?? ''} onChange={e => set('min', parseInt(e.target.value) || 0)} />
    </>
    case 'ACCOUNT_AGE_DAYS': return <>
      <span className={styles.paramLabel}>max days</span>
      <input type="number" className={styles.paramInput} value={params.max ?? ''} onChange={e => set('max', parseInt(e.target.value) || 0)} />
    </>
    case 'MESSAGE_LANGUAGE': return <>
      <select className={styles.typeSelect} style={{minWidth:80}} value={params.mode ?? 'INCLUDE'} onChange={e => set('mode', e.target.value)}>
        <option>INCLUDE</option><option>EXCLUDE</option>
      </select>
      <input className={styles.paramInputWide} value={params.languages ?? ''} onChange={e => set('languages', e.target.value)} placeholder="en,de" />
    </>
    case 'GROUP_SIZE': return <>
      <span className={styles.paramLabel}>min members</span>
      <input type="number" className={styles.paramInput} value={params.min ?? ''} onChange={e => set('min', parseInt(e.target.value) || 0)} />
    </>
    case 'MESSAGE_LENGTH': return <>
      <span className={styles.paramLabel}>min chars</span>
      <input type="number" className={styles.paramInput} value={params.min ?? ''} onChange={e => set('min', e.target.value === '' ? undefined : parseInt(e.target.value))} placeholder="—" />
      <span className={styles.paramLabel}>max chars</span>
      <input type="number" className={styles.paramInput} value={params.max ?? ''} onChange={e => set('max', e.target.value === '' ? undefined : parseInt(e.target.value))} placeholder="—" />
    </>
    case 'FLAGGED_COUNT': return <>
      <span className={styles.paramLabel}>min</span>
      <input type="number" className={styles.paramInput} value={params.min ?? ''} onChange={e => set('min', parseInt(e.target.value) || 0)} />
      <span className={styles.paramLabel}>in last</span>
      <input type="number" className={styles.paramInput} value={params.windowDays ?? ''} onChange={e => set('windowDays', parseInt(e.target.value) || 30)} />
      <span className={styles.paramLabel}>days</span>
    </>
    default: return null
  }
}

export function ConditionGroupBuilder({ groups, onChange }) {
  const setGroups = g => onChange(g)

  const addGroup = () => setGroups([...groups, { conditions: [] }])
  const removeGroup = i => setGroups(groups.filter((_, idx) => idx !== i))

  const addCondition = i => {
    const updated = groups.map((g, idx) => idx !== i ? g : {
      ...g, conditions: [...g.conditions, { type: 'TIME_WINDOW', ...defaultParams('TIME_WINDOW') }]
    })
    setGroups(updated)
  }
  const removeCondition = (gi, ci) => {
    const updated = groups.map((g, idx) => idx !== gi ? g : {
      ...g, conditions: g.conditions.filter((_, cidx) => cidx !== ci)
    })
    setGroups(updated)
  }
  const updateConditionType = (gi, ci, newType) => {
    const updated = groups.map((g, idx) => idx !== gi ? g : {
      ...g, conditions: g.conditions.map((c, cidx) => cidx !== ci
        ? c : { type: newType, ...defaultParams(newType) })
    })
    setGroups(updated)
  }
  const updateConditionParams = (gi, ci, params) => {
    const updated = groups.map((g, idx) => idx !== gi ? g : {
      ...g, conditions: g.conditions.map((c, cidx) => cidx !== ci
        ? c : { type: c.type, ...params })
    })
    setGroups(updated)
  }

  return (
    <div className={styles.root}>
      <p className={styles.hint}>Groups are OR&apos;d together. Conditions within a group are AND&apos;d.</p>
      {groups.map((group, gi) => (
        <div key={gi}>
          {gi > 0 && <div className={styles.orLabel}>— OR —</div>}
          <div className={`${styles.group} ${gi === 0 ? styles.groupActive : ''}`}>
            <div className={styles.groupHeader}>
              <span className={gi === 0 ? styles.groupLabel : styles.groupLabelMuted}>
                Group {gi + 1} — AND
              </span>
              <button className={styles.removeGroup} onClick={() => removeGroup(gi)}>{'\u2715'} remove group</button>
            </div>
            {group.conditions.map((cond, ci) => {
              const { type, ...params } = cond
              return (
                <div key={ci} className={styles.conditionRow}>
                  <select className={styles.typeSelect} value={type}
                    onChange={e => updateConditionType(gi, ci, e.target.value)}>
                    {CONDITION_TYPES.map(t => <option key={t.value} value={t.value}>{t.label}</option>)}
                  </select>
                  <ConditionParams type={type} params={params}
                    onChange={p => updateConditionParams(gi, ci, p)} />
                  <button className={styles.removeCondition} onClick={() => removeCondition(gi, ci)}>{'\u2715'}</button>
                </div>
              )
            })}
            <button className={styles.addCondition} onClick={() => addCondition(gi)}>+ Add condition</button>
          </div>
        </div>
      ))}
      <button className={styles.addGroup} onClick={addGroup}>+ Add OR Group</button>
    </div>
  )
}
