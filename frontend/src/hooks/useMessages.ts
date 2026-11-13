import { useEffect, useRef, useState } from 'react'
import type { WsMessage } from '../types/demo'

const reconnectDelayMs = 3000

function websocketUrl(): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/demo/ws`
}

export function useMessages(onCityChanged?: () => void): { messages: WsMessage[]; connected: boolean } {
  const [messages, setMessages] = useState<WsMessage[]>([])
  const [connected, setConnected] = useState(false)
  const socketRef = useRef<WebSocket | null>(null)

  useEffect(() => {
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
          if (message.body.includes('city-changed')) onCityChanged?.()
          else if (typeof message.body === 'string') setMessages((current) => [...current, message])
        } catch {
          // Ignore malformed messages while keeping the connection alive.
        }
      }
      socket.onclose = () => {
        setConnected(false)
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
  }, [onCityChanged])

  return { messages, connected }
}