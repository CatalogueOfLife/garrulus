import { Blob, File } from 'node:buffer';
import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach, beforeAll, vi } from 'vitest';
import { cleanup } from '@testing-library/react';
import { server } from './server';

// vitest's jsdom environment swaps in jsdom's Blob/File, which Node's native fetch does not
// recognise. Point the globals back at Node's Blob/File plus undici's fetch family (as Blixa does),
// before undici is first imported.
Object.assign(globalThis, { Blob, File });
const { fetch, FormData, Headers, Request, Response } = await import('undici');
Object.assign(globalThis, { fetch, FormData, Headers, Request, Response });

// Mantine calls matchMedia; jsdom doesn't implement it.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }),
});

// jsdom lacks ResizeObserver, used by Mantine.
window.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
};

window.HTMLElement.prototype.scrollIntoView = vi.fn();

// Node's experimental localStorage warns without --localstorage-file; supply an in-memory Storage.
const store = new Map<string, string>();
const memoryStorage = {
  get length() {
    return store.size;
  },
  clear: () => store.clear(),
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => void store.set(k, String(v)),
  removeItem: (k: string) => void store.delete(k),
  key: (i: number) => Array.from(store.keys())[i] ?? null,
};
Object.defineProperty(window, 'localStorage', { writable: true, value: memoryStorage });
Object.assign(globalThis, { localStorage: memoryStorage });

afterEach(() => cleanup());
afterEach(() => store.clear());

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
