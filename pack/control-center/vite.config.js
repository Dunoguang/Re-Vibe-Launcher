import { defineConfig } from 'vite'
import { resolve } from 'path'

export default defineConfig({
  root: resolve(__dirname),
  base: './',
  build: {
    outDir: resolve(__dirname, 'dist'),
    emptyOutDir: true,
    assetsInlineLimit: 100000000,
    cssCodeSplit: false,
    assetsDir: 'cc-assets',
    rollupOptions: {
      input: resolve(__dirname, 'control_center.html'),
      output: {
        inlineDynamicImports: true,
        manualChunks: undefined,
      },
    },
  },
})
