import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { App } from '../App';
import { meBody, server, signedOut } from '../test/server';
import { renderApp } from '../test/render';
import { rememberNext, takeNext } from './useMe';

const KEY = 'garrulus.next';

describe('remembered next', () => {
  it.each(['//x', '\\x', 'https://x', ''])('ignores unsafe value %j on write', (v) => {
    rememberNext(v);
    expect(localStorage.getItem(KEY)).toBeNull();
  });

  it('ignores unsafe and expired values on read, and clears them', () => {
    localStorage.setItem(KEY, JSON.stringify({ path: '//x', ts: Date.now() }));
    expect(takeNext()).toBeNull();
    localStorage.setItem(KEY, JSON.stringify({ path: '/hits', ts: Date.now() - 61 * 60 * 1000 }));
    expect(takeNext()).toBeNull();
    expect(localStorage.getItem(KEY)).toBeNull();
  });

  it('navigates once to the stored next when the user turns up signed in', async () => {
    rememberNext('/unsubscribe?t=q');
    server.use(http.get('/api/me', () => HttpResponse.json(meBody)),
      http.get('/api/works', () => HttpResponse.json({ total: 0, limit: 20, offset: 0, results: [], facets: {} })),
      http.get('/api/xr', () => HttpResponse.json({ key: 1, alias: 'x' })),
      http.get('/api/vocab/taxgroup', () => HttpResponse.json([])));
    renderApp(<App />, '/');
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/unsubscribe?t=q'));
    expect(localStorage.getItem(KEY)).toBeNull();
  });

  it('does not navigate while signed out and keeps the value', async () => {
    rememberNext('/account');
    server.use(signedOut, http.get('/api/works', () => HttpResponse.json({ total: 0, limit: 20, offset: 0, results: [], facets: {} })),
      http.get('/api/xr', () => HttpResponse.json({ key: 1, alias: 'x' })),
      http.get('/api/vocab/taxgroup', () => HttpResponse.json([])));
    renderApp(<App />, '/');
    await screen.findByRole('link', { name: 'Sign in' });
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/);
    expect(localStorage.getItem(KEY)).not.toBeNull();
  });

  it('ignores an expired value after sign-in', async () => {
    localStorage.setItem(KEY, JSON.stringify({ path: '/account', ts: Date.now() - 2 * 3600 * 1000 }));
    server.use(http.get('/api/me', () => HttpResponse.json(meBody)),
      http.get('/api/works', () => HttpResponse.json({ total: 0, limit: 20, offset: 0, results: [], facets: {} })),
      http.get('/api/xr', () => HttpResponse.json({ key: 1, alias: 'x' })),
      http.get('/api/vocab/taxgroup', () => HttpResponse.json([])));
    renderApp(<App />, '/');
    await screen.findByRole('button', { name: 'A' });
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/);
  });
});
