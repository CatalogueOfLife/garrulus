import { screen } from '@testing-library/react';
import { EmptyState } from './EmptyState';
import { renderApp } from '../test/render';

describe('EmptyState', () => {
  it('renders the title, the text and the action', () => {
    renderApp(<EmptyState title="Nothing here" action={<a href="/x">Go on</a>}>Try something else.</EmptyState>);
    expect(screen.getByRole('heading', { name: 'Nothing here' })).toBeInTheDocument();
    expect(screen.getByText('Try something else.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go on' })).toBeInTheDocument();
  });

  it('works without text and action', () => {
    renderApp(<EmptyState title="Empty" />);
    expect(screen.getByRole('heading', { name: 'Empty' })).toBeInTheDocument();
  });
});
