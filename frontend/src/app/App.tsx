import { useCallback, useEffect, useRef, useState } from 'react'
import type { FormEvent, RefObject } from 'react'
import { authenticationErrorMessage, useAuth } from '../auth/AuthContext'
import { useMessages } from '../hooks/useMessages'
import { ApiError, deleteCity, fetchCities, publishMessage, resetAgentConversation, saveCity, searchCities, streamAgentMessage } from '../services/demoApi'
import { generateUuid } from '../lib/uuid'
import type { City } from '../types/demo'

interface ConversationEntry {
  id: string
  role: 'user' | 'assistant'
  content: string
}

type PendingAction =
  | { kind: 'save'; city: { name: string; population: number }; id?: number }
  | { kind: 'delete'; city: City }
  | { kind: 'publish' }
  | { kind: 'agent'; message: string }
  | { kind: 'restart' }

function describePendingAction(action: PendingAction): string {
  switch (action.kind) {
    case 'save': return `${action.id === undefined ? 'Create' : 'Update'} ${action.city.name} with a population of ${action.city.population.toLocaleString()}`
    case 'delete': return `Delete ${action.city.name} (population ${action.city.population.toLocaleString()})`
    case 'publish': return 'Publish a RabbitMQ message'
    case 'agent': return `Send this assistant request: “${action.message}”`
    case 'restart': return 'Clear this assistant conversation'
  }
}

