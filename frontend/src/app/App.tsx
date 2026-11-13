import { useCallback, useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { useMessages } from '../hooks/useMessages'
import { deleteCity, fetchCities, publishMessage, saveCity, searchCities, streamAgentMessage } from '../services/demoApi'
import type { City } from '../types/demo'

interface ConversationEntry {
  id: string
  role: 'user' | 'assistant'
  content: string
}

export function App() {
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
    const id = window.crypto.randomUUID()
    window.sessionStorage.setItem('city-agent-conversation-id', id)
    return id
  })
  const [conversation, setConversation] = useState<ConversationEntry[]>([])
  const [agentInput, setAgentInput] = useState('')
  const [sendingAgentMessage, setSendingAgentMessage] = useState(false)
  const conversationRef = useRef<HTMLDivElement>(null)

  const loadCities = useCallback(async () => {
    try {
      setCities(await fetchCities())
      setStatus('Cities loaded')
    } catch (error) {
      setStatus(error instanceof Error ? error.message : 'Unable to load cities')
    }
  }, [])

  const { messages, connected } = useMessages(loadCities)

  useEffect(() => { void loadCities() }, [])

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

  const submitCity = async () => {
    try { await saveCity({ name, population: Number(population) }, editing?.id); setName(''); setPopulation(''); setEditing(null); setStatus('City change queued') }
    catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to save city') }
  }

  const removeCity = async (id: number) => {
    try { await deleteCity(id); setStatus('City deletion queued') }
    catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to delete city') }
  }

  const handlePublish = async () => {
    setPublishing(true)
    try {
      setStatus(await publishMessage())
    } catch (error) {
      setStatus(error instanceof Error ? error.message : 'Unable to publish message')
    } finally {
      setPublishing(false)
    }
  }

  useEffect(() => {
    const container = conversationRef.current
    if (container) container.scrollTop = container.scrollHeight
  }, [conversation])

  const sendAgentMessage = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const message = agentInput.trim()
    if (!message || sendingAgentMessage) return

    const responseId = window.crypto.randomUUID()
    setConversation((current) => [
      ...current,
      { id: window.crypto.randomUUID(), role: 'user', content: message },
      { id: responseId, role: 'assistant', content: '' },
    ])
    setAgentInput('')
    setSendingAgentMessage(true)
    try {
      await streamAgentMessage(conversationId, message, (event) => {
        if (event.type === 'token' && event.content) {
          setConversation((current) => current.map((entry) => entry.id === responseId
            ? { ...entry, content: entry.content + event.content }
            : entry))
        } else if (event.type === 'error') {
          setConversation((current) => current.map((entry) => entry.id === responseId
            ? { ...entry, content: event.error ?? 'The assistant is temporarily unavailable.' }
            : entry))
        }
      })
    } catch (error) {
      setConversation((current) => current.map((entry) => entry.id === responseId
        ? { ...entry, content: error instanceof Error ? error.message : 'Unable to reach the assistant.' }
        : entry))
    } finally {
      setSendingAgentMessage(false)
    }
  }

  return (
    <main className="app-shell">
      <header className="hero">
        <p className="eyebrow">Ktor application</p>
        <h1>React frontend</h1>
        <p className="subtitle">A lightweight presentation layer for the existing city and RabbitMQ demo.</p>
      </header>
      <div className="dashboard-layout">
        <div className="dashboard-main">
          <section className="card">
            <input aria-label="Search cities by name" placeholder="Search by name" value={query} onChange={(event) => setQuery(event.target.value)} />
            {query.trim() ? (searchResults.length === 0 ? <p className="muted">No matching cities.</p> : <ul>{searchResults.map((city) => <li key={city.id}>{city.name} — {city.population.toLocaleString()}</li>)}</ul>) : (cities.length === 0 ? <p className="muted">No cities found.</p> : <ul>{cities.map((city) => <li key={city.id}>{city.name} — {city.population.toLocaleString()} <button onClick={() => { setEditing(city); setName(city.name); setPopulation(String(city.population)) }}>Edit</button> <button onClick={() => void removeCity(city.id)}>Delete</button></li>)}</ul>)}
          </section>

          <section className="card">
            <div className="section-heading"><h2>Cities</h2><button onClick={() => void loadCities()}>Refresh</button></div>
            <div className="section-heading"><input placeholder="City name" value={name} onChange={(event) => setName(event.target.value)} /><input type="number" placeholder="Population" value={population} onChange={(event) => setPopulation(event.target.value)} /><button onClick={() => void submitCity()}>{editing ? 'Update' : 'Add'} city</button></div>
          </section>

          <section className="card">
            <div className="section-heading"><h2>RabbitMQ messages</h2><span className={connected ? 'connected' : 'muted'}>{connected ? 'Connected' : 'Reconnecting…'}</span></div>
            <button onClick={() => void handlePublish()} disabled={publishing}>{publishing ? 'Publishing…' : 'Publish message'}</button>
            <p className="muted">{status}</p>
            <pre>{messages.length ? messages.map((message) => message.body).join('\n') : 'Waiting for messages…'}</pre>
          </section>
        </div>

        <aside className="card assistant-card">
          <h2>City assistant</h2>
          <p className="muted">Ask to create, update, or delete a city. Changes are shared with connected clients.</p>
          <div className="conversation" ref={conversationRef} aria-live="polite" aria-label="Assistant conversation">
            {conversation.length === 0 ? <p className="muted">Start a conversation. For example, “Create Oslo” or “Update Oslo to a population of 710000.”</p> : conversation.map((entry) => (
              <article className={`chat-message ${entry.role}`} key={entry.id}>
                <strong>{entry.role === 'user' ? 'You' : 'Assistant'}</strong>
                <p>{entry.content || (sendingAgentMessage && entry.role === 'assistant' ? 'Thinking…' : '')}</p>
              </article>
            ))}
          </div>
          <form className="chat-form" onSubmit={(event) => void sendAgentMessage(event)}>
            <textarea aria-label="Message the city assistant" value={agentInput} onChange={(event) => setAgentInput(event.target.value)} placeholder="Message the city assistant" rows={3} disabled={sendingAgentMessage} />
            <button type="submit" disabled={sendingAgentMessage || !agentInput.trim()}>{sendingAgentMessage ? 'Responding…' : 'Send'}</button>
          </form>
        </aside>
      </div>
    </main>
  )
}
