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