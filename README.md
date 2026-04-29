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
- Spring Boot 4.0.4 + Spring Shell — local CLI, no web server
- Spring AI 2.0.0-M4 + Ollama (runs fully locally, no API key needed)
- spring-ai-agent-utils 0.7.0 (`FileSystemTools`, `GrepTool`, `GlobTool`, `SkillsTool`, `AutoMemoryToolsAdvisor`)
- MkDocs Material — wiki viewer in the browser

## How it works

```
   ┌────────┐  ingest --url <youtube>  ┌──────────────────────┐
   │  you   │ ───────────────────────▶ │ YoutubeTranscriptClient│  poll transcript → LLM → raw/
   └────────┘                          └──────────┬───────────┘  (requires youtube-video-transcript)
        │                                         │
        │  ingest --url <other>    ┌──────────────┐
        └────────────────────────▶ │ IngestService│  fetch URL → clean MD → save to raw/
                                   └──────┬───────┘
        │                               │
        │  copy files to import/        │
        │  (.pdf .docx .txt .md         ▼
        │   claude-*.json          ┌──────────────┐
        │   chatgpt-*.json)        │ ImportService│  parse → LLM → raw/  (parallel, N=OLLAMA_NUM_PARALLEL)
        └────────────────────────▶ └──────┬───────┘  processed files → import/processed/
                                          │
                                          ▼
                                    ┌───────────┐
                                    │   raw/    │
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
        ┌──────────────────┐              │            ┌──────────────────┐
        │  ResearchAgent   │◀─────────────┴───────────▶│ WikiLinterAgent  │
        │  reads wiki/,    │                           │ rebuilds backlink│
        │  writes outputs/ │                           │ dedupes, etc.    │
        └──────────────────┘                           └──────────────────┘
```

All three agents are `ChatClient`s wired with the agent-utils tools so the
LLM itself reads/writes files, greps the wiki, loads skills (style guide,
templates) and persists notes-to-self in `memory/`.

## Layout

```
karpathy-wiki/
├── SCHEMA.md    # single source of truth for wiki structure & workflows
├── import/      # drop documents here → `import` command processes them
│   └── processed/   # successfully imported files land here
├── raw/         # drop files here, or use `ingest`
├── wiki/        # generated knowledge base (empty until first compile)
│   ├── articles/  concepts/  summaries/  outputs/
│   ├── index.md      # content catalog (compiler-maintained)
│   ├── log.md        # chronological op log (system-maintained, append-only)
│   └── backlinks.md  # rebuilt by linter
├── skills/      # style guide + templates the agents consult
├── memory/      # agent persistent memory (auto-managed)
└── src/main/java/com/danvega/wiki/...
```

## Quick start (Docker)

### 1. Pull the model

If Ollama is already running on your machine (port 11434), `run.sh` will use
it automatically. Otherwise Docker will start an Ollama container for you.

```bash
# pull once — skip if you already have gemma4:31b
ollama pull gemma4:31b
# or via Docker if Ollama isn't installed locally:
docker compose run --rm ollama ollama pull gemma4:31b
```

### 2. Add some content

**Option A — import your own documents** (PDF, DOCX, TXT, MD, Claude/ChatGPT JSON exports):

```bash
cp ~/Downloads/lecture.pdf import/
cp ~/Downloads/chatgpt-export.json import/
./run.sh import      # converts, compiles, and moves files to import/processed/
```

Subfolders work too — drop a whole directory into `import/` and every file is
picked up recursively.

