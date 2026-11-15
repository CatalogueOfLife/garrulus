import { Text, Title } from '@mantine/core';
import { useParams } from 'react-router-dom';
import { usePageTitle } from '../usePageTitle';

function Placeholder({ kind }: { kind: string }) {
  const { id } = useParams();
  usePageTitle(`${kind} ${id ?? ''}`.trim());
  return (
    <>
      <Title order={2}>{kind}</Title>
      <Text c="dimmed">{id}</Text>
    </>
  );
}

export const TaxonPage = () => <Placeholder kind="Taxon" />;
export const NamePage = () => <Placeholder kind="Name" />;
export const WorkPage = () => <Placeholder kind="Work" />;
export const SourcePage = () => <Placeholder kind="Source" />;
