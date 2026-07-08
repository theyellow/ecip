import { useEffect, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { aiConfigApi } from '../../api/aiConfig'
import { providerConfigApi } from '../../api/providerConfig'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { ConfirmDialog } from '../../components/ConfirmDialog/ConfirmDialog'
import { Modal } from '../../components/Modal/Modal'
import { SectionLabel } from '../../components/SectionLabel/SectionLabel'
import styles from './AIConfig.module.css'

function ProxyModelPicker({ onPick }) {
  const api = providerConfigApi(useAuthRequest())
  const [models, setModels] = useState([])
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)

  const load = async () => {
    if (open) { setOpen(false); return }
    setLoading(true)
    try {
      const data = await api.getProxyModels()
      setModels(data.models ?? [])
      setOpen(true)
    } catch {
      setModels([])
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className={styles.proxyPicker}>
      <Button variant="secondary" onClick={load} disabled={loading}>
        {loading ? '…' : 'Pick from proxy'}
      </Button>
      {open && models.length > 0 && (
        <select className={styles.input} size={Math.min(models.length, 6)}
          onChange={e => { onPick(e.target.value); setOpen(false) }}>
          {models.map(m => <option key={m} value={m}>{m}</option>)}
        </select>
      )}
    </div>
  )
}

function ModelModal({ model, onClose, onSave }) {
  const [form, setForm] = useState({
    modelKey: model?.modelKey ?? '',
    provider: model?.provider ?? '',
    modelName: model?.modelName ?? '',
    description: model?.description ?? '',
    taskType: model?.taskType ?? 'GENERAL',
    inputCostPer1kTokens: model?.inputCostPer1kTokens ?? 0,
    outputCostPer1kTokens: model?.outputCostPer1kTokens ?? 0,
    contextWindow: model?.contextWindow ?? 8192,
    maxOutputTokens: model?.maxOutputTokens ?? 2048,
    avgLatencyMs: model?.avgLatencyMs ?? 500,
    supportsStreaming: model?.supportsStreaming ?? false,
    active: model?.active ?? true,
    priority: model?.priority ?? 100,
  })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  return (
    <Modal title={model ? 'Edit Model' : 'Add Model'} onClose={onClose} onSubmit={() => onSave(form)}>
      <div className={styles.field}>
        <label>Model Key *</label>
        <input type="text" className={styles.input} value={form.modelKey}
          onChange={e => set('modelKey', e.target.value)} required />
      </div>
      <div className={styles.field}>
        <label>Provider *</label>
        <input type="text" className={styles.input} value={form.provider}
          onChange={e => set('provider', e.target.value)} placeholder="openai / anthropic / google" required />
      </div>
      <div className={styles.field}>
        <label>Model Name *</label>
        <div className={styles.modelNameRow}>
          <input type="text" className={styles.input} value={form.modelName}
            onChange={e => set('modelName', e.target.value)} required />
          <ProxyModelPicker onPick={name => set('modelName', name)} />
        </div>
      </div>
      <div className={styles.field}>
        <label>Description</label>
        <input type="text" className={styles.input} value={form.description}
          onChange={e => set('description', e.target.value)} />
      </div>
      <div className={styles.field}>
        <label>Task Type</label>
        <select className={styles.input} value={form.taskType}
          onChange={e => set('taskType', e.target.value)}>
          {['GENERAL', 'CLASSIFICATION', 'MODERATION', 'EMBED', 'EXTRACT', 'CHAT', 'SUMMARIZATION'].map(t => (
            <option key={t}>{t}</option>
          ))}
        </select>
      </div>
      <div className={styles.field}>
        <label>Input cost / 1k tokens ($)</label>
        <input type="number" step="0.0001" className={styles.input} value={form.inputCostPer1kTokens}
          onChange={e => set('inputCostPer1kTokens', parseFloat(e.target.value))} />
      </div>
      <div className={styles.field}>
        <label>Output cost / 1k tokens ($)</label>
        <input type="number" step="0.0001" className={styles.input} value={form.outputCostPer1kTokens}
          onChange={e => set('outputCostPer1kTokens', parseFloat(e.target.value))} />
      </div>
      <div className={styles.field}>
        <label>Context window (tokens)</label>
        <input type="number" className={styles.input} value={form.contextWindow}
          onChange={e => set('contextWindow', parseInt(e.target.value, 10))} />
      </div>
      <div className={styles.field}>
        <label>Max output tokens</label>
        <input type="number" className={styles.input} value={form.maxOutputTokens}
          onChange={e => set('maxOutputTokens', parseInt(e.target.value, 10))} />
      </div>
      <div className={styles.field}>
        <label>Priority (lower = preferred)</label>
        <input type="number" className={styles.input} value={form.priority}
          onChange={e => set('priority', parseInt(e.target.value, 10))} />
      </div>
      <label>
        <input type="checkbox" checked={form.supportsStreaming}
          onChange={e => set('supportsStreaming', e.target.checked)} /> Supports streaming
      </label>
      <label>
        <input type="checkbox" checked={form.active}
          onChange={e => set('active', e.target.checked)} /> Active
      </label>
    </Modal>
  )
}

function TemplateModal({ template, models, onClose, onSave }) {
  const isSystem = template?.system === true
  const [form, setForm] = useState({
    name: template?.name ?? '',
    version: template?.version ?? '1.0',
    description: template?.description ?? '',
    modelConfigId: template?.modelConfig?.id ?? '',
    systemPrompt: template?.systemPrompt ?? '',
    userPromptTemplate: template?.userPromptTemplate ?? '',
    temperature: template?.temperature ?? '',
    maxTokens: template?.maxTokens ?? 8192,
    active: template?.active ?? true,
    priority: template?.priority ?? 100,
  })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  const handleSave = () => {
    const payload = {
      ...form,
      temperature: form.temperature === '' ? null : parseFloat(form.temperature),
      modelConfig: form.modelConfigId ? { id: form.modelConfigId } : null,
    }
    delete payload.modelConfigId
    onSave(payload)
  }

  return (
    <Modal title={template ? 'Edit Template' : 'Add Template'} onClose={onClose} onSubmit={handleSave}>
      <div className={styles.field}>
        <label>Name *</label>
        <input type="text" className={styles.input} value={form.name}
          onChange={e => set('name', e.target.value)} required disabled={isSystem} />
      </div>
      <div className={styles.field}>
        <label>Version</label>
        <input type="text" className={styles.input} value={form.version}
          onChange={e => set('version', e.target.value)} />
      </div>
      <div className={styles.field}>
        <label>Description</label>
        <input type="text" className={styles.input} value={form.description}
          onChange={e => set('description', e.target.value)} />
      </div>
      <div className={styles.field}>
        <label>Model</label>
        <select className={styles.input} value={form.modelConfigId}
          onChange={e => set('modelConfigId', e.target.value)}>
          <option value="">Default (auto)</option>
          {models.map(m => (
            <option key={m.id} value={m.id}>{m.modelKey} — {m.modelName}</option>
          ))}
        </select>
      </div>
      <div className={styles.field}>
        <label>System Prompt *</label>
        <textarea className={`${styles.input} ${styles.promptTextarea}`} rows={6}
          value={form.systemPrompt} onChange={e => set('systemPrompt', e.target.value)} required />
      </div>
      <div className={styles.field}>
        <label>User Prompt Template</label>
        <textarea className={`${styles.input} ${styles.promptTextarea}`} rows={4}
          value={form.userPromptTemplate} onChange={e => set('userPromptTemplate', e.target.value)}
          placeholder="Use {{variable}} placeholders" />
      </div>
      <div className={styles.field}>
        <label>Temperature</label>
        <input type="number" step="0.1" min="0" max="2" className={styles.input}
          value={form.temperature} onChange={e => set('temperature', e.target.value)}
          placeholder="Model default" />
      </div>
      <div className={styles.field}>
        <label>Max Tokens</label>
        <input type="number" className={styles.input} value={form.maxTokens}
          onChange={e => set('maxTokens', parseInt(e.target.value, 10))} />
      </div>
      <label>
        <input type="checkbox" checked={form.active}
          onChange={e => set('active', e.target.checked)} /> Active
      </label>
    </Modal>
  )
}

function ProviderModal({ provider, onClose, onSave }) {
  const api = providerConfigApi(useAuthRequest())
  const [form, setForm] = useState({
    name: provider?.name ?? '',
    baseUrl: provider?.baseUrl ?? '',
    apiKey: '',
    active: provider?.active ?? false,
  })
  const [testResult, setTestResult] = useState(null)
  const [testing, setTesting] = useState(false)
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  const testConnection = async () => {
    if (!form.baseUrl) return
    setTesting(true)
    setTestResult(null)
    try {
      const data = await api.getProxyModels({ baseUrl: form.baseUrl, apiKey: form.apiKey || undefined })
      setTestResult({ ok: data.reachable, models: data.models ?? [] })
    } catch {
      setTestResult({ ok: false, models: [] })
    } finally {
      setTesting(false)
    }
  }

  return (
    <Modal title={provider ? 'Edit Provider' : 'Add Provider'} onClose={onClose} onSubmit={() => onSave(form)}>
      <div className={styles.field}>
        <label>Name *</label>
        <input type="text" className={styles.input} value={form.name}
          onChange={e => set('name', e.target.value)} placeholder="local-litellm" required />
      </div>
      <div className={styles.field}>
        <label>Base URL *</label>
        <input type="text" className={styles.input} value={form.baseUrl}
          onChange={e => { set('baseUrl', e.target.value); setTestResult(null) }}
          placeholder="" required />
      </div>
      <div className={styles.field}>
        <label>API Key (optional — leave blank to keep existing)</label>
        <input type="password" className={styles.input} value={form.apiKey}
          onChange={e => { set('apiKey', e.target.value); setTestResult(null) }}
          placeholder="Leave blank if not required" />
      </div>
      <div className={styles.proxyPicker}>
        <Button type="button" variant="secondary" onClick={testConnection}
          disabled={testing || !form.baseUrl}>
          {testing ? '…' : 'Test & list models'}
        </Button>
        {testResult && (
          <Badge variant={testResult.ok ? 'green' : 'red'}>
            {testResult.ok ? `Reachable — ${testResult.models.length} model(s)` : 'Unreachable'}
          </Badge>
        )}
      </div>
      {testResult?.ok && testResult.models.length > 0 && (
        <ul className={styles.modelList}>
          {testResult.models.map(m => <li key={m} className={styles.mono}>{m}</li>)}
        </ul>
      )}
      <label>
        <input type="checkbox" checked={form.active}
          onChange={e => set('active', e.target.checked)} /> Active
      </label>
    </Modal>
  )
}

function ProviderConfigSection() {
  const api = providerConfigApi(useAuthRequest())
  const [providers, setProviders] = useState([])
  const [modal, setModal] = useState(null)
  const [status, setStatus] = useState(null)
  const [error, setError] = useState('')

  const load = () =>
    api.listProviderConfigs().then(setProviders).catch(e => setError(e.message))

  useEffect(() => { load() }, [])

  const save = async form => {
    try {
      if (modal === 'add') await api.createProviderConfig(form)
      else await api.updateProviderConfig(modal.id, form)
      setModal(null)
      load()
    } catch (e) { setError(e.message) }
  }

  const [pendingDelete, setPendingDelete] = useState(null)

  const remove = async p => {
    try { await api.deleteProviderConfig(p.id); load() }
    catch (e) { setError(e.message) }
  }

  const testConnection = async () => {
    setStatus(null)
    try {
      const data = await api.getProxyModels()
      setStatus({ ok: data.reachable, models: data.models ?? [] })
    } catch {
      setStatus({ ok: false, models: [] })
    }
  }

  return (
    <div className={styles.section}>
      <SectionLabel aside={<Button onClick={() => setModal('add')}>+ Add Provider</Button>}>LLM Provider</SectionLabel>
      {error && <p role="alert" style={{ color: 'var(--signal-stop-fg)', background: 'rgba(248,113,113,0.08)', border: '1px solid rgba(248,113,113,0.25)', padding: '8px 12px', fontFamily: 'var(--font-mono)', fontSize: '12px', marginBottom: 'var(--sp-3)' }}>{error}</p>}
      <div className={styles.tableWrapper}>
      <table className={styles.table}>
        <thead>
          <tr><th>Name</th><th>Base URL</th><th>Active</th><th></th></tr>
        </thead>
        <tbody>
          {providers.map(p => (
            <tr key={p.id} className={styles.clickable} onClick={() => setModal(p)}>
              <td className={styles.mono}>{p.name}</td>
              <td>{p.baseUrl}</td>
              <td><Badge variant={p.active ? 'green' : 'red'}>{p.active ? 'Yes' : 'No'}</Badge></td>
              <td className={styles.actions} onClick={e => e.stopPropagation()}>
                {p.active && (
                  <Button variant="secondary" onClick={testConnection}>Test</Button>
                )}
                <Button variant="danger" onClick={() => setPendingDelete({ kind: 'provider', row: p })}>Delete</Button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      </div>
      {status && (
        <div className={styles.connectionStatus}>
          <Badge variant={status.ok ? 'green' : 'red'}>
            {status.ok ? 'Reachable' : 'Unreachable'}
          </Badge>
          {status.ok && status.models.length > 0 && (
            <ul className={styles.modelList}>
              {status.models.map(m => <li key={m} className={styles.mono}>{m}</li>)}
            </ul>
          )}
        </div>
      )}
      {modal && (
        <ProviderModal
          provider={modal === 'add' ? null : modal}
          onClose={() => setModal(null)}
          onSave={save}
        />
      )}
      {pendingDelete && (
        <ConfirmDialog
          title="Delete record"
          message={`Delete provider "${pendingDelete.row.name}"? This cannot be undone.`}
          onConfirm={() => { remove(pendingDelete.row); setPendingDelete(null) }}
          onClose={() => setPendingDelete(null)}
        />
      )}
    </div>
  )
}

export function AIConfig() {
  const api = aiConfigApi(useAuthRequest())

  const [models, setModels] = useState([])
  const [templates, setTemplates] = useState([])
  const [modelModal, setModelModal] = useState(null)
  const [templateModal, setTemplateModal] = useState(null)
  const [pendingDelete, setPendingDelete] = useState(null)
  const [error, setError] = useState('')

  const loadModels = () => api.listModels().then(setModels).catch(e => setError(e.message))
  const loadTemplates = () => api.listTemplates().then(setTemplates).catch(e => setError(e.message))

  useEffect(() => {
    loadModels()
    loadTemplates()
  }, [])

  const saveModel = async form => {
    try {
      if (modelModal === 'add') await api.createModel(form)
      else await api.updateModel(modelModal.id, form)
      setModelModal(null)
      loadModels()
    } catch (e) { setError(e.message) }
  }

  const removeModel = async model => {
    try { await api.deleteModel(model.id); loadModels() }
    catch (e) { setError(e.message) }
  }

  const saveTemplate = async form => {
    try {
      if (templateModal === 'add') await api.createTemplate(form)
      else await api.updateTemplate(templateModal.id, form)
      setTemplateModal(null)
      loadTemplates()
    } catch (e) { setError(e.message) }
  }

  const removeTemplate = async tmpl => {
    try { await api.deleteTemplate(tmpl.id); loadTemplates() }
    catch (e) { setError(e.message) }
  }

  return (
    <>
      <div className={styles.pageHeader}>
        <div>
          <h2>AI Config</h2>
          <div className={styles.systemId}>{'\u2726'} llm-orchestrator {'\u00b7'} {models.filter(m => m.active).length} active models</div>
        </div>
      </div>
      {error && <p role="alert" style={{ color: 'var(--signal-stop-fg)', background: 'rgba(248,113,113,0.08)', border: '1px solid rgba(248,113,113,0.25)', padding: '8px 12px', fontFamily: 'var(--font-mono)', fontSize: '12px', marginBottom: 'var(--sp-3)' }}>{error}</p>}

      {/* Models */}
      <div className={styles.section}>
        <SectionLabel aside={<Button onClick={() => setModelModal('add')}>+ Add Model</Button>}>AI Models</SectionLabel>
        <div className={styles.tableWrapper}>
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Key</th>
              <th>Provider</th>
              <th>Model Name</th>
              <th>Task Type</th>
              <th>Priority</th>
              <th>Active</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {models.map(m => (
              <tr key={m.id} className={styles.clickable} onClick={() => setModelModal(m)}>
                <td className={styles.mono}>{m.modelKey}</td>
                <td>{m.provider}</td>
                <td>{m.modelName}</td>
                <td><Badge variant="gray">{m.taskType}</Badge></td>
                <td>{m.priority}</td>
                <td><Badge variant={m.active ? 'green' : 'red'}>{m.active ? 'Yes' : 'No'}</Badge></td>
                <td className={styles.actions} onClick={e => e.stopPropagation()}>
                  <Button variant="danger" onClick={() => setPendingDelete({ kind: 'model', row: m })}>Delete</Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      </div>

      {/* Templates */}
      <div className={styles.section}>
        <SectionLabel aside={<Button onClick={() => setTemplateModal('add')}>+ Add Template</Button>}>Prompt Templates</SectionLabel>
        <div className={styles.tableWrapper}>
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Name</th>
              <th>Type</th>
              <th>Model</th>
              <th>System Prompt</th>
              <th>Active</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {templates.map(t => (
              <tr key={t.id} className={styles.clickable} onClick={() => setTemplateModal(t)}>
                <td>{t.name}</td>
                <td>{t.system ? <Badge variant="blue">System</Badge> : <Badge variant="gray">Custom</Badge>}</td>
                <td className={styles.mono}>{t.modelConfig?.modelKey ?? '—'}</td>
                <td className={styles.preview} title={t.systemPrompt}>{t.systemPrompt}</td>
                <td><Badge variant={t.active ? 'green' : 'red'}>{t.active ? 'Yes' : 'No'}</Badge></td>
                <td className={styles.actions} onClick={e => e.stopPropagation()}>
                  {!t.system && <Button variant="danger" onClick={() => setPendingDelete({ kind: 'template', row: t })}>Delete</Button>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      </div>

      {/* LLM Provider */}
      <ProviderConfigSection />

      {modelModal && (
        <ModelModal
          model={modelModal === 'add' ? null : modelModal}
          onClose={() => setModelModal(null)}
          onSave={saveModel}
        />
      )}
      {templateModal && (
        <TemplateModal
          template={templateModal === 'add' ? null : templateModal}
          models={models}
          onClose={() => setTemplateModal(null)}
          onSave={saveTemplate}
        />
      )}
      {pendingDelete && (
        <ConfirmDialog
          title="Delete record"
          message={
            pendingDelete.kind === 'model'
              ? `Delete model "${pendingDelete.row.modelKey}"? This cannot be undone.`
              : `Delete template "${pendingDelete.row.name}"? This cannot be undone.`
          }
          onConfirm={() => {
            if (pendingDelete.kind === 'model') removeModel(pendingDelete.row)
            else removeTemplate(pendingDelete.row)
            setPendingDelete(null)
          }}
          onClose={() => setPendingDelete(null)}
        />
      )}
    </>
  )
}
