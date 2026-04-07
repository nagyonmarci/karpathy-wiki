# Karpathy Wiki

An LLM-powered, self-maintaining personal Markdown knowledge base, inspired by
[Andrej Karpathy's tweet](https://x.com/karpathy/status/2039805659525644595).

Plain `.md` files on disk are the single source of truth. Three Spring AI
agents do ~95% of the curation work:

- **WikiCompilerAgent** — turns raw drops into clean, linked wiki pages.
- **ResearchAgent** — answers questions (with structured citations) and
  writes new notes/slides.
- **WikiLinterAgent** — runs a deterministic structural scan for orphans,
  broken links, and stub pages, then has the LLM layer on contradictions
  and connection suggestions. Returns a structured `LintReport`.

The wiki's structure and workflows live in a single editable file —
[`SCHEMA.md`](./SCHEMA.md) — which every agent reads at startup. Changing
the wiki's shape means editing one Markdown file, not three Java prompts.

## Tech stack

- Java 26
- Spring Boot 4.0.4 + Spring Shell (`spring-shell-starter`) — local CLI, no web server
- Spring AI 2.0.0-M4 (`spring-ai-starter-model-openai`)
- spring-ai-agent-utils 0.7.0 (`FileSystemTools`, `GrepTool`, `GlobTool`, `SkillsTool`, `AutoMemoryToolsAdvisor`)
- Maven

## How it works

This is a local Spring Shell CLI app. When you start it, you get an interactive
`shell:>` prompt. You drive the workflow by either dropping files into `raw/`
or running shell commands.

The mental model:

```
   ┌────────┐  ingest --url ...  ┌──────────────┐
   │  you   │ ─────────────────▶ │ IngestService│  fetch URL → clean MD → save to raw/
   └────────┘                    └──────┬───────┘
        │                               │
        │ drop .md files                ▼
        │                         ┌───────────┐
        └────────────────────────▶│   raw/    │
                                  └─────┬─────┘
                                        │ compile
                                        ▼
                            ┌────────────────────────┐
                            │  WikiCompilerAgent     │
                            │  (LLM + filesystem     │
                            │   + skills + memory)   │
                            └───────────┬────────────┘
                                        │ writes
                                        ▼
                            ┌────────────────────────┐
                            │ wiki/articles/         │
                            │ wiki/concepts/         │
                            │ wiki/summaries/        │
                            │ wiki/index.md          │
                            │ wiki/backlinks.md      │
                            └───────────┬────────────┘
                                        │
                       query "..."      │            lint
        ┌──────────────────┐            │            ┌──────────────────┐
        │  ResearchAgent   │◀───────────┴───────────▶│ WikiLinterAgent  │
        │  reads wiki/,    │                         │ rebuilds backlink│
        │  writes outputs/ │                         │ dedupes, etc.    │
        └──────────────────┘                         └──────────────────┘
```

All three agents are `ChatClient`s wired with the agent-utils tools so the
LLM itself reads/writes files, greps the wiki, loads skills (style guide,
templates) and persists notes-to-self in `memory/`.

## Layout

```
karpathy-wiki/
├── SCHEMA.md    # single source of truth for wiki structure & workflows
├── raw/         # drop files here, or use `ingest`
├── wiki/        # generated knowledge base
│   ├── articles/  concepts/  summaries/  outputs/
│   ├── index.md      # content catalog (compiler-maintained)
│   ├── log.md        # chronological op log (system-maintained, append-only)
│   └── backlinks.md  # rebuilt by linter
├── skills/      # style guide + templates the agents consult
├── memory/      # agent persistent memory (auto-managed)
└── src/main/java/com/danvega/wiki/...
```

## Setup

### 1. Install prerequisites
- JDK 26 (`java -version` should report 26)
- Maven 3.9+

### 2. Set your OpenAI key

```bash
export OPENAI_API_KEY=sk-...
```

(To use a different model, edit `spring.ai.openai.chat.options.model` in
`src/main/resources/application.yml`.)

### 3. Run the app

```bash
mvn spring-boot:run
```

This drops you into an interactive `shell:>` prompt. The first run will
create empty `raw/`, `wiki/`, and `memory/` directories if they don't
already exist. You can also run a single command non-interactively:

```bash
java -jar target/karpathy-wiki-0.1.0-SNAPSHOT.jar status
```

## Using it — a full walkthrough

### Step 1 — Add some content

You have two options. Use whichever is convenient.

**Option A — ingest a web link (preferred):**

```
shell:> ingest --url https://lilianweng.github.io/posts/2023-06-23-agent/ --title "LLM Powered Autonomous Agents" --tags agents,llm
```

What happens:
1. `IngestService` fetches the page with `RestClient`.
2. The LLM strips boilerplate and converts the HTML to clean Markdown.
3. A new file like `raw/2026-04-07-llm-powered-autonomous-agents.md`
   is written, with YAML front-matter (title, source URL, tags).
4. If `wiki.ingest.auto-compile=true` (the default), the
   `WikiCompilerAgent` immediately runs and integrates it into `wiki/`.

**Option B — drop files directly:**

Save any `.md`, `.txt`, or notes file into the `raw/` folder, then:

```
shell:> compile
```

### Step 2 — Watch the wiki grow

After compilation, look in `wiki/`. You should see new files such as:
- `wiki/articles/<slug>.md` — full writeup with front-matter and `[[wiki-links]]`
- `wiki/concepts/<concept>.md` — one file per concept the agent extracted
- `wiki/summaries/<slug>.md` — TL;DRs
- `wiki/index.md` and `wiki/backlinks.md` — auto-maintained

Open them in VS Code (or any Markdown editor). They're plain files — commit
them to git if you want history.

### Step 3 — Ask questions / generate research outputs

```
shell:> query --question "Compare ReAct and Reflexion based on my notes, and write a Marp slide deck to wiki/outputs/."
```

The `ResearchAgent` will grep/read the wiki, synthesize an answer, and (if
you asked for an artifact) write a new file under `wiki/outputs/`. The
output prints the answer followed by a `Sources:` block listing every
wiki file the agent actually read.

### Step 4 — Keep the wiki healthy

Run the linter whenever the wiki feels messy:

```
shell:> lint
```

A deterministic Java pre-pass scans `wiki/` for **orphans** (pages with
no inbound links), **broken links** (`[[targets]]` that don't resolve),
and **gaps** (stub pages under 200 chars). That ground-truth report is
handed to `WikiLinterAgent`, which then rebuilds `backlinks.md`, looks
for **contradictions** across pages on the same topic, and suggests new
connections. The output groups orphans, broken links, gaps, and
contradictions.

### Step 5 — Check status

```
shell:> status
```

Returns counts of files in `raw/` and `wiki/` plus your configured paths.

## Command reference

| Command   | Options                                            | Purpose |
|-----------|----------------------------------------------------|---------|
| `ingest`  | `--url <url>` `--title <t>` `--tags a,b,c`         | Fetch URL → save to `raw/` → optionally auto-compile |
| `compile` | _(none)_                                           | Run `WikiCompilerAgent` over `raw/` |
| `query`   | `--question "..."`                                 | Ask `ResearchAgent`. Prints answer + sources |
| `lint`    | _(none)_                                           | Run `WikiLinterAgent`. Prints structured report |
| `status`  | _(none)_                                           | Counts and paths |

Every successful `ingest`, `compile`, `query`, and `lint` invocation
appends a one-line entry to `wiki/log.md` so you have an auditable
chronological record of everything the system has done.

## Tips

- The agents live in `src/main/java/com/danvega/wiki/`. Tweak their
  system prompts to change tone, structure, or tagging conventions.
- The agents read everything from `skills/`, so add more skill files
  (e.g. `linking-rules.md`, `tagging.md`) to teach them new conventions
  without touching Java.
- `memory/` is managed by `AutoMemoryToolsAdvisor` — the LLM writes its
  own notes there across conversations. You can read/edit those files too.

## Troubleshooting

- **401 from OpenAI** — `OPENAI_API_KEY` not exported, or wrong project key.
- **`ingest` returns nothing useful** — some sites block server-side
  fetches; try downloading the page yourself and dropping it into `raw/`.
- **Compile produced nothing** — check the app logs. The LLM may have
  decided nothing in `raw/` was new; touch a file or add a new one and
  re-run `compile`.
