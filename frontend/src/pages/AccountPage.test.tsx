import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { AccountPage } from './AccountPage';
import { meBody, server } from '../test/server';
import { renderApp } from '../test/render';

describe('AccountPage', () => {
  it('savesDigestSettings', async () => {
    let patch: unknown = null;
    server.use(
      http.get('/api/me', () => HttpResponse.json(meBody)),
      http.patch('/api/me', async ({ request }) => {
        patch = await request.json();
        return HttpResponse.json({ ...meBody, digestFrequency: 'WEEKLY' });
      }),
    );
    renderApp(<AccountPage />, '/account');
    await userEvent.click(await screen.findByRole('textbox', { name: 'Digest frequency' }));
    await userEvent.click(await screen.findByRole('option', { name: 'Weekly' }));
    await userEvent.click(screen.getByRole('button', { name: 'Save settings' }));
    await waitFor(() => expect(patch).not.toBeNull());
    expect(patch).toMatchObject({ digestFrequency: 'WEEKLY', digestHour: 7, digestWeekday: 1 });
    expect((patch as { timeZone: string }).timeZone).toBeTruthy();
    expect(await screen.findByText('Settings saved')).toBeInTheDocument();
  });

  it('sends a link when the email changes', async () => {
    let email: unknown = null;
    server.use(
      http.get('/api/me', () => HttpResponse.json(meBody)),
      http.post('/api/me/email', async ({ request }) => { email = await request.json(); return HttpResponse.json({}, { status: 202 }); }),
    );
    renderApp(<AccountPage />, '/account');
    const input = await screen.findByLabelText(/^Email/);
    await userEvent.clear(input);
    await userEvent.type(input, 'new@example.org');
    await userEvent.click(screen.getByRole('button', { name: 'Change email' }));
    expect(await screen.findByText(/We sent a link/)).toBeInTheDocument();
    expect(email).toEqual({ email: 'new@example.org' });
  });

  it('deleteNeedsTypedConfirmation', async () => {
    let deleted = 0;
    server.use(
      http.get('/api/me', () => HttpResponse.json(meBody)),
      http.delete('/api/me', () => { deleted++; return new HttpResponse(null, { status: 204 }); }),
    );
    renderApp(<AccountPage />, '/account');
    await userEvent.click(await screen.findByRole('button', { name: 'Delete account' }));
    const dialog = await screen.findByRole('dialog');
    const confirm = within(dialog).getByRole('button', { name: 'Delete my account' });
    expect(confirm).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText(/Type DELETE/), 'DELETE');
    expect(confirm).toBeEnabled();
    await userEvent.click(confirm);
    await waitFor(() => expect(deleted).toBe(1));
  });

  it('asks a signed-out visitor to sign in', async () => {
    server.use(http.get('/api/me', () => new HttpResponse(null, { status: 401 })));
    renderApp(<AccountPage />, '/account');
    expect(await screen.findByRole('link', { name: 'Sign in' })).toBeInTheDocument();
  });
});
