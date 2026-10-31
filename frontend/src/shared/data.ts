import { useQuery } from "@tanstack/react-query";
import { api } from "../api";

export const useData = <T>(path: string) =>
  useQuery<T>({ queryKey: [path], queryFn: () => api<T>(path) });

export const short = (value: string) =>
  value.length > 22 ? value.slice(0, 13) + "…" + value.slice(-5) : value;

export const message = (error: unknown) =>
  error instanceof Error
    ? error.message
    : "The request could not be completed.";

export const localInput = (time: Date) => {
  const d = new Date(time.getTime() - time.getTimezoneOffset() * 60000);
  return d.toISOString().slice(0, 16);
};

export const initialInterval = () => ({
  start: localInput(new Date(Date.now() - 86400000)),
  end: localInput(new Date(Date.now() + 60000)),
});
