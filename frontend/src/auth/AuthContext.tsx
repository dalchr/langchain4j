import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { ApiError, fetchAuthenticationStatus, login as loginRequest, logout as logoutRequest } from '../services/demoApi'
import type { AuthenticatedUser } from '../types/auth'

interface AuthContextValue {
  user: AuthenticatedUser | null
  loading: boolean
  loginOpen: boolean
  loginReason: string
  openLogin: (reason?: string) => void
  closeLogin: () => void
  login: (username: string, password: string) => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthenticatedUser | null>(null)
  const [loading, setLoading] = useState(true)
  const [loginOpen, setLoginOpen] = useState(false)
  const [loginReason, setLoginReason] = useState('Log in to continue.')

  const openLogin = useCallback((reason = 'Log in to continue.') => {
    setLoginReason(reason)
    setLoginOpen(true)
  }, [])
  const closeLogin = useCallback(() => setLoginOpen(false), [])

  useEffect(() => {
    let active = true
    void fetchAuthenticationStatus()
      .then((response) => { if (active) setUser(response.user) })
      .catch(() => { if (active) setUser(null) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [])

  useEffect(() => {
    const handleAuthenticationRequired = () => {
      setUser(null)
      openLogin('Your session has expired or you need to log in to continue.')
    }
    window.addEventListener('app-auth-required', handleAuthenticationRequired)
    return () => window.removeEventListener('app-auth-required', handleAuthenticationRequired)
  }, [openLogin])

  useEffect(() => {
    if (!user) return
    const interval = window.setInterval(() => {
      void fetchAuthenticationStatus()
        .then((response) => {
          if (!response.authenticated) window.dispatchEvent(new Event('app-auth-required'))
        })
        .catch(() => window.dispatchEvent(new Event('app-auth-required')))
    }, 30_000)
    return () => window.clearInterval(interval)
  }, [user])

  const login = useCallback(async (username: string, password: string) => {
    const authenticatedUser = await loginRequest(username, password)
    setUser(authenticatedUser)
    setLoginOpen(false)
  }, [])

  const logout = useCallback(async () => {
    try {
      await logoutRequest()
    } finally {
      setUser(null)
    }
  }, [])

  const value = useMemo(() => ({
    user,
    loading,
    loginOpen,
    loginReason,
    openLogin,
    closeLogin,
    login,
    logout,
  }), [user, loading, loginOpen, loginReason, openLogin, closeLogin, login, logout])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used within AuthProvider')
  return context
}

export function authenticationErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 401) return 'Please log in to continue.'
  return error instanceof Error ? error.message : 'Authentication failed.'
}