import { fileURLToPath, URL } from 'node:url'

import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig, type Plugin } from 'vite'

function canonicalV3Redirect(): Plugin {
  return {
    name: 'canonical-v3-redirect',
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        if (request.url === '/' || request.url === '') {
          response.statusCode = 302
          response.setHeader('Location', '/v3/')
          response.end()
          return
        }
        next()
      })
    },
    configurePreviewServer(server) {
      server.middlewares.use((request, response, next) => {
        if (request.url === '/' || request.url === '') {
          response.statusCode = 302
          response.setHeader('Location', '/v3/')
          response.end()
          return
        }
        next()
      })
    },
  }
}

export default defineConfig({
  base: '/v3/',
  plugins: [canonicalV3Redirect(), react(), tailwindcss()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5174,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
