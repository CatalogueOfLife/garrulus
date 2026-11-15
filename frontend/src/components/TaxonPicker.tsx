import { useState } from 'react';
import { CloseButton, Group, Loader, NavLink, Stack, Text, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useQuery } from '@tanstack/react-query';
import { getXr, suggestTaxa } from '../api';

interface Props {
  /** The label of the selected taxon, if any. */
  label?: string;
  onPick: (usageId: string, label: string) => void;
  onClear: () => void;
}

export function TaxonPicker({ label, onPick, onClear }: Props) {
  const [text, setText] = useState('');
  const [debounced] = useDebouncedValue(text.trim(), 200);
  const xr = useQuery({ queryKey: ['xr'], queryFn: getXr, staleTime: Infinity });
  const xrKey = xr.data?.key;
  const suggest = useQuery({
    queryKey: ['suggest', xrKey, debounced],
    queryFn: () => suggestTaxa(xrKey!, debounced),
    enabled: xrKey !== undefined && debounced.length >= 2,
  });

  return (
    <Stack gap={4}>
      {label ? (
        <Group gap={4} wrap="nowrap">
          <Text size="sm">{label}</Text>
          <CloseButton size="sm" aria-label="Clear taxon" onClick={onClear} />
        </Group>
      ) : null}
      <TextInput
        aria-label="Taxon"
        placeholder="Find a taxon…"
        value={text}
        onChange={(e) => setText(e.currentTarget.value)}
        rightSection={suggest.isFetching ? <Loader size="xs" /> : null}
        disabled={xr.isError}
        description={xr.isError ? 'The taxon search is unavailable.' : undefined}
      />
      {debounced.length >= 2 && suggest.data?.length ? (
        <Stack gap={0} role="listbox" aria-label="Taxon suggestions">
          {suggest.data.slice(0, 8).map((s) => (
            <NavLink
              key={s.usageId}
              label={s.suggestion}
              onClick={() => {
                onPick(s.usageId, s.match);
                setText('');
              }}
            />
          ))}
        </Stack>
      ) : null}
      {suggest.isError ? <Text size="xs" c="red">Suggestions failed.</Text> : null}
    </Stack>
  );
}
