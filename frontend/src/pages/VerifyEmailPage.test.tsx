import { screen } from '@testing-library/react';
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
    let calls = 0;
    server.use(http.post('/api/auth/verify-email', () => { calls++; return HttpResponse.json({ outcome }); }));
    renderApp(<VerifyEmailPage />, '/verify-email?token=t');
    expect(calls).toBe(0);
    await userEvent.click(screen.getByRole('button', { name: 'Confirm this address' }));
    expect(await screen.findByText(text)).toBeInTheDocument();
    expect(calls).toBe(1);
  });

  it('shows an error for a bad token', async () => {
    server.use(http.post('/api/auth/verify-email', () => new HttpResponse(null, { status: 400 })));
    renderApp(<VerifyEmailPage />, '/verify-email?token=t');
    await userEvent.click(screen.getByRole('button', { name: 'Confirm this address' }));
    expect(await screen.findByText(/invalid or has expired/)).toBeInTheDocument();
  });
});
