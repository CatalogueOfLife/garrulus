# CLAUDE.md

**Garrulus** (internal id `nametracker`) follows the journals of biodiversity science, finds scientific names (plus nomenclatural acts and the
article's verbatim classification) in abstracts and open-access full text, organises them on the latest COL XR, and
notifies users who follow taxa, journals or names. It also builds a public name–literature index.

**Naming:**
- **Internal id `nametracker`:** Java packages `org.catalogueoflife.nametracker.*`, artifactIds `nametracker-*`, the
  database, systemd units `col-nametracker-*`, the repo.
- **Public name "Garrulus"** (the European jay): everything users see. That covers the UI, logo, page titles, emails,
  feeds, public URLs and the outgoing HTTP User-Agent `Garrulus/<version> (+https://www.catalogueoflife.org)`.
- **Brand:** logo, jay colour palette and the Mantine theme are in `docs/brand/`. Take colours from there; never invent
  new ones.

**Status:** design approved 2026-11-14; implementation starts with milestones M0 (foundations) and M1 (finder v1).
Commands and module details get filled in here as M0 lands.

## Read first

- `docs/design.md`: the approved design. Goals, constraints, components, data model, workflows, roadmap.
- `docs/adr/`: later decisions, one file each.

## Ground rules (from the design)

- **Stack:** Blixa's stack (`~/code/col/blixa`): Spring Boot 4.1, **JDK 25**, MyBatis, Flyway, Postgres 17,
  React + Mantine. CLB modules and name-parser versions come from `org.catalogueoflife:clb-bom` via one `clb.version`
  property.
- **Two Spring Boot apps sharing one database.** They never call each other.
  - `web`: user-facing, mostly read, always on.
  - `indexer`: harvesting, analysis, matching; can be stopped at any time.
  - The schema lives once, in the shared `store` module.
- **Free by default.** Nothing may depend on a paid or metered service.
  - Every such integration (OpenAlex beyond the free allowance, OpenAlex content downloads, LLM judge) has a config
    switch and a cost cap.
  - The backfill uses free sources only.
- **The name finder sits behind the `NameFinder` SPI and its JSON contract** (`finder-api`), so alternative
  implementations can be swapped in. The default is gnfinder plus our own stages.
- **Never store a name that doesn't point at an exact span of source text.** No LLM-invented names.
- Keep full text only for open-licensed works; for everything else, only derived data and short snippets.
