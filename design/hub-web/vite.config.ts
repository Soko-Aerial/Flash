import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// A design prototype, not a product: it serves from this folder only, binds to localhost by
// default and never talks to the network. `npm run dev` is the whole story.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: { port: 5180, open: false },
  build: { outDir: 'dist', sourcemap: true },
})
