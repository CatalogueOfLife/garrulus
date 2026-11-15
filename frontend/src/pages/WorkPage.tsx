import { Alert, Anchor, Badge, Grid, Group, Loader, Stack, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';
import { getWork, type NameDetail } from '../api';
import { HighlightedText } from '../components/HighlightedText';
import { usePageTitle } from '../usePageTitle';

function Section({ title, children }: { title: string; children: ReactNode }) {
  const id = `sec-${title.replace(/\W+/g, '-')}`;
  return (
    <section aria-labelledby={id}>
      <Title order={4} id={id} mb="xs">{title}</Title>
      {children}
    </section>
  );
}

function NameRow({ n, withTaxon }: { n: NameDetail; withTaxon: boolean }) {
  const path = n.classification.map((t) => t.name).filter(Boolean).join(' › ');
  return (
    <li>
      <Anchor component={Link} to={`/name/${n.id}`} fs="italic">{n.name}</Anchor>
      {withTaxon && n.usageId ? (
        <>
          {' → '}
          <Anchor component={Link} to={`/taxon/${n.usageId}`}>{n.usageName || n.usageId}</Anchor>
          {n.usageRank ? <Text span size="sm" c="dimmed"> ({n.usageRank})</Text> : null}
        </>
      ) : null}
      {n.mainSubject ? <Badge ml={6} size="xs" variant="light">main subject</Badge> : null}
      {n.actTypes.map((a) => <Badge key={a} ml={4} size="xs" variant="outline">{a}</Badge>)}
      {path ? <Text size="xs" c="dimmed">COL classification: {path}</Text> : null}
    </li>
  );
}

export function WorkPage() {
  const { id = '' } = useParams();
  const q = useQuery({ queryKey: ['work', id], queryFn: () => getWork(id) });
  const w = q.data;
  usePageTitle(w?.title || `Work ${id}`);
  if (q.isError) return <Alert color="red">{(q.error as Error).message}</Alert>;
  if (!w) return <Loader />;

  const placed = w.names.filter((n) => n.placementMethod === 'MATCH' || n.placementMethod === 'GENUS');
  const verb = w.names.filter((n) => n.placementMethod === 'VERBATIM_CONTEXT');
  const none = w.names.filter((n) => n.placementMethod === 'NONE');
  const list = (ns: NameDetail[], withTaxon: boolean) => (
    <Stack component="ul" gap="xs" p={0} m={0} pl="md">
      {ns.map((n) => <NameRow key={n.id} n={n} withTaxon={withTaxon} />)}
    </Stack>
  );
  const showText = w.retained && w.text && w.text.length > 0;
  const snippets = w.names.filter((n) => n.bestSnippet);

  return (
    <Stack gap="lg">
      <div>
        <Title order={2}>{w.title || '(untitled)'}</Title>
        <Text c="dimmed">
          {w.authors.map((a) => a.name).join(', ')}{w.year ? ` (${w.year})` : ''}
          {w.source ? <> · <Anchor component={Link} to={`/source/${w.source.id}`}>{w.source.title}</Anchor></> : null}
        </Text>
        <Group gap="xs" mt={4}>
          {w.doi ? <Anchor href={`https://doi.org/${w.doi}`} size="sm">doi:{w.doi}</Anchor> : null}
          {w.licence ? <Badge variant="light" size="sm">{w.licence}</Badge> : null}
          {w.hasActs ? <Badge variant="light" size="sm">nomenclatural acts</Badge> : null}
        </Group>
      </div>
      {w.abstract ? <Text>{w.abstract}</Text> : null}

      <Grid gutter="xl">
        <Grid.Col span={{ base: 12, md: 8 }}>
          <Stack gap="lg">
            {placed.length ? <Section title="Placed in COL">{list(placed, true)}</Section> : null}
            {verb.length ? <Section title="Placed by the verbatim classification">{list(verb, true)}</Section> : null}
            {none.length ? <Section title="Not placed">{list(none, false)}</Section> : null}
          </Stack>
        </Grid.Col>
        <Grid.Col span={{ base: 12, md: 4 }}>
          {w.contexts.length ? (
            <Section title="Verbatim classification in the article">
              <Stack component="ul" gap="xs" p={0} m={0} pl="md">
                {w.contexts.map((c) => <li key={c.localId}><Text size="sm">{c.verbatim}</Text></li>)}
              </Stack>
            </Section>
          ) : null}
        </Grid.Col>
      </Grid>

      {showText ? (
        <Section title="Full text">
          <Stack gap="sm">
            {w.text!.map((b) => (
              b.type.startsWith('heading') || b.type === 'title'
                ? <Title key={b.id} order={5}><HighlightedText block={b} /></Title>
                : <HighlightedText key={b.id} block={b} />
            ))}
          </Stack>
        </Section>
      ) : snippets.length ? (
        <Section title="Snippets">
          <Stack component="ul" gap="xs" p={0} m={0} pl="md">
            {snippets.map((n) => <li key={n.id}><Text size="sm">{n.bestSnippet}</Text></li>)}
          </Stack>
        </Section>
      ) : null}
    </Stack>
  );
}
