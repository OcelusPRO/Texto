import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Route, Routes } from "react-router";
import "./index.css";
import "./lib/i18n";
import { Layout } from "./components/Layout";
import { ToastProvider } from "./components/ui";
import { AdminPage } from "./pages/AdminPage";
import { Dashboard } from "./pages/Dashboard";
import { EditPostPage, HistoryPage, NewPostPage } from "./pages/EditorPages";
import { NotFound } from "./pages/NotFound";
import { PostPage } from "./pages/PostPage";
import { HomePage, LoginPage, ProfilePage, SettingsPage } from "./pages/UserPages";

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } },
});

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <BrowserRouter>
          <Routes>
            <Route element={<Layout />}>
              <Route index element={<HomePage />} />
              <Route path="login" element={<LoginPage />} />
              <Route path="new" element={<NewPostPage />} />
              <Route path="dashboard" element={<Dashboard />} />
              <Route path="settings" element={<SettingsPage />} />
              <Route path="admin" element={<AdminPage />} />
              <Route path="u/:handle/*" element={<ProfilePage />} />
              <Route path=":key" element={<PostPage />} />
              <Route path=":key/edit" element={<EditPostPage />} />
              <Route path=":key/history" element={<HistoryPage />} />
              <Route path="*" element={<NotFound />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </ToastProvider>
    </QueryClientProvider>
  </StrictMode>,
);
