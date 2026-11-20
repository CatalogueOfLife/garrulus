import { screen } from '@testing-library/react';
import { CeasedBadge } from './CeasedBadge';
import { renderApp } from '../test/render';

describe('CeasedBadge', () => {
  it('rendersYearAndSuccessorLink', () => {
    renderApp(<CeasedBadge status="CEASED" lastIssueYear={2019} continuedBy={{ id: 12, title: 'New <b>Journal</b>' }} />);
    expect(screen.getByText(/Ceased publication · last issue 2019/)).toBeInTheDocument();
    const link = screen.getByRole('link', { name: 'New <b>Journal</b>' });
    expect(link).toHaveAttribute('href', '/source/12');
    expect(link.querySelector('b')).toBeNull();
    expect(screen.getByText(/Continued as/)).toBeInTheDocument();
  });

  it('withoutYearOrSuccessor', () => {
    renderApp(<CeasedBadge status="CEASED" />);
    expect(screen.getByText('Ceased publication')).toBeInTheDocument();
    expect(screen.queryByText(/Continued as/)).toBeNull();
  });

  it('nothingForActive', () => {
    const { container } = renderApp(<CeasedBadge status="ACTIVE" lastIssueYear={2019} />);
    expect(container.querySelector('p')).toBeNull();
    expect(screen.queryByText(/Ceased/)).toBeNull();
  });
});
