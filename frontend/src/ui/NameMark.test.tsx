import { screen } from '@testing-library/react';
import { NameMark } from './NameMark';
import { renderApp } from '../test/render';
import type { MentionSpan } from '../api';

const span = (over: Partial<MentionSpan> = {}): MentionSpan => ({ id: 1, nameId: 7, startCp: 0, endCp: 3, verbatim: 'x', ...over });

describe('NameMark', () => {
  it('linksToTheNameAndItalicisesSpecies', () => {
    renderApp(<NameMark span={span()} name={{ rank: 'species' }}>Carabus auratus</NameMark>);
    const a = screen.getByRole('link', { name: 'Carabus auratus' });
    expect(a).toHaveAttribute('href', '/name/7');
    expect(a.querySelector('i')).not.toBeNull();
    expect(a).not.toHaveClass('gr-mark-act');
  });

  it('keepsFamilyRoman', () => {
    renderApp(<NameMark span={span()} name={{ rank: 'family' }}>Carabidae</NameMark>);
    expect(screen.getByRole('link', { name: 'Carabidae' }).querySelector('i')).toBeNull();
  });

  it('marksAnActSpan', () => {
    renderApp(<NameMark span={span({ actType: 'SP_NOV' })} name={{ rank: 'species' }}>Carabus novus</NameMark>);
    expect(screen.getByRole('link', { name: 'Carabus novus' })).toHaveClass('gr-mark-act');
  });
});