**Option B — ingest a YouTube video** (requires [youtube-video-transcript](https://github.com/nagyonmarci/youtube-video-transcript) running locally):

```bash
YOUTUBE_DIRECTUS_TOKEN=<token> ./run.sh ingest \
  --url "https://www.youtube.com/watch?v=dQw4w9WgXcQ" \
  --title "My Video" --tags ai,lecture
```

YouTube URLs are auto-detected — the transcript is fetched via the
`youtube-video-transcript` API instead of HTML scraping (takes ~1 min/video).
Set `YOUTUBE_DIRECTUS_TOKEN` once in your shell profile or in `docker-compose.yml`.

**Option C — fetch any other URL:**

```bash
./run.sh ingest --url https://example.com/article --title "My Article" --tags ai,notes
```

**Option D — drop a Markdown file directly:**

The `raw/` folder is gitignored (it's your personal notes). An example note is
provided in `examples/` — copy it over to get started immediately:

```bash
cp examples/spring-ai-intro.md raw/
```

Or drop any `.md` file into `raw/` with YAML front-matter:

```markdown
---
title: My First Note
tags: [example, getting-started]
---

Your content here...
```

### 3. Compile

```bash
./run.sh compile
```

`run.sh` detects whether Ollama is already running on your machine and uses it
directly; if not, it starts the Ollama container first.

After a few seconds, `wiki/` will be populated with linked Markdown pages.

### 4. Browse the wiki

```bash
docker compose up wiki
```

Open [http://localhost:8000](http://localhost:8000).

> **404 on first load?** The wiki is empty until `compile` has run at least
> once. Add a file to `raw/` and run `./run.sh compile` first.

### 5. Ask questions

```bash
./run.sh query --question "What is a ChatClient and how does it relate to advisors?"
```

### 6. Interactive shell

```bash
./run.sh
```

```
shell:> status
shell:> ingest --url https://spring.io/blog/2025/01/23/spring-ai-1-0-0-m6-released --title "Spring AI M6" --tags spring-ai,release
shell:> compile
shell:> lint
```

## Local development (without Docker)

### Prerequisites

- JDK 26 — `java -version` should report 26
- Maven 3.9+ — use `make build` which sets `JAVA_HOME` automatically
- Ollama running locally — `ollama serve`

### Build & run

```bash
make build
OLLAMA_BASE_URL=http://localhost:11434 mvn spring-boot:run
```

Or with a single command:

```bash
java -jar target/karpathy-wiki-0.1.0-SNAPSHOT.jar status
```

### Choosing a model

The default model is `gemma4:31b` (set in `docker-compose.yml`). Override it per-session:

```bash
OLLAMA_MODEL=qwen2.5:7b ./run.sh
```

Or edit `spring.ai.ollama.chat.options.model` in
`src/main/resources/application.yml`.

## Command reference

| Command   | Shortcut | Options                                            | Purpose |
|-----------|----------|----------------------------------------------------|---------|
| `import`  | `m`      | _(none)_                                           | Convert files in `import/` → `raw/`, then compile. Runs in parallel (N = `OLLAMA_NUM_PARALLEL`) |
| `ingest`  | `i`      | `--url <url>` `--title <t>` `--tags a,b,c`         | Fetch URL → `raw/`. YouTube URLs auto-routed to transcript API; other URLs scraped as HTML |
| `compile` | `c`      | _(none)_                                           | Run `WikiCompilerAgent` over `raw/` |
| `query`   | `q`      | `--question "..."`                                 | Ask `ResearchAgent`. Prints answer + sources |
| `lint`    | `l`      | _(none)_                                           | Run `WikiLinterAgent`. Prints structured report |
| `status`  | `s`      | _(none)_                                           | File counts, paths, CPU cores, effective parallelism |

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
- If a raw file has a `repo:` field with a GitHub URL, the compiler will
  shallow-clone it, weave real code snippets into the wiki pages, and
  clean up the clone when done.

```yaml
---
title: Embabel First Look
source: https://youtu.be/G5VDQCZu6t0
repo: https://github.com/danvega/blog-agent
tags: [java, embabel, ai, agents]
---

[paste transcript here]
```

## YouTube integration

`ingest` automatically detects `youtube.com/watch` and `youtu.be/` URLs and
routes them to the [youtube-video-transcript](https://github.com/nagyonmarci/youtube-video-transcript)
service instead of HTML scraping.

**How it works:**
1. `POST /fetch-video` — submits the video to the transcript fetcher queue
2. Polls Directus every 10s until `status = "done"` (typically 50–105s/video)
3. Sends the plain-text transcript to the LLM with a structured wiki-note prompt
4. Saves the result to `raw/` with frontmatter (`title`, `source`, `uploaded`, `tags`)

**Required environment variables:**

| Variable | Default | Description |
|---|---|---|
| `YOUTUBE_DIRECTUS_TOKEN` | _(none)_ | Directus admin token — **required** to enable YouTube routing |
| `YOUTUBE_FETCHER_URL` | `http://localhost:8000` | Fetcher API base URL |
| `YOUTUBE_DIRECTUS_URL` | `http://localhost:8055` | Directus API base URL |

If `YOUTUBE_DIRECTUS_TOKEN` is not set, YouTube URLs fall back to HTML scraping
(with a warning in the log).

**Set once in your shell profile:**
```bash
export YOUTUBE_DIRECTUS_TOKEN=your_token_here
```

**Or in `docker-compose.yml`:**
```yaml
karpathy-wiki:
  environment:
    - YOUTUBE_DIRECTUS_TOKEN=your_token_here
    - YOUTUBE_FETCHER_URL=http://host.docker.internal:8000
    - YOUTUBE_DIRECTUS_URL=http://host.docker.internal:8055
```

## Performance: parallel import

`ImportService` processes files concurrently — the number of parallel LLM calls
matches `OLLAMA_NUM_PARALLEL` on the Ollama server (default: 2).

When multiple requests arrive simultaneously, Ollama batches them into a single
GPU pass: the model weights are read from memory once and used to compute tokens
for all N requests in parallel. This improves GPU core utilisation on large
unified-memory chips (e.g. M4 Max: 40 GPU cores, 128 GB, ~546 GB/s bandwidth).

To increase throughput further, raise `OLLAMA_NUM_PARALLEL` and set
`wiki.ingest.parallelism` to match:

```bash
# host Ollama (macOS)
launchctl setenv OLLAMA_NUM_PARALLEL 4
# restart Ollama, then:
OLLAMA_NUM_PARALLEL=4 ./run.sh import
```

Or in `docker-compose.yml` for the containerised Ollama:

```yaml
ollama:
  environment:
    - OLLAMA_NUM_PARALLEL=4
```

Note: each parallel context keeps a full copy of the model in VRAM.
`gemma4:31b` = ~47 GB, so on a 128 GB system the practical limit is 2.

## Troubleshooting

- **MkDocs shows 404** — `wiki/` is empty; run `./run.sh compile` first.
- **`run.sh` starts Ollama container but model is missing** — run
  `docker compose run --rm ollama ollama pull gemma4:31b` once.
- **`ingest` returns nothing useful** — some sites block server-side
  fetches; download the page yourself and drop it into `raw/` manually.
- **YouTube ingest falls back to HTML scraping** — `YOUTUBE_DIRECTUS_TOKEN` is not set.
  Export it in your shell or add it to `docker-compose.yml`.
- **YouTube ingest times out** — the `youtube-video-transcript` fetcher may not be running,
  or the video has no captions and Whisper transcription is still in progress (check
  `docker compose logs fetcher` in that project). Default timeout is 5 minutes.
- **Compile produced nothing** — the LLM may have decided nothing in
  `raw/` was new; touch a file or add a new one and re-run `compile`.
