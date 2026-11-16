import { Anchor, List, Stack, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useEffect } from 'react';
import { useLocation } from 'react-router-dom';
import { getXr } from '../api';
import { usePageTitle } from '../usePageTitle';

export function AboutPage() {
  usePageTitle('About');
  const xr = useQuery({ queryKey: ['xr'], queryFn: getXr, staleTime: Infinity });
  const { hash } = useLocation();
  useEffect(() => {
    if (hash) document.getElementById(hash.slice(1))?.scrollIntoView();
  }, [hash]);
  return (
    <Stack gap="md" maw={760}>
      <Title order={2}>About Garrulus</Title>
      <Text>
        Garrulus is a service of the Catalogue of Life. It follows the journals of biodiversity science, finds scientific
        names in abstracts and in open-access full text, and organises them on the latest Catalogue of Life eXtended
        Release. You can follow taxa, journals, names and taxonomic groups by email digest or Atom feed. All of it
        feeds a public index of names and the literature that uses them.
      </Text>

      <Title order={3}>How it works</Title>
      <List spacing="xs">
        <List.Item>Journals and papers come from OpenAlex and Crossref metadata.</List.Item>
        <List.Item>Full text comes from Pensoft, Europe PMC and PubMed Central where a paper is open.</List.Item>
        <List.Item>Names are found with gnfinder plus Garrulus' own rules, and each name points at an exact span of the text.</List.Item>
        <List.Item>
          Names are matched to the Catalogue of Life eXtended Release
          {xr.data ? <>, currently <Text span fw={600}>{xr.data.alias}</Text></> : null}.
        </List.Item>
        <List.Item>A name is a main subject of a paper when it matters to the paper; otherwise it is only mentioned.</List.Item>
        <List.Item>Names that occur only in the reference list are left out.</List.Item>
      </List>

      <Title order={3}>Name pages and taxon pages</Title>
      <Text id="names">
        A <b>name page</b> is about one name as it is written in papers — for example <i>Paussus olcesii</i>. It lists the
        papers that use this spelling, when it first appeared, and where Catalogue of Life places it. Names that COL does
        not know yet, such as newly described species, have name pages too.
      </Text>
      <Text id="taxa">
        A <b>taxon page</b> is about a taxon in the Catalogue of Life — for example the family Carabidae. It gathers the
        papers about this taxon and everything below it, whatever name the papers use: its species and genera, synonyms
        and misspellings that COL resolves to it. Following a taxon follows all of that.
      </Text>
      <Text>
        Full text is kept and shown only for open-licensed papers; for other papers Garrulus keeps only the names it found.
      </Text>

      <Title order={3}>Links</Title>
      <List spacing="xs">
        <List.Item><Anchor href="/api/openapi.yaml">API description</Anchor></List.Item>
        <List.Item><Anchor href="https://www.catalogueoflife.org">catalogueoflife.org</Anchor></List.Item>
        <List.Item><Anchor href="https://github.com/CatalogueOfLife/nametracker">Source code</Anchor></List.Item>
      </List>
    </Stack>
  );
}
