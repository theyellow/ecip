import { useEffect, useState } from 'react'
import { useAuth } from '../../auth/AuthContext'
import { makeRequest } from '../../api/client'
import { aiConfigApi } from '../../api/aiConfig'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { Modal } from '../../components/Modal/Modal'
import styles from './AIConfig.module.css'

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
      <label>Model Key *</label>
      <input type="text" className={styles.input} value={form.modelKey}
        onChange={e => set('modelKey', e.target.value)} required />
      <label>Provider *</label>
      <input type="text" className={styles.input} value={form.provider}
        onChange={e => set('provider', e.target.value)} placeholder="openai / anthropic / google" required />
      <label>Model Name *</label>
      <input type="text" className={styles.input} value={form.modelName}
        onChange={e => set('modelName', e.target.value)} required />
      <label>Description</label>
      <input type="text" className={styles.input} value={form.description}
        onChange={e => set('description', e.target.value)} />
      <label>Task Type</label>
      <select className={styles.input} value={form.taskType}
        onChange={e => set('taskType', e.target.value)}>
        {['GENERAL', 'CLASSIFICATION', 'MODERATION', 'SUMMARIZATION', 'CHAT'].map(t => (
          <option key={t}>{t}</option>
        ))}
      </select>
      <label>Input cost / 1k tokens ($)</label>
      <input type="number" step="0.0001" className={styles.input} value={form.inputCostPer1kTokens}
        onChange={e => set('inputCostPer1kTokens', parseFloat(e.target.value))} />
      <label>Output cost / 1k tokens ($)</label>
      <input type="number" step="0.0001" className={styles.input} value={form.outputCostPer1kTokens}
        onChange={e => set('outputCostPer1kTokens', parseFloat(e.target.value))} />
      <label>Context window (tokens)</label>
      <input type="number" className={styles.input} value={form.contextWindow}
        onChange={e => set('contextWindow', parseInt(e.target.value, 10))} />
      <label>Max output tokens</label>
      <input type="number" className={styles.input} value={form.maxOutputTokens}
        onChange={e => set('maxOutputTokens', parseInt(e.target.value, 10))} />
      <label>Priority (lower = preferred)</label>
      <input type="number" className={styles.input} value={form.priority}
        onChange={e => set('priority', parseInt(e.target.value, 10))} />
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

function TemplateModal({ template, onClose, onSave }) {
  const [form, setForm] = useState({
    name: template?.name ?? '',
    version: template?.version ?? '1.0',
    description: template?.description ?? '',
    modelProvider: template?.modelProvider ?? '',
    modelName: template?.modelName ?? '',
    systemPrompt: template?.systemPrompt ?? '',
    userPromptTemplate: template?.userPromptTemplate ?? '',
    temperature: template?.temperature ?? 0.7,
    maxTokens: template?.maxTokens ?? 2048,
    active: template?.active ?? true,
    priority: template?.priority ?? 100,
  })
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  return (
    <Modal title={template ? 'Edit Template' : 'Add Template'} onClose={onClose} onSubmit={() => onSave(form)}>
      <label>Name *</label>
      <input type="text" className={styles.input} value={form.name}
        onChange={e => set('name', e.target.value)} required />
      <label>Version</label>
      <input type="text" className={styles.input} value={form.version}
        onChange={e => set('version', e.target.value)} />
      <label>Description</label>
      <input type="text" className={styles.input} value={form.description}
        onChange={e => set('description', e.target.value)} />
      <label>Model Provider</label>
      <input type="text" className={styles.input} value={form.modelProvider}
        onChange={e => set('modelProvider', e.target.value)} placeholder="openai" />
      <label>Model Name</label>
      <input type="text" className={styles.input} value={form.modelName}
        onChange={e => set('modelName', e.target.value)} placeholder="gpt-4o" />
      <label>System Prompt *</label>
      <textarea className={`${styles.input} ${styles.promptTextarea}`} rows={6}
        value={form.systemPrompt} onChange={e => set('systemPrompt', e.target.value)} required />
      <label>User Prompt Template</label>
      <textarea className={`${styles.input} ${styles.promptTextarea}`} rows={4}
        value={form.userPromptTemplate} onChange={e => set('userPromptTemplate', e.target.value)}
        placeholder="Use {{variable}} placeholders" />
      <label>Temperature</label>
      <input type="number" step="0.1" min="0" max="2" className={styles.input}
        value={form.temperature} onChange={e => set('temperature', parseFloat(e.target.value))} />
      <label>Max Tokens</label>
      <input type="number" className={styles.input} value={form.maxTokens}
        onChange={e => set('maxTokens', parseInt(e.target.value, 10))} />
      <label>
        <input type="checkbox" checked={form.active}
          onChange={e => set('active', e.target.checked)} /> Active
      </label>
    </Modal>
  )
}

export function AIConfig() {
  const { token } = useAuth()
  const api = aiConfigApi(makeRequest(token))

  const [models, setModels] = useState([])
  const [templates, setTemplates] = useState([])
  const [modelModal, setModelModal] = useState(null)
  const [templateModal, setTemplateModal] = useState(null)
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
    if (!confirm(`Delete model "${model.modelKey}"?`)) return
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
    if (!confirm(`Delete template "${tmpl.name}"?`)) return
    try { await api.deleteTemplate(tmpl.id); loadTemplates() }
    catch (e) { setError(e.message) }
  }

  return (
    <div className={styles.page}>
      <h2>AI Configuration</h2>
      {error && <p className={styles.error} role="alert">{error}</p>}

      {/* Models */}
      <div className={styles.section}>
        <div className={styles.sectionHeader}>
          <h3 className={styles.sectionTitle}>AI Models</h3>
          <Button onClick={() => setModelModal('add')}>+ Add Model</Button>
        </div>
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
              <tr key={m.id}>
                <td className={styles.mono}>{m.modelKey}</td>
                <td>{m.provider}</td>
                <td>{m.modelName}</td>
                <td><Badge variant="gray">{m.taskType}</Badge></td>
                <td>{m.priority}</td>
                <td><Badge variant={m.active ? 'green' : 'red'}>{m.active ? 'Yes' : 'No'}</Badge></td>
                <td className={styles.actions}>
                  <Button variant="secondary" onClick={() => setModelModal(m)}>Edit</Button>
                  <Button variant="danger" onClick={() => removeModel(m)}>Delete</Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Templates */}
      <div className={styles.section}>
        <div className={styles.sectionHeader}>
          <h3 className={styles.sectionTitle}>Prompt Templates</h3>
          <Button onClick={() => setTemplateModal('add')}>+ Add Template</Button>
        </div>
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Name</th>
              <th>Version</th>
              <th>Provider</th>
              <th>System Prompt</th>
              <th>Active</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {templates.map(t => (
              <tr key={t.id}>
                <td>{t.name}</td>
                <td className={styles.mono}>{t.version}</td>
                <td>{t.modelProvider}</td>
                <td className={styles.preview} title={t.systemPrompt}>{t.systemPrompt}</td>
                <td><Badge variant={t.active ? 'green' : 'red'}>{t.active ? 'Yes' : 'No'}</Badge></td>
                <td className={styles.actions}>
                  <Button variant="secondary" onClick={() => setTemplateModal(t)}>Edit</Button>
                  <Button variant="danger" onClick={() => removeTemplate(t)}>Delete</Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

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
          onClose={() => setTemplateModal(null)}
          onSave={saveTemplate}
        />
      )}
    </div>
  )
}
