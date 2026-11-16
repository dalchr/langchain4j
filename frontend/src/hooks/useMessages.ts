import { useEffect, useRef, useState } from 'react'
import type { WsMessage } from '../types/demo'

const reconnectDelayMs = 3000

function websocketUrl(): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/demo/ws`
}

export function useMessages(onCityChanged?: () => void, enabled = true): { messages: WsMessage[]; connected: boolean } {
  const [messages, setMessages] = useState<WsMessage[]>([])
  const [connected, setConnected] = useState(false)
  const socketRef = useRef<WebSocket | null>(null)

  useEffect(() => {
    if (!enabled) {
      setConnected(false)
      socketRef.current?.close()
      socketRef.current = null
      return
    }
    let active = true
    let reconnectTimer: number | undefined

    const connect = () => {
      if (!active) return
      const socket = new WebSocket(websocketUrl())
      socketRef.current = socket
      socket.onopen = () => setConnected(true)
      socket.onmessage = (event) => {
        try {
          const message = JSON.parse(event.data) as WsMessage
          const body = typeof message.body === 'string' ? message.body : JSON.stringify(message.body)
          try {
            const parsed = JSON.parse(body)
            // Structured event (has operation field or event === 'city-changed') → trigger city reload
            if (parsed.operation || parsed.event === 'city-changed') {
              onCityChanged?.()
            } else {
              setMessages((current) => [...current, message])
            }
          } catch {
            // Plain string message → check for "city-changed"
            if (body.includes('city-changed')) {
              onCityChanged?.()
            } else {
              setMessages((current) => [...current, message])
            }
          }
        } catch {
          // Ignore malformed messages while keeping the connection alive.
        }
      }
      socket.onclose = (event) => {
        setConnected(false)
        if (event.code === 1008) {
          window.dispatchEvent(new Event('app-auth-required'))
          return
        }
        if (active) reconnectTimer = window.setTimeout(connect, reconnectDelayMs)
      }
      socket.onerror = () => socket.close()
    }

    connect()
    return () => {
      active = false
      if (reconnectTimer !== undefined) window.clearTimeout(reconnectTimer)
      socketRef.current?.close()
      socketRef.current = null
    }
  }, [onCityChanged, enabled])

  return { messages, connected }
}