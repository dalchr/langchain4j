import { useEffect, useState } from 'react'
import { useMessages } from '../hooks/useMessages'
import { fetchCities, publishMessage, searchCities } from '../services/demoApi'
import type { City } from '../types/demo'

export function App() {
  const [cities, setCities] = useState<City[]>([])
  const [status, setStatus] = useState('Loading cities…')
  const [publishing, setPublishing] = useState(false)
  const [query, setQuery] = useState('')
  const [searchResults, setSearchResults] = useState<City[]>([])
  const { messages, connected } = useMessages()

  const loadCities = async () => {
    try {
      setCities(await fetchCities())
      setStatus('Cities loaded')
    } catch (error) {
      setStatus(error instanceof Error ? error.message : 'Unable to load cities')
    }
  }

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

  return (
    <main className="app-shell">
      <header className="hero">
        <p className="eyebrow">Ktor application</p>
        <h1>React frontend</h1>
        <p className="subtitle">A lightweight presentation layer for the existing city and RabbitMQ demo.</p>
      </header>
      <section className="card">
        <div className="section-heading"><h2>Cities</h2><button onClick={() => void loadCities()}>Refresh</button></div>
        <input aria-label="Search cities by name" placeholder="Search by name" value={query} onChange={(event) => setQuery(event.target.value)} />
        {query.trim() ? (searchResults.length === 0 ? <p className="muted">No matching cities.</p> : <ul>{searchResults.map((city) => <li key={city.id}>{city.name} — {city.population.toLocaleString()}</li>)}</ul>) : (cities.length === 0 ? <p className="muted">No cities found.</p> : <ul>{cities.map((city) => <li key={city.id}>{city.name} — {city.population.toLocaleString()}</li>)}</ul>)}
      </section>
      <section className="card">
        <div className="section-heading"><h2>RabbitMQ messages</h2><span className={connected ? 'connected' : 'muted'}>{connected ? 'Connected' : 'Reconnecting…'}</span></div>
        <button onClick={() => void handlePublish()} disabled={publishing}>{publishing ? 'Publishing…' : 'Publish message'}</button>
        <p className="muted">{status}</p>
        <pre>{messages.length ? messages.map((message) => message.body).join('\n') : 'Waiting for messages…'}</pre>
      </section>
    </main>
  )
}