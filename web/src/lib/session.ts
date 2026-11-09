import { useQuery } from "@tanstack/react-query";
import { api, ApiError, type Me } from "./api";

export function useMe() {
  return useQuery<Me | null>({
    queryKey: ["me"],
    queryFn: async () => {
      try {
        return await api.me();
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) return null;
        throw e;
      }
    },
    staleTime: 60_000,
  });
}

export function useConfig() {
  return useQuery({ queryKey: ["config"], queryFn: api.config, staleTime: Infinity });
}

export function loginUrl(provider: string, redirect = location.pathname + location.search, link = false) {
  const p = new URLSearchParams({ redirect });
  if (link) p.set("link", "1");
  return `/login/${provider}?${p}`;
}
