# Wiki Schema

The single source of truth for the wiki's structure and workflows. All
agents (Compiler, Research, Linter) are required to read and follow this
file. If a rule here conflicts with an agent's system prompt, **this
file wins**. To change the wiki's shape, edit this file — not the Java.

## Layers

1. **`raw/`** — immutable user-supplied source material. Never edited
   by agents after ingest.
2. **`wiki/`** — generated, agent-maintained Markdown knowledge base.
3. **`SCHEMA.md`** (this file) + **`skills/`** — configuration that
   defines structure, conventions, and workflows.

## Wiki layout

```
wiki/
├── articles/<slug>.md      # one per source: full writeup
├── concepts/<concept>.md   # one per concept: deduped across articles
├── summaries/<slug>.md     # one per source: TL;DR (≤ 200 words)
├── outputs/                # research artifacts (notes, slides, comparisons)
├── index.md                # content-oriented catalog (special file)
├── log.md                  # chronological operation log (special file, append-only)
└── backlinks.md            # auto-rebuilt by the linter
```

## Page front-matter (YAML)

Every article, concept, and summary page MUST start with:

```yaml
---
title: <human title>
type: article | concept | summary
slug: <kebab-case>
sources: [<wiki-relative or URL>]   # articles + summaries only
concepts: [<concept-slug>, ...]      # articles only
backlinks: [<wiki-relative>, ...]    # maintained by linter
tags: [...]
---
```

## Linking conventions

- Use `[[concept-slug]]` for inline cross-references. Targets must
  resolve to a real file under `wiki/concepts/` or `wiki/articles/`.
- Cross-link aggressively but never to a page that does not exist.
- The linter rebuilds `wiki/backlinks.md` from these links.

## Special files

- `index.md` — content-oriented. Lists every article/concept/summary
  grouped by topic with a one-line description. Compiler maintains.
- `log.md` — system-maintained append-only log of every ingest, compile,
  query, and lint operation. Do not edit by hand.

## Workflows

- **Ingest** — fetch a URL → clean Markdown → write to `raw/<date>-<slug>.md`
  with front-matter (`title`, `source`, `tags`). May auto-trigger compile.
- **Compile** — for each new/changed file in `raw/`: write/update the
  matching article, summary, and concept pages, then refresh `index.md`.
  Be incremental — never rewrite untouched files.
- **Query** — search `wiki/` first; synthesize an answer; cite the wiki
  files used; optionally produce an artifact under `wiki/outputs/`.
  Responses are structured: `{ answer, sources[] }`.
- **Lint** — run a deterministic scan for orphans, broken links, and
  stub pages, then have the LLM additionally reason about contradictions
  and suggest connections. Output is structured.
