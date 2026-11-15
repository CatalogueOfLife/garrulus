import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { App } from './App';
import { server, emptyPage, taxGroupHandler, xrHandler } from './test/server';
import { renderApp } from './test/render';

describe('App', () => {
  it('shows the brand and the search page, titled Garrulus', async () => {
    server.use(xrHandler, http.get('/api/works', () => HttpResponse.json(emptyPage)), taxGroupHandler);
    renderApp(<App />);
    expect(screen.getByRole('heading', { name: 'Garrulus' })).toBeInTheDocument();
    expect(await screen.findByText(/No works match/)).toBeInTheDocument();
    expect(document.title).toBe('Garrulus — Search');
  });

  it('has the placeholder routes', async () => {
    server.use(xrHandler);
    renderApp(<App />, '/work/5');
    expect(await screen.findByRole('heading', { name: 'Work' })).toBeInTheDocument();
    expect(document.title).toBe('Garrulus — Work 5');
  });
});
