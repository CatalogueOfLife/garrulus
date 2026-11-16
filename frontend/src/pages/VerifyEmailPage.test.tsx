import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { VerifyEmailPage } from './VerifyEmailPage';
import { server } from '../test/server';
import { renderApp } from '../test/render';

describe('VerifyEmailPage', () => {
  it.each([
    ['SET', 'Address confirmed'],
    ['MERGED', 'Your accounts were merged'],
    ['REFUSED_OTHER_ORCID', /linked to another ORCID iD/],
  ])('shows the outcome %s after the click', async (outcome, text) => {
    document.cookie = 'XSRF-TOKEN=vx1; path=/';
    let calls = 0;
    let xsrf: string | null = null;
    server.use(
      http.get('/api/auth/config', () => HttpResponse.json({ orcid: false })),
      http.post('/api/auth/verify-email', ({ request }) => {
        calls++;
        xsrf = request.headers.get('x-xsrf-token');
        return HttpResponse.json({ outcome });
      }),
    );
    renderApp(<VerifyEmailPage />, '/verify-email?token=t');
    expect(calls).toBe(0);
    const button = await screen.findByRole('button', { name: 'Confirm this address', hidden: false });
    await waitFor(() => expect(button).toBeEnabled());
    await userEvent.click(button);
    expect(await screen.findByText(text)).toBeInTheDocument();
    expect(calls).toBe(1);
    expect(xsrf).toBe('vx1');
  });

  it('shows an error for a bad token', async () => {
    server.use(
      http.get('/api/auth/config', () => HttpResponse.json({ orcid: false })),
      http.post('/api/auth/verify-email', () => new HttpResponse(null, { status: 400 })),
    );
    renderApp(<VerifyEmailPage />, '/verify-email?token=t');
    await userEvent.click(await screen.findByRole('button', { name: 'Confirm this address', hidden: false }));
    expect(await screen.findByText(/invalid or has expired/)).toBeInTheDocument();
  });

  it('says to retry on a 403, not "expired"', async () => {
    server.use(
      http.get('/api/auth/config', () => HttpResponse.json({ orcid: false })),
      http.post('/api/auth/verify-email', () => new HttpResponse(null, { status: 403 })),
    );
    renderApp(<VerifyEmailPage />, '/verify-email?token=t');
    await userEvent.click(await screen.findByRole('button', { name: 'Confirm this address' }));
    expect(await screen.findByText(/try again/)).toBeInTheDocument();
    expect(screen.queryByText(/expired/)).toBeNull();
  });
});
