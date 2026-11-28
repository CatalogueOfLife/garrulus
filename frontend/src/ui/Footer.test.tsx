import { render, screen } from '@testing-library/react';
import { Version } from './Footer';

describe('Version', () => {
  it('shortHashLinkingToTheCommit', () => {
    render(<Version commit="2c6e0da1234567890abcdef1234567890abcdef0" />);
    expect(screen.getByRole('link', { name: 'Version 2c6e0da' }))
      .toHaveAttribute('href', 'https://github.com/CatalogueOfLife/garrulus/commit/2c6e0da1234567890abcdef1234567890abcdef0');
  });
  it('nothingWithoutACommit', () => {
    const { container } = render(<Version commit="" />);
    expect(container).toBeEmptyDOMElement();
  });
});
