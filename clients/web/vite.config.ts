import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';

export default defineConfig({
  plugins: [svelte()],
  build: { target: 'es2022', sourcemap: false },
  // Розробка: API проксюємо на локальний сервер (cargo run -p shepit-server).
  server: {
    proxy: {
      '/v0': 'http://127.0.0.1:8443',
      '/health': 'http://127.0.0.1:8443',
    },
  },
});
