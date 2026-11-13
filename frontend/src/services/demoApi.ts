import type { City } from '../types/demo'
import type { AuthenticationResponse, AuthenticatedUser } from '../types/auth'

const csrfHeaderName = 'X-CSRF-Token'
let csrfToken: string | null = null
let csrfTokenRequest: Promise<string> | null = null

export class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message)
    this.name = 'ApiError'
  }
}

async function getCsrfToken(): Promise<string> {
  if (csrfToken) return csrfToken
  if (!csrfTokenRequest) {
    csrfTokenRequest = fetch('/auth/csrf', { credentials: 'same-origin' })
      .then(async (response) => {
        if (!response.ok) throw new ApiError(`Unable to initialize request security (${response.status})`, response.status)
        const result = await response.json() as { token: string }
        csrfToken = result.token
        return result.token
      })
      .finally(() => { csrfTokenRequest = null })
  }
  return csrfTokenRequest
}

async function apiRequest(url: string, options: RequestInit = {}): Promise<Response> {
  const method = (options.method ?? 'GET').toUpperCase()
  const headers = new Headers(options.headers)
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) headers.set(csrfHeaderName, await getCsrfToken())
  const response = await fetch(url, { ...options, headers, credentials: 'same-origin' })
  if (response.status === 401) window.dispatchEvent(new Event('app-auth-required'))
  return response
}

export async function fetchAuthenticationStatus(): Promise<AuthenticationResponse> {
  const response = await apiRequest('/auth/me')
  if (!response.ok) throw new ApiError(`Unable to check authentication (${response.status})`, response.status)
  return response.json() as Promise<AuthenticationResponse>
}

export async function login(username: string, password: string): Promise<AuthenticatedUser> {
  const response = await apiRequest('/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  if (!response.ok) {
    const responseText = await response.text()
    let message = response.status === 401 ? 'Invalid username or password.' : `Login failed (${response.status})`
    try {
      message = (JSON.parse(responseText) as { message?: string }).message ?? responseText ?? message
    } catch {
      if (responseText) message = responseText
    }
    throw new ApiError(message, response.status)
  }
  const result = await response.json() as AuthenticationResponse
  if (!result.authenticated || !result.user) throw new ApiError('Login did not return an authenticated user.', response.status)
  return result.user
}

export async function logout(): Promise<void> {
  const response = await apiRequest('/auth/logout', { method: 'POST' })
  if (!response.ok) throw new ApiError(`Logout failed (${response.status})`, response.status)
}

export async function fetchCities(signal?: AbortSignal): Promise<City[]> {
  const response = await apiRequest('/demo/cities', { signal })
  if (!response.ok) throw new ApiError(`Unable to load cities (${response.status})`, response.status)
  return response.json() as Promise<City[]>
}

export async function searchCities(name: string, signal?: AbortSignal): Promise<City[]> {
  const response = await apiRequest(`/demo/cities/search?name=${encodeURIComponent(name)}`, { signal })
  if (!response.ok) throw new ApiError(`Unable to search cities (${response.status})`, response.status)
  return response.json() as Promise<City[]>
}

export async function publishMessage(): Promise<string> {
  const response = await apiRequest('/demo/rabbitmq/publish', { method: 'POST' })
  const text = await response.text()
  if (!response.ok) throw new ApiError(text || `Publish failed (${response.status})`, response.status)
  return text
}

export async function saveCity(city: { name: string; population: number }, id?: number): Promise<string> {
  const response = await apiRequest(id === undefined ? '/demo/cities' : `/demo/cities/${id}`, { method: id === undefined ? 'POST' : 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ operation: id === undefined ? 'create' : 'update', ...city }) })
  const text = await response.text()
  if (!response.ok) throw new ApiError(text || `Save failed (${response.status})`, response.status)
  return text
}

export async function deleteCity(id: number): Promise<string> {
  const response = await apiRequest(`/demo/cities/${id}`, { method: 'DELETE' })
  const text = await response.text()
  if (!response.ok) throw new ApiError(text || `Delete failed (${response.status})`, response.status)
  return text
}

export interface AgentStreamEvent {
  type: 'token' | 'done' | 'error'
  content?: string
  error?: string
}

export async function resetAgentConversation(conversationId: string): Promise<void> {
  const response = await apiRequest('/demo/agent/conversation/reset', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ conversationId }),
  })
  if (!response.ok) {
    throw new ApiError((await response.text()) || `Conversation reset failed (${response.status})`, response.status)
  }
}

export async function streamAgentMessage(
  conversationId: string,
  message: string,
  onEvent: (event: AgentStreamEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const response = await apiRequest('/demo/agent/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ conversationId, message }),
    signal,
  })
  if (!response.ok) {
    throw new ApiError((await response.text()) || `Assistant request failed (${response.status})`, response.status)
  }
  if (!response.body) throw new Error('Streaming responses are not supported by this browser')

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let receivedTerminalEvent = false
  const dispatch = (frame: string) => {
    const data = frame.split(/\r?\n/)
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trimStart())
      .join('\n')
    if (data) {
      const event = JSON.parse(data) as AgentStreamEvent
      if (event.type === 'done' || event.type === 'error') receivedTerminalEvent = true
      onEvent(event)
    }
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
  if (!receivedTerminalEvent) throw new Error('The assistant response stream ended unexpectedly. Please try again.')
}