import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { App } from './App';
import { server, emptyPage, meBody, signedOut, taxGroupHandler, xrHandler, sourcesHandler } from './test/server';
import { renderApp } from './test/render';

describe('App', () => {
  it('shows the brand and the search page, titled Garrulus', async () => {
    server.use(signedOut, xrHandler, sourcesHandler(), http.get('/api/works', () => HttpResponse.json(emptyPage)), taxGroupHandler);
    renderApp(<App />);
    expect(screen.getByRole('heading', { name: 'Garrulus' })).toBeInTheDocument();
    expect(await screen.findByText(/No works match/)).toBeInTheDocument();
    expect(document.title).toBe('Garrulus — Search');
  });

  it('routes /source/:id to the journal page', async () => {
    server.use(signedOut, xrHandler, taxGroupHandler, http.get('/api/sources/5', () => HttpResponse.json({
      id: 5, title: 'Zootaxa', tier: 'CORE', subfields: [], taxgroups: [], codes: [], years: [], recentWorks: [],
    })));
    renderApp(<App />, '/source/5');
    expect(await screen.findByRole('heading', { name: 'Zootaxa' })).toBeInTheDocument();
    expect(document.title).toBe('Garrulus — Zootaxa');
  });

  it('shows Sign in when signed out and routes /unsubscribe', async () => {
    server.use(signedOut);
    renderApp(<App />, '/unsubscribe?t=x');
    expect(await screen.findByRole('link', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Turn off digest emails' })).toBeInTheDocument();
  });

  it('shows the account menu when signed in', async () => {
    server.use(http.get('/api/me', () => HttpResponse.json(meBody)));
    renderApp(<App />, '/unsubscribe?t=x');
    expect(await screen.findByRole('button', { name: 'A' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Sign in' })).toBeNull();
  });
});
