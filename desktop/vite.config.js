import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { localApi } from './server/local-api.js'

export default defineConfig(({ mode }) => ({
  base: './',
  plugins: [vue(), localApi(loadEnv(mode, process.cwd(), ''))],
}))
