import { Link } from 'react-router-dom';
import type { MentionSpan } from '../api';
import { isItalicName, type NameInfo } from './nameStyle';
import { NameText } from './SciName';

/** A name found in running text: a link with a 1px ink underline, red when the span carries a nomenclatural act. */
export function NameMark({ span, name, children }: { span: MentionSpan; name?: NameInfo; children: string }) {
  return (
    <Link to={`/name/${span.nameId}`} className={span.actType ? 'gr-mark gr-mark-act' : 'gr-mark'}>
      <NameText text={children} italic={isItalicName(children, name ?? {})} />
    </Link>
  );
}
