import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { AdminLayout } from './AdminLayout';
import { renderApp } from '../../test/render';
import { meBody, server } from '../../test/server';

describe('AdminLayout', () => {
  it('nonAdminSeesNotFound', async () => {
    server.use(http.get('/api/me', () => HttpResponse.json(meBody)));
    renderApp(<AdminLayout />, '/admin');
    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Admin' })).toBeNull();
  });
  it('adminSeesTheSubMenu', async () => {
    server.use(http.get('/api/me', () => HttpResponse.json({ ...meBody, admin: true })));
    renderApp(<AdminLayout />, '/admin');
    expect(await screen.findByRole('link', { name: 'Layers' })).toHaveAttribute('href', '/admin/layers');
  });
});
