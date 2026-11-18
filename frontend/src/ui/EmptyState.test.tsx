import { screen } from '@testing-library/react';
import { ApiError } from '../api';
import { EmptyState } from './EmptyState';
import { retryUnlessNotFound } from './NotFound';
import { renderApp } from '../test/render';

describe('EmptyState', () => {
  it('renders the title, the text and the action', () => {
    renderApp(<EmptyState title="Nothing here" action={<a href="/x">Go on</a>}>Try something else.</EmptyState>);
    expect(screen.getByRole('heading', { name: 'Nothing here' })).toBeInTheDocument();
    expect(screen.getByText('Try something else.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go on' })).toBeInTheDocument();
  });

  it('is a second-level heading by default and a page title when asked', () => {
    const { unmount } = renderApp(<EmptyState title="Empty" />);
    expect(screen.getByRole('heading', { level: 2, name: 'Empty' })).toBeInTheDocument();
    unmount();
    renderApp(<EmptyState title="Gone" headingLevel={1} />);
    expect(screen.getByRole('heading', { level: 1, name: 'Gone' })).toBeInTheDocument();
  });

  it('works without text and action', () => {
    renderApp(<EmptyState title="Empty" />);
    expect(screen.getByRole('heading', { name: 'Empty' })).toBeInTheDocument();
  });
});

describe('retryUnlessNotFound', () => {
  it('gives up on a 404 at once and retries other errors three times', () => {
    expect(retryUnlessNotFound(0, new ApiError(404, 'nope'))).toBe(false);
    expect(retryUnlessNotFound(0, new ApiError(500, 'down'))).toBe(true);
    expect(retryUnlessNotFound(2, new Error('network'))).toBe(true);
    expect(retryUnlessNotFound(3, new Error('network'))).toBe(false);
  });
});
