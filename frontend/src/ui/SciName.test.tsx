import { screen } from '@testing-library/react';
import { renderApp } from '../test/render';
import { SciName } from './SciName.tsx';
import { SciTitle } from './SciTitle';

const span = (id: number, startCp: number, endCp: number) => ({ id, nameId: id, startCp, endCp, verbatim: '' });

describe('SciName', () => {
  it('italicises a binomial and appends the act marker', () => {
    const { container } = renderApp(<SciName name="Abies alba" acts={['SP_NOV']} />);
    expect(container.querySelector('i')).toHaveTextContent('Abies alba');
    expect(screen.getByText('sp. nov.')).toHaveClass('gr-act');
  });

  it('keeps a family roman', () => {
    const { container } = renderApp(<SciName name="Pinaceae" rank="FAMILY" />);
    expect(container.querySelector('i')).toBeNull();
    expect(screen.getByText('Pinaceae')).toBeInTheDocument();
  });

  it('links when given a target', () => {
    renderApp(<SciName name="Abies alba" to="/names/1" />);
    expect(screen.getByRole('link')).toHaveAttribute('href', '/names/1');
  });
});

describe('SciTitle', () => {
  it('italicises exactly the span, counting code points', () => {
    const { container } = renderApp(<SciTitle title="𝔄 Abies alba" mentions={[span(1, 2, 12)]} />);
    const it = container.querySelectorAll('i');
    expect(it).toHaveLength(1);
    expect(it[0]).toHaveTextContent(/^Abies alba$/);
    expect(container).toHaveTextContent('𝔄 Abies alba');
  });

  it('renders the first of overlapping spans once', () => {
    const { container } = renderApp(<SciTitle title="Abies alba" mentions={[span(1, 0, 10), span(2, 5, 10)]} />);
    expect(container.querySelectorAll('i')).toHaveLength(1);
    expect(container).toHaveTextContent('Abies alba');
  });
});
