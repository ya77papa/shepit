import { defineConfig, type Plugin } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join, relative } from 'node:path';

// Версія = коміт, з якого зібрано (CI передає SHEPIT_COMMIT). Локально — "dev".
const COMMIT = (process.env.SHEPIT_COMMIT || 'dev').slice(0, 40);

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    return statSync(p).isDirectory() ? walk(p) : [p];
  });
}

/**
 * Після збірки: service worker зі списком файлів і version.json з хешем збірки.
 * Хеш збірки (SHA-256 над хешами всіх файлів) CI публікує у звіті — будь-хто може
 * звірити, що сервер роздає саме те, що зібрано з GitHub.
 */
function shepitPwa(): Plugin {
  let outDir = 'dist';
  return {
    name: 'shepit-pwa',
    apply: 'build',
    configResolved(c) {
      outDir = c.build.outDir;
    },
    closeBundle() {
      const files = walk(outDir)
        .map((p) => '/' + relative(outDir, p).split('\\').join('/'))
        .filter((p) => p !== '/sw.js' && p !== '/version.json')
        .sort();
      const hashes = files.map((f) => {
        const h = createHash('sha256').update(readFileSync(join(outDir, f))).digest('hex');
        return `${h}  ${f}`;
      });
      const build = createHash('sha256').update(hashes.join('\n')).digest('hex');
      const version = `${COMMIT.slice(0, 12)}-${build.slice(0, 12)}`;
      const sw = readFileSync('sw.template.js', 'utf8')
        .replace('__VERSION__', JSON.stringify(version))
        .replace('__FILES__', JSON.stringify(['/', ...files.filter((f) => f !== '/index.html' && !f.startsWith('/admin/')), '/index.html']));
      writeFileSync(join(outDir, 'sw.js'), sw);
      writeFileSync(join(outDir, 'version.json'), JSON.stringify({ commit: COMMIT, build, version, files: hashes }, null, 2));
      console.log(`shepit: версія ${version}, хеш збірки ${build}`);
    },
  };
}

export default defineConfig({
  plugins: [svelte(), shepitPwa()],
  define: { __SHEPIT_COMMIT__: JSON.stringify(COMMIT) },
  build: {
    target: 'es2022',
    sourcemap: false,
    // Дві сторінки: застосунок і адмін-панель (/admin/).
    rollupOptions: { input: { main: 'index.html', admin: 'admin/index.html' } },
  },
  // Розробка: API проксюємо на локальний сервер (cargo run -p shepit-server).
  server: {
    proxy: {
      '/v0': 'http://127.0.0.1:8443',
      '/health': 'http://127.0.0.1:8443',
    },
  },
});
