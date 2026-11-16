import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { SignInPage } from './SignInPage';
import { authConfigHandler, server } from '../test/server';
import { renderApp } from '../test/render';

describe('SignInPage', () => {
  it('hidesOrcidWhenOff', async () => {
    server.use(authConfigHandler(false));
    renderApp(<SignInPage />, '/signin');
    expect(await screen.findByRole('button', { name: /Email me a sign-in link/ })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Continue with ORCID/ })).toBeNull();
  });

  it('showsOrcidWhenOn', async () => {
    server.use(authConfigHandler(true));
    renderApp(<SignInPage />, '/signin');
    expect(await screen.findByRole('link', { name: /Continue with ORCID/ })).toHaveAttribute('href', '/oauth2/authorization/orcid');
  });

  it('showsCheckInboxAfterSubmit and remembers next', async () => {
    server.use(authConfigHandler(false), http.post('/api/auth/link', () => HttpResponse.json({}, { status: 202 })));
    renderApp(<SignInPage />, '/signin?next=/hits');
    await userEvent.type(await screen.findByLabelText(/^Email/), 'a@example.org');
    await userEvent.click(screen.getByRole('button', { name: /Email me a sign-in link/ }));
    expect(await screen.findByText('Check your inbox')).toBeInTheDocument();
    expect(sessionStorage.getItem('garrulus.next')).toBe('/hits');
  });

  it('shows an ORCID error', async () => {
    server.use(authConfigHandler(true));
    renderApp(<SignInPage />, '/signin?error=orcid');
    expect(await screen.findByText(/ORCID sign-in did not work/)).toBeInTheDocument();
  });
});
