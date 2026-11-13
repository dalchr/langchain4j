import type { City } from '../types/demo'

export async function fetchCities(signal?: AbortSignal): Promise<City[]> {
  const response = await fetch('/demo/cities', { signal })
  if (!response.ok) throw new Error(`Unable to load cities (${response.status})`)
  return response.json() as Promise<City[]>
}

export async function searchCities(name: string, signal?: AbortSignal): Promise<City[]> {
  const response = await fetch(`/demo/cities/search?name=${encodeURIComponent(name)}`, { signal })
  if (!response.ok) throw new Error(`Unable to search cities (${response.status})`)
  return response.json() as Promise<City[]>
}

export async function publishMessage(): Promise<string> {
  const response = await fetch('/demo/rabbitmq/publish', { method: 'POST' })
  const text = await response.text()
  if (!response.ok) throw new Error(text || `Publish failed (${response.status})`)
  return text
}

export async function saveCity(city: { name: string; population: number }, id?: number): Promise<string> {
  const response = await fetch(id === undefined ? '/demo/cities' : `/demo/cities/${id}`, { method: id === undefined ? 'POST' : 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ operation: id === undefined ? 'create' : 'update', ...city }) })
  const text = await response.text()
  if (!response.ok) throw new Error(text || `Save failed (${response.status})`)
  return text
}

export async function deleteCity(id: number): Promise<string> {
  const response = await fetch(`/demo/cities/${id}`, { method: 'DELETE' })
  const text = await response.text()
  if (!response.ok) throw new Error(text || `Delete failed (${response.status})`)
  return text
}

export interface AgentStreamEvent {
  type: 'token' | 'done' | 'error'
  content?: string
  error?: string
}

export async function resetAgentConversation(conversationId: string): Promise<void> {
  const response = await fetch('/demo/agent/conversation/reset', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ conversationId }),
  })
  if (!response.ok) {
    throw new Error((await response.text()) || `Conversation reset failed (${response.status})`)
  }
}

export async function streamAgentMessage(
  conversationId: string,
  message: string,
  onEvent: (event: AgentStreamEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const response = await fetch('/demo/agent/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ conversationId, message }),
    signal,
  })
  if (!response.ok) {
    throw new Error((await response.text()) || `Assistant request failed (${response.status})`)
  }
  if (!response.body) throw new Error('Streaming responses are not supported by this browser')

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  const dispatch = (frame: string) => {
    const data = frame.split(/\r?\n/)
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trimStart())
      .join('\n')
    if (data) onEvent(JSON.parse(data) as AgentStreamEvent)
  }

  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value, { stream: !done })
    const frames = buffer.split(/\r?\n\r?\n/)
    buffer = frames.pop() ?? ''
    frames.forEach(dispatch)
    if (done) break
  }
  if (buffer.trim()) dispatch(buffer)
}