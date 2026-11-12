import cljf from 'clojurific/vite';

// The Worker, one bundle: `vite build --config vite.worker.config.mjs`
// writes dist/worker/example.js with the fetch handler as its default
// export and the Durable Object class, which wrangler.jsonc names.
export default {
  // public/ belongs to the client's build
  publicDir: false,
  plugins: [
    cljf({
      outputDir: 'target/worker',
      compilerOptions: {
        'js-entries': {
          example: {
            exports: {
              default: "'example.worker/handler",
              Page: "'example.worker/Page",
            },
          },
        },
      },
      manifest: false,
    }),
  ],
  build: {
    ssr: true,
    outDir: 'dist/worker',
    rolldownOptions: {
      input: {},
      external: [/^node:/, /^cloudflare:/],
      output: { entryFileNames: '[name].js', codeSplitting: false },
    },
  },
  ssr: {
    target: 'webworker',
    noExternal: true,
  },
};
