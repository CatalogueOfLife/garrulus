# Garrulus (nametracker)

Garrulus follows the journals of biodiversity science, finds scientific names in abstracts and open-access full text,
organises them on the latest COL XR and notifies users who follow taxa, journals or names. "Garrulus" is the public
name; `nametracker` is the internal id. See [docs/design.md](docs/design.md) for the design.

## Getting started

1. Install JDK 25 (`sdk env`, see `.sdkmanrc`), Docker and Node 24.
2. `docker compose up -d db gnfinder`
3. `mvn -B verify` for the Java build and tests, then `cd frontend && npm install && npm test`.
4. Run the apps: `mvn -pl indexer spring-boot:run` and `mvn -pl web spring-boot:run`, plus `npm run dev` in `frontend/`.

The full command list, configuration variables and module overview are in [CLAUDE.md](CLAUDE.md#commands).
