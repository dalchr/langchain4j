import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const backendHost = process.env.VITE_BACKEND_HOST ?? 'http://127.0.0.1:8080'

export default defineConfig({
  base: '/app/',
  plugins: [react()],
  server: {
    host: true,
    allowedHosts: ['finndet-host02'],
    proxy: {
      '/auth': {
        target: backendHost,
      },
      '/demo': {
        target: backendHost,
        ws: true,
      },
    },
  },
})