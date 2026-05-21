import React, { useCallback, useEffect, useMemo, useState } from 'react'
import { useAuthRequest } from '../../auth/AuthContext'
import { telegramApi } from '../../api/telegram'
import { Badge } from '../../components/Badge/Badge'
import { Button } from '../../components/Button/Button'
import { Modal } from '../../components/Modal/Modal'
import styles from './Telegram.module.css'

const STATUS_VARIANT = {
  ACTIVE: 'green',
  AWAITING_CODE: 'yellow',
  AWAITING_PASSWORD: 'yellow',
  UNCONFIGURED: 'gray',
  DISCONNECTED: 'red',
}

export function Telegram() {
  const authRequest = useAuthRequest()
  const api = useMemo(() => telegramApi(authRequest), [authRequest])

  const [accounts, setAccounts] = useState([])
  const [error, setError] = useState('')
  const [showAdd, setShowAdd] = useState(false)
  const [addForm, setAddForm] = useState({ phoneNumber: '', displayName: '' })
  const [wizard, setWizard] = useState(null)
  const [codeInput, setCodeInput] = useState('')
  const [passwordInput, setPasswordInput] = useState('')

  const [expandedAccount, setExpandedAccount] = useState(null)
  const [watchedGroups, setWatchedGroups] = useState({})
  const [showDiscover, setShowDiscover] = useState(null)
  const [discoveredChats, setDiscoveredChats] = useState([])
  const [discoverLoading, setDiscoverLoading] = useState(false)
  const [discoverError, setDiscoverError] = useState('')

  const loadAccounts = useCallback(() => {
    api.listAccounts().then(setAccounts).catch(e => setError(e.message))
  }, [api])

  useEffect(() => {
    loadAccounts()
  }, [loadAccounts])

  const loadWatched = useCallback(
    accountId => {
      api
        .listWatched(accountId)
        .then(groups => setWatchedGroups(prev => ({ ...prev, [accountId]: groups })))
        .catch(() => {})
    },
    [api],
  )

  const openDiscover = useCallback(
    async accountId => {
      setShowDiscover(accountId)
      setDiscoverLoading(true)
      setDiscoverError('')
      setDiscoveredChats([])
      try {
        const chats = await api.discoverChats(accountId)
        setDiscoveredChats(chats)
      } catch (e) {
        setDiscoverError(e.message)
      } finally {
        setDiscoverLoading(false)
      }
    },
    [api],
  )

  const openGroupsPanel = useCallback(
    accountId => {
      setExpandedAccount(id => (id === accountId ? null : accountId))
      loadWatched(accountId)
    },
    [loadWatched],
  )

  const handleWatch = async (accountId, chat) => {
    try {
      await api.watchGroup(accountId, { chatId: chat.chatId, title: chat.title })
      loadWatched(accountId)
    } catch (e) {
      setDiscoverError(e.message)
    }
  }

  const handleUnwatch = async (accountId, chatId) => {
    try {
      await api.unwatchGroup(accountId, chatId)
      loadWatched(accountId)
    } catch (e) {
      setError(e.message)
    }
  }

  // Poll status when wizard is open
  useEffect(() => {
    if (!wizard) return
    const interval = setInterval(async () => {
      try {
        const s = await api.getStatus(wizard.accountId)
        if (s.status === 'ACTIVE') {
          const activeId = wizard.accountId
          setWizard(null)
          loadAccounts()
          setExpandedAccount(activeId)
          loadWatched(activeId)
          openDiscover(activeId)
        } else if (s.status === 'AWAITING_PASSWORD' && wizard.step !== 'password') {
          setWizard(w => ({ ...w, step: 'password', error: null }))
        }
      } catch (e) {
      console.warn('Telegram status poll error:', e.message)
    }
    }, 2500)
    return () => clearInterval(interval)
  }, [wizard, api, loadAccounts, loadWatched, openDiscover])

  const handleAdd = async () => {
    setError('')
    try {
      await api.createAccount({ phoneNumber: addForm.phoneNumber, displayName: addForm.displayName })
      setShowAdd(false)
      setAddForm({ phoneNumber: '', displayName: '' })
      loadAccounts()
    } catch (e) {
      setError(e.message)
    }
  }

  const handleReconnect = async id => {
    setError('')
    try {
      const res = await api.reconnect(id)
      if (res.accepted) {
        setWizard({ accountId: id, step: 'code', error: null })
      } else {
        setError(res.reason)
      }
    } catch (e) {
      setError(e.message)
    }
  }

  const handleSubmitCode = async () => {
    try {
      await api.submitCode(wizard.accountId, codeInput)
      setCodeInput('')
    } catch (e) {
      setWizard(w => ({ ...w, error: e.message }))
    }
  }

  const handleSubmitPassword = async () => {
    try {
      await api.submitPassword(wizard.accountId, passwordInput)
      setPasswordInput('')
    } catch (e) {
      setWizard(w => ({ ...w, error: e.message }))
    }
  }

  const handleLogout = async id => {
    setError('')
    try {
      await api.logout(id)
      loadAccounts()
    } catch (e) {
      setError(e.message)
    }
  }

  const handleDelete = async id => {
    setError('')
    try {
      await api.deleteAccount(id)
      loadAccounts()
    } catch (e) {
      setError(e.message)
    }
  }

  const isWatched = (accountId, chatId) =>
    (watchedGroups[accountId] || []).some(g => g.chatId === chatId)

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h2>Telegram Accounts</h2>
        <Button onClick={() => setShowAdd(true)}>Add Account</Button>
      </div>

      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}

      <table className={styles.table}>
        <thead>
          <tr>
            <th>Name</th>
            <th>Phone</th>
            <th>Status</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {accounts.map(a => (
            <React.Fragment key={a.id}>
              <tr>
                <td>{a.displayName || '—'}</td>
                <td>{a.phoneNumber}</td>
                <td>
                  <Badge variant={STATUS_VARIANT[a.status] ?? 'gray'} title={a.lastError ?? ''}>
                    {a.status}
                  </Badge>
                </td>
                <td className={styles.actions}>
                  <Button variant="secondary" onClick={() => openGroupsPanel(a.id)}>
                    Groups
                  </Button>
                  <Button variant="secondary" onClick={() => handleReconnect(a.id)}>
                    Auth
                  </Button>
                  <Button variant="secondary" onClick={() => handleLogout(a.id)}>
                    Logout
                  </Button>
                  <Button variant="danger" onClick={() => handleDelete(a.id)}>
                    Delete
                  </Button>
                </td>
              </tr>
              {expandedAccount === a.id && (
                <tr>
                  <td colSpan={4} className={styles.groupsPanel}>
                    <div className={styles.groupsPanelHeader}>
                      <span>Watched Groups</span>
                      <Button variant="secondary" onClick={() => openDiscover(a.id)}>
                        Discover
                      </Button>
                    </div>
                    {(watchedGroups[a.id] || []).length === 0 ? (
                      <p className={styles.empty}>
                        No groups watched. Use Discover to add groups.
                      </p>
                    ) : (
                      <table className={styles.innerTable}>
                        <thead>
                          <tr>
                            <th>Name</th>
                            <th>Chat ID</th>
                            <th>Moderation</th>
                            <th></th>
                          </tr>
                        </thead>
                        <tbody>
                          {(watchedGroups[a.id] || []).map(g => (
                            <tr key={g.chatId}>
                              <td>{g.name}</td>
                              <td>{g.chatId}</td>
                              <td>{g.moderationLevel}</td>
                              <td>
                                <Button
                                  variant="danger"
                                  onClick={() => handleUnwatch(a.id, g.chatId)}
                                >
                                  Unwatch
                                </Button>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    )}
                  </td>
                </tr>
              )}
            </React.Fragment>
          ))}
          {accounts.length === 0 && (
            <tr>
              <td colSpan={4} className={styles.empty}>
                No accounts configured
              </td>
            </tr>
          )}
        </tbody>
      </table>

      {showAdd && (
        <Modal title="Add Telegram Account" onClose={() => setShowAdd(false)}>
          <div className={styles.form}>
            {[
              {
                label: 'Display Name',
                key: 'displayName',
                type: 'text',
                placeholder: 'Monitor account 1',
              },
              {
                label: 'Phone Number',
                key: 'phoneNumber',
                type: 'text',
                placeholder: '+49123456789',
              },
            ].map(({ label, key, type, placeholder }) => (
              <div key={key}>
                <label className={styles.label}>{label}</label>
                <input
                  type={type}
                  className={styles.input}
                  placeholder={placeholder}
                  value={addForm[key]}
                  onChange={e => setAddForm(f => ({ ...f, [key]: e.target.value }))}
                />
              </div>
            ))}
            <div className={styles.modalActions}>
              <Button onClick={handleAdd}>Save</Button>
              <Button variant="secondary" onClick={() => setShowAdd(false)}>
                Cancel
              </Button>
            </div>
          </div>
        </Modal>
      )}

      {wizard && (
        <Modal title="Authenticate Account" onClose={() => setWizard(null)}>
          <div className={styles.form}>
            {wizard.error && <p className={styles.error}>{wizard.error}</p>}
            {wizard.step === 'code' && (
              <>
                <p>Enter the verification code sent to your Telegram app.</p>
                <input
                  type="text"
                  className={styles.input}
                  placeholder="12345"
                  value={codeInput}
                  onChange={e => setCodeInput(e.target.value)}
                  autoFocus
                />
                <div className={styles.modalActions}>
                  <Button onClick={handleSubmitCode}>Submit Code</Button>
                </div>
              </>
            )}
            {wizard.step === 'password' && (
              <>
                <p>Enter your 2FA password.</p>
                <input
                  type="password"
                  className={styles.input}
                  value={passwordInput}
                  onChange={e => setPasswordInput(e.target.value)}
                  autoFocus
                />
                <div className={styles.modalActions}>
                  <Button onClick={handleSubmitPassword}>Submit Password</Button>
                </div>
              </>
            )}
          </div>
        </Modal>
      )}

      {showDiscover && (
        <Modal title="Discover Groups" onClose={() => setShowDiscover(null)}>
          <div className={styles.discoverModal}>
            <div className={styles.discoverHeader}>
              <Button variant="secondary" onClick={() => openDiscover(showDiscover)}>
                Refresh
              </Button>
            </div>
            {discoverError && <p className={styles.error}>{discoverError}</p>}
            {discoverLoading && <p>Loading groups...</p>}
            {!discoverLoading && discoveredChats.length === 0 && !discoverError && (
              <p className={styles.empty}>
                No groups found. Ensure the account is ACTIVE and in at least one group.
              </p>
            )}
            {!discoverLoading && discoveredChats.length > 0 && (
              <table className={styles.innerTable}>
                <thead>
                  <tr>
                    <th>Name</th>
                    <th>Type</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {discoveredChats.map(chat => (
                    <tr key={chat.chatId}>
                      <td>{chat.title}</td>
                      <td>{chat.type}</td>
                      <td>
                        {isWatched(showDiscover, chat.chatId) ? (
                          <Button variant="secondary" disabled>
                            Watching
                          </Button>
                        ) : (
                          <Button onClick={() => handleWatch(showDiscover, chat)}>Watch</Button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </Modal>
      )}
    </div>
  )
}
