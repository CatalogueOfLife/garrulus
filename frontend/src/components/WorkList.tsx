import { Anchor, Badge, Group, Stack, Text } from '@mantine/core';
import { Link } from 'react-router-dom';
import type { WorkSummary } from '../api';

export function WorkList({ works }: { works: WorkSummary[] }) {
  return (
    <Stack gap="md" component="ul" p={0} m={0} style={{ listStyle: 'none' }}>
      {works.map((w) => (
        <li key={w.id}>
          <Anchor component={Link} to={`/work/${w.id}`} fw={600}>
            {w.title || '(untitled)'}
          </Anchor>
          <Text size="sm" c="dimmed">
            {w.authors.join(', ')}
            {w.authorCount > w.authors.length ? ` et al.` : ''}
            {w.year ? ` (${w.year})` : ''}
            {w.source ? ' · ' : ''}
            {w.source ? (
              <Anchor component={Link} to={`/source/${w.source.id}`} size="sm">
                {w.source.title}
              </Anchor>
            ) : null}
          </Text>
          <Group gap={6} mt={4}>
            {w.hasActs ? <Badge variant="light" size="sm">nomenclatural acts</Badge> : null}
            {w.mainNames.map((n) => (
              <Badge key={n.id} variant="outline" size="sm" component={Link} to={`/name/${n.id}`} tt="none">
                {n.name}
              </Badge>
            ))}
          </Group>
        </li>
      ))}
    </Stack>
  );
}
