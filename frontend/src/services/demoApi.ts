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