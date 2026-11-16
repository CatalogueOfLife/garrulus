import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { SignInVerifyPage } from './SignInVerifyPage';
import { server } from '../test/server';
import { renderApp } from '../test/render';

const app = () => (
  <Routes>
    <Route path="/signin/verify" element={<SignInVerifyPage />} />
    <Route path="*" element={<div>elsewhere</div>} />
  </Routes>
);

describe('SignInVerifyPage', () => {
  it('postsTokenOnceAndRedirects (only after the click)', async () => {
    document.cookie = 'XSRF-TOKEN=xyz; path=/';
    sessionStorage.setItem('garrulus.next', '/hits');
    let calls = 0;
    let body = '';
    let xsrf: string | null = null;
    server.use(
      http.get('/api/auth/config', () => HttpResponse.json({ orcid: false })),
      http.post('/login/ott', async ({ request }) => {
        calls++;
        body = await request.text();
        xsrf = request.headers.get('x-xsrf-token');
        return HttpResponse.json({ userId: 1 });
      }),
    );
    renderApp(app(), '/signin/verify?token=tok1');
    await new Promise((r) => setTimeout(r, 50));
    expect(calls).toBe(0);
    await userEvent.click(await screen.findByRole('button', { name: 'Sign in to Garrulus' }));
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/hits'));
    expect(calls).toBe(1);
    expect(body).toBe('token=tok1');
    expect(xsrf).toBe('xyz');
  });

  it('ignores an unsafe next', async () => {
    sessionStorage.setItem('garrulus.next', '//evil.example');
    server.use(
      http.get('/api/auth/config', () => HttpResponse.json({ orcid: false })),
      http.post('/login/ott', () => HttpResponse.json({ userId: 1 })),
    );
    renderApp(app(), '/signin/verify?token=t');
    await userEvent.click(await screen.findByRole('button', { name: 'Sign in to Garrulus' }));
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/));
  });

  it('shows an error for an invalid link', async () => {
    server.use(
      http.get('/api/auth/config', () => HttpResponse.json({ orcid: false })),
      http.post('/login/ott', () => new HttpResponse(null, { status: 401 })),
    );
    renderApp(app(), '/signin/verify?token=bad');
    await userEvent.click(await screen.findByRole('button', { name: 'Sign in to Garrulus' }));
    expect(await screen.findByText(/invalid or has expired/)).toBeInTheDocument();
  });
});
