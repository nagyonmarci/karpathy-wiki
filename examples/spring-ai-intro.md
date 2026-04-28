---
title: Spring AI Introduction
source: https://docs.spring.io/spring-ai/reference/
tags: [spring-ai, java, llm, getting-started]
---

Spring AI is a framework that brings AI capabilities to Spring Boot applications.
It provides a unified abstraction over multiple LLM providers (OpenAI, Ollama,
Anthropic, Azure, etc.) so developers can swap models by changing config, not code.

## Core abstractions

**ChatClient** is the main entry point for interacting with an LLM. It follows
the builder pattern and supports a fluent API for constructing prompts, attaching
tools, and reading responses.

**Advisors** are interceptors that wrap the ChatClient pipeline. Common uses:
- `SimpleLoggerAdvisor` — logs every request/response
- `AutoMemoryToolsAdvisor` — gives the LLM a read/write memory store
- `MessageChatMemoryAdvisor` — maintains conversation history

**Tool calling** lets the LLM invoke Java methods declared with `@Tool`. Spring AI
handles the JSON serialisation and the back-and-forth automatically.

**Structured output** — responses can be deserialized directly into Java records or
classes using `entity(MyRecord.class)` on the call chain.

## Ollama integration

Ollama runs open-weight models locally (llama3.2, qwen2.5, mistral, etc.).
The Spring AI Ollama starter auto-configures a `ChatClient` pointing at
`http://localhost:11434` by default. No API key required.

```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: llama3.2
```

## Agent pattern

An agent is a `ChatClient` loop that reads context, calls tools, and writes
results until it decides it is done. Spring AI agent-utils provides ready-made
filesystem tools (`FileSystemTools`, `GrepTool`, `GlobTool`) so the LLM can
read and write files directly — no custom tool wiring needed.
