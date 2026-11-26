import { defineConfig, loadEnv } from 'vite'
import { fileURLToPath } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { localApi } from './server/local-api.js'

export default defineConfig(({ mode }) => ({
  base: './',
  resolve: {
    alias: {
      '@title-font': fileURLToPath(new URL(mode === 'android' ? './src/android-title-font.css' : './src/title-font.css', import.meta.url)),
    },
  },
  plugins: [vue(), ...(mode === 'android' ? [] : [localApi(loadEnv(mode, process.cwd(), ''))])],
  build: mode === 'android' ? { target: 'chrome110' } : undefined,
}))