export function App() {
  const auth = useAuth()
  const [cities, setCities] = useState<City[]>([])
  const [status, setStatus] = useState('Loading cities…')
  const [publishing, setPublishing] = useState(false)
  const [query, setQuery] = useState('')
  const [searchResults, setSearchResults] = useState<City[]>([])
  const [editing, setEditing] = useState<City | null>(null)
  const [name, setName] = useState('')
  const [population, setPopulation] = useState('')
  const [conversationId] = useState(() => {
    const storedId = window.sessionStorage.getItem('city-agent-conversation-id')
    if (storedId) return storedId
    const id = generateUuid()
    window.sessionStorage.setItem('city-agent-conversation-id', id)
    return id
  })
  const [conversation, setConversation] = useState<ConversationEntry[]>([])
  const [agentInput, setAgentInput] = useState('')
  const [sendingAgentMessage, setSendingAgentMessage] = useState(false)
  const [restartingConversation, setRestartingConversation] = useState(false)
  const chatTextareaRef = useRef<HTMLTextAreaElement>(null)
  const [agentStatus, setAgentStatus] = useState('')
  const [workingMessage, setWorkingMessage] = useState<string | null>(null)
  const [isWorkingFading, setIsWorkingFading] = useState(false)

  useEffect(() => {
    setTimeout(() => chatTextareaRef.current?.focus(), 0)
  }, [])
  const [pendingAction, setPendingAction] = useState<PendingAction | null>(null)
  const [loginUsername, setLoginUsername] = useState('admin') // TODO: reset
  const [loginPassword, setLoginPassword] = useState('admin') // TODO: reset
  const [loginError, setLoginError] = useState('')
  const [loggingIn, setLoggingIn] = useState(false)
  const [loggingOut, setLoggingOut] = useState(false)
  const conversationRef = useRef<HTMLDivElement>(null)

  const loadCities = useCallback(async () => {
    try {
      setCities(await fetchCities())
      setStatus('Cities loaded')
    } catch (error) {
      setStatus(error instanceof Error ? error.message : 'Unable to load cities')
    }
  }, [])

  // Stabilize the city-reload callback so the WebSocket connection doesn't unnecessarily
  // recreate on every render (which would drop incoming messages). The ref ensures the
  // latest `loadCities` is always called while keeping the reference stable for `useMessages`.
  const cityReloadRef = useRef<() => void>(loadCities)
  useEffect(() => {
    cityReloadRef.current = loadCities
  }, [loadCities])

  const handleCityChanged = useCallback(() => {
    cityReloadRef.current()
  }, [])

  const { messages, connected } = useMessages(handleCityChanged, Boolean(auth.user) && !auth.loading)

  // Reload cities whenever auth state changes (login, logout, session refresh)
  useEffect(() => {
    if (!auth.loading && auth.user) {
      void loadCities()
    }
  }, [auth.loading, auth.user, loadCities])

  useEffect(() => {
    const controller = new AbortController()
    const timer = window.setTimeout(async () => {
      if (!query.trim()) { setSearchResults([]); return }
      try { setSearchResults(await searchCities(query, controller.signal)) } catch (error) {
        if (!(error instanceof DOMException && error.name === 'AbortError')) setStatus('Unable to search cities')
      }
    }, 20)
    return () => { window.clearTimeout(timer); controller.abort() }
  }, [query])

  const executeAction = async (action: PendingAction) => {
    try {
      switch (action.kind) {
        case 'save':
          await saveCity(action.city, action.id)
          setName('')
          setPopulation('')
          setEditing(null)
          setStatus('City change queued')
          break
        case 'delete':
          await deleteCity(action.city.id)
          setStatus(`Deletion of ${action.city.name} queued`)
          break
        case 'publish':
          setPublishing(true)
          setStatus(await publishMessage())
          break
        case 'agent':
          if (!await sendAgentRequest(action.message)) return
          break
        case 'restart':
          setRestartingConversation(true)
          await resetAgentConversation(conversationId)
          setConversation([])
          setAgentInput('')
          setAgentStatus('')
          break
      }
      setPendingAction(null)
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        setPendingAction(action)
        auth.openLogin('Your session expired. Log in, review this action, and confirm it to continue.')
      } else {
        const message = error instanceof Error ? error.message : 'The requested action failed.'
        if (action.kind === 'agent') setAgentStatus(message)
        else if (action.kind === 'restart') setAgentStatus(message)
        else setStatus(message)
      }
    } finally {
      if (action.kind === 'publish') setPublishing(false)
      if (action.kind === 'restart') setRestartingConversation(false)
    }
  }

  const requestAction = (action: PendingAction, reason: string) => {
    if (!auth.user) {
      setPendingAction(action)
      auth.openLogin(reason)
      return
    }
    void executeAction(action)
  }

  const submitCity = () => {
    requestAction(
      { kind: 'save', city: { name: name.trim(), population: Number(population) }, id: editing?.id },
      'Log in to save this city. You will be able to review and confirm the change afterward.',
    )
  }

  const removeCity = (city: City) => {
    requestAction(
      { kind: 'delete', city },
      `Log in to delete ${city.name}. You will be asked to review and confirm the deletion afterward.`,
    )
  }

  const handlePublish = () => {
    requestAction({ kind: 'publish' }, 'Log in to publish a RabbitMQ message.')
  }

  useEffect(() => {
    const container = conversationRef.current
    if (container) container.scrollTop = container.scrollHeight
  }, [conversation])

  const clearWorkingMessage = () => {
    setIsWorkingFading(true)
    setTimeout(() => {
      setWorkingMessage(null)
      setIsWorkingFading(false)
    }, 400)
  }

  const sendAgentRequest = async (message: string, onComplete?: () => void): Promise<boolean> => {
    const responseId = generateUuid()
    const userMessageId = generateUuid()
    setConversation((current) => [
      ...current,
      { id: userMessageId, role: 'user', content: message },
      { id: responseId, role: 'assistant', content: '' },
    ])
    setAgentInput('')
    setAgentStatus('')
    setSendingAgentMessage(true)
    try {
      await streamAgentMessage(conversationId, message, (event) => {
        if (event.type === 'token' && event.content) {
          clearWorkingMessage()
          setConversation((current) => current.map((entry) => entry.id === responseId
            ? { ...entry, content: entry.content + event.content }
            : entry))
        } else if (event.type === 'working') {
          setWorkingMessage(event.content ?? 'The assistant is working on your request…')
        } else if (event.type === 'error') {
          clearWorkingMessage()
          setConversation((current) => current.map((entry) => entry.id === responseId
            ? { ...entry, content: event.error ?? 'The assistant is temporarily unavailable.' }
            : entry))
        } else if (event.type === 'done') {
          clearWorkingMessage()
          setConversation((current) => current.map((entry) => entry.id === responseId && !entry.content
            ? { ...entry, content: 'The assistant returned an empty response. Please try again.' }
            : entry))
        }
      })
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        setConversation((current) => current.filter((entry) => entry.id !== responseId && entry.id !== userMessageId))
        setAgentInput(message)
        setPendingAction({ kind: 'agent', message })
        auth.openLogin('Log in to resume this assistant request, then review and confirm before sending it.')
        return false
      }
      setConversation((current) => current.map((entry) => entry.id === responseId
        ? { ...entry, content: error instanceof Error ? error.message : 'Unable to reach the assistant.' }
        : entry))
    } finally {
      setSendingAgentMessage(false)
      if (onComplete) setTimeout(onComplete, 0)
    }
    return true
  }

  const sendAgentMessage = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const message = agentInput.trim()
    if (!message || sendingAgentMessage || auth.loading) return
    if (!auth.user) {
      setPendingAction({ kind: 'agent', message })
      auth.openLogin('Log in to resume this assistant request, then review and confirm before sending it.')
      return
    }
    void sendAgentRequest(message, () => { chatTextareaRef.current?.focus() })
  }

  const restartConversation = () => {
    if (sendingAgentMessage || restartingConversation) return
    requestAction({ kind: 'restart' }, 'Log in to restart the assistant conversation.')
  }

  const confirmPendingAction = async () => {
    if (!pendingAction || !auth.user) return
    await executeAction(pendingAction)
  }

  const submitLogin = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setLoginError('')
    setLoggingIn(true)
    try {
      await auth.login(loginUsername, loginPassword)
      setLoginPassword('')
      if (pendingAction?.kind === 'agent') {
        setPendingAction(null)
        const message = (pendingAction as { kind: 'agent'; message: string }).message
        if (message) {
          await sendAgentRequest(message)
          setTimeout(() => chatTextareaRef.current?.focus(), 0)
        }
      } else if (pendingAction) {
        auth.openLogin(`Login successful. ${describePendingAction(pendingAction)} is queued and pending your review.`)
      }
    } catch (error) {
      setLoginError(authenticationErrorMessage(error))
    } finally {
      setLoggingIn(false)
    }
  }

  const handleLogout = async () => {
    setLoggingOut(true)
    setPendingAction(null)
    try {
      await auth.logout()
      setStatus('You have been logged out.')
    } catch (error) {
      setStatus(error instanceof Error ? error.message : 'Unable to log out.')
    } finally {
      setLoggingOut(false)
    }
  }

  return (
    <main className="app-shell">
      <header className="hero">
        <div className="hero-heading">
          <div>
            <p className="eyebrow">Ktor application</p>
            <h1>Agentic with guardrails</h1>
            <p className="subtitle">Talk to an AI city assistant, watch it mutate data in real time, with guardrails and user behavior awareness</p>
          </div>
          <div className="auth-controls">
            {auth.loading ? <span className="muted">Checking session…</span> : auth.user
              ? <><span>Logged in as <strong>{auth.user.displayName}</strong></span><button type="button" className="secondary-button" onClick={() => void handleLogout()} disabled={loggingOut}>{loggingOut ? 'Logging out…' : 'Logout'}</button></>
              : <button type="button" onClick={() => auth.openLogin()} disabled={loggingIn}>Login</button>}
          </div>
        </div>
      </header>
      {pendingAction && <section className="card pending-action" aria-live="polite">
        <div>
          <h2>Review pending action</h2>
          <p>{describePendingAction(pendingAction)}</p>
          {!auth.user && <p className="muted">Log in first. This action will not run automatically after login.</p>}
        </div>
        <div className="pending-actions">
          <button type="button" onClick={() => void confirmPendingAction()} disabled={!auth.user || sendingAgentMessage || restartingConversation}>Confirm and continue</button>
          <button type="button" className="secondary-button" onClick={() => setPendingAction(null)}>Cancel</button>
        </div>
      </section>}
      <div className="dashboard-layout">
        <div className="dashboard-main">
          <section className="card">
            <input aria-label="Search cities by name" placeholder="Search by name" value={query} onChange={(event) => setQuery(event.target.value)} />
            {query.trim() ? (searchResults.length === 0 ? <p className="muted">No matching cities.</p> : <ul>{searchResults.map((city) => <li key={city.id}>{city.name} — {city.population.toLocaleString()}</li>)}</ul>) : (cities.length === 0 ? <p className="muted">No cities found.</p> : <ul>{cities.map((city) => <li key={city.id}>{city.name} — {city.population.toLocaleString()} <button type="button" onClick={() => { setEditing(city); setName(city.name); setPopulation(String(city.population)) }}>Edit</button> <button type="button" className="danger-button" onClick={() => removeCity(city)}>Delete</button></li>)}</ul>)}
          </section>

          <section className="card">
            <div className="section-heading"><h2>Cities</h2><button onClick={() => void loadCities()}>Refresh</button></div>
            <form className="city-form" onSubmit={(event) => { event.preventDefault(); submitCity() }}>
              <input aria-label="City name" placeholder="City name" value={name} onChange={(event) => setName(event.target.value)} required />
              <input aria-label="City population" type="number" min="0" max="2147483647" step="1" placeholder="Population" value={population} onChange={(event) => setPopulation(event.target.value)} required />
              <button type="submit">{editing ? 'Update' : 'Add'} city</button>
              {editing && <button type="button" className="secondary-button" onClick={() => { setEditing(null); setName(''); setPopulation('') }}>Cancel edit</button>}
            </form>
          </section>

          <section className="card">
            <div className="section-heading"><h2>RabbitMQ messages</h2><span className={connected ? 'connected' : 'muted'}>{connected ? 'Connected' : 'Reconnecting…'}</span></div>
            <button onClick={handlePublish} disabled={publishing}>{publishing ? 'Publishing…' : 'Publish message'}</button>
            <p className="muted">{status}</p>
            <pre>{messages.length ? messages.map((message) => message.body).join('\n') : 'Waiting for messages…'}</pre>
          </section>
        </div>

        <aside className="card assistant-card">
          <div className="section-heading"><h2>City assistant</h2><button type="button" onClick={() => void restartConversation()} disabled={sendingAgentMessage || restartingConversation}>{restartingConversation ? 'Restarting…' : 'Restart'}</button></div>
          <p className="muted">Ask to create, update, or delete a city. Changes are shared with connected clients.</p>
          {agentStatus && <p className="muted" role="status">{agentStatus}</p>}
          <div className="conversation" ref={conversationRef} aria-live="polite" aria-label="Assistant conversation">
            {conversation.length === 0 ? <p className="muted">Start a conversation. For example, “Create Oslo” or “Update Oslo to a population of 710000.”</p> : conversation.map((entry) => (
              <article className={`chat-message ${entry.role}`} key={entry.id}>
                <strong>{entry.role === 'user' ? 'You' : 'Assistant'}</strong>
                <p>{entry.content || (sendingAgentMessage && entry.role === 'assistant' ? 'Thinking…' : '')}</p>
              </article>
            ))}
            {workingMessage && (
              <article className="chat-message working" aria-live="polite">
                <strong>Assistant</strong>
                <p>{workingMessage}</p>
              </article>
            )}
          </div>
          <form className="chat-form" onSubmit={(event) => void sendAgentMessage(event)}>
            <textarea
              ref={chatTextareaRef}
              aria-label="Message the city assistant"
              value={agentInput}
              onChange={(event) => setAgentInput(event.target.value)}
              onKeyDown={(event) => {
                if (event.metaKey && event.key === 'Enter') {
                  event.preventDefault()
                  void sendAgentMessage(event as unknown as FormEvent<HTMLFormElement>)
                  setTimeout(() => chatTextareaRef.current?.focus(), 0)
                }
              }}
              placeholder="Message the city assistant"
              rows={3}
              disabled={sendingAgentMessage || restartingConversation}
            />
            <button type="submit" disabled={sendingAgentMessage || restartingConversation || !agentInput.trim()}>{sendingAgentMessage ? 'Responding…' : 'Send'}</button>
          </form>
        </aside>
      </div>
      {auth.loginOpen && <div className="modal-backdrop">
        <section className="card login-dialog" role="dialog" aria-modal="true" aria-labelledby="login-title">
          <div className="section-heading"><h2 id="login-title">Login</h2><button type="button" className="secondary-button" onClick={auth.closeLogin}>Close</button></div>
          <p className="muted">{auth.loginReason}</p>
          {loginError && <p className="error-message" role="alert">{loginError}</p>}
          <form className="login-form" onSubmit={(event) => void submitLogin(event)}>
            <label>Username<input autoFocus autoComplete="username" value={loginUsername} onChange={(event) => setLoginUsername(event.target.value)} required /></label>
            <label>Password<input type="password" autoComplete="current-password" value={loginPassword} onChange={(event) => setLoginPassword(event.target.value)} required /></label>
            <button type="submit" disabled={loggingIn}>{loggingIn ? 'Logging in…' : 'Login'}</button>
          </form>
        </section>
      </div>}
    </main>
  )
}
