export interface AuthenticatedUser {
  id: string
  username: string
  displayName: string
  roles: string[]
  permissions: string[]
}

export interface AuthenticationResponse {
  authenticated: boolean
  user: AuthenticatedUser | null
}