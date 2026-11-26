import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { localApi } from './server/local-api.js'

export default defineConfig(({ mode }) => ({
  base: './',
  plugins: [vue(), ...(mode === 'android' ? [] : [localApi(loadEnv(mode, process.cwd(), ''))])],
  build: mode === 'android' ? { target: 'chrome110' } : undefined,
}))
