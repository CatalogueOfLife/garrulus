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

  it('routes /source/:id to the journal page', async () => {
    server.use(xrHandler, taxGroupHandler, http.get('/api/sources/5', () => HttpResponse.json({
      id: 5, title: 'Zootaxa', tier: 'CORE', subfields: [], taxgroups: [], codes: [], recentWorks: [],
    })));
    renderApp(<App />, '/source/5');
    expect(await screen.findByRole('heading', { name: 'Zootaxa' })).toBeInTheDocument();
    expect(document.title).toBe('Garrulus — Zootaxa');
  });
});
