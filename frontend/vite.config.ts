import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  base: '/app/',
  plugins: [react()],
  server: {
    proxy: {
      '/auth': {
        target: 'http://localhost:8080',
      },
      '/demo': {
        target: 'http://localhost:8080',
        ws: true,
      },
    },
  },
})