import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig(({ mode }) => {
  // En dev, le backend Ktor tourne sur :8080 (TEXTO_BACKEND pour un autre) ; Vite lui relaie l'API, l'auth et les fichiers servis.
  const backend = loadEnv(mode, ".", "TEXTO_").TEXTO_BACKEND || "http://localhost:8080";
  return {
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
  };
});
