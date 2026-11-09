import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// En dev, le backend Ktor tourne sur :8080 ; Vite lui relaie l'API, l'auth et les fichiers servis.
const backend = "http://localhost:8080";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: Object.fromEntries(
      ["/api", "/login/discord", "/login/twitch", "/login/dev", "/raw", "/og", "/new-texto", "/author", "/factorio-assets", "/cli"].map(
        (p) => [p, { target: backend, changeOrigin: false }],
      ),
    ),
  },
  build: {
    outDir: "dist",
    emptyOutDir: true,
    chunkSizeWarningLimit: 2000,
  },
});
