import { defineConfig } from 'vite'
import { resolve } from 'path'

export default defineConfig({
  root: resolve(__dirname, 'src'),
  base: './',
  build: {
    outDir: resolve(__dirname, 'dist'),
    emptyOutDir: true,
    assetsInlineLimit: 100000000,
    cssCodeSplit: false,
    rollupOptions: {
      input: resolve(__dirname, 'src/index.html'),
      output: {
        inlineDynamicImports: true,
        manualChunks: undefined,
      },
    },
  },
})
