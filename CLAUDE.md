# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

PaiSmart is an enterprise RAG (Retrieval-Augmented Generation) knowledge management system — Spring Boot 3.4.2 backend + Vue 3 frontend. Users upload documents, the system chunks/embeds/indexes them, then answers natural-language questions using retrieved context + DeepSeek LLM.

## Commands

### Backend (Maven, Java 17)
```bash
mvn spring-boot:run                                      # Run (default profile)
mvn spring-boot:run -Dspring-boot.run.profiles=dev       # Run with dev profile
mvn spring-boot:run -Dspring-boot.run.profiles=docker    # Run with docker profile
mvn clean package                                         # Build
mvn test                                                  # All tests
mvn test -Dtest=UserServiceTest                           # Single test class
```

### Frontend (pnpm monorepo, Node >=18.20.0)
```bash
cd frontend
pnpm install                                              # Install dependencies
pnpm dev                                                  # Dev server (test mode)
pnpm dev:prod                                             # Dev server (prod mode)
pnpm build                                                # Production build
pnpm typecheck                                            # TypeScript type checking
pnpm lint                                                 # ESLint
```

### Docker (infrastructure services)
```bash
cd docs && docker-compose up -d                           # Start MySQL, Redis, ES, Kafka, MinIO
```

## Architecture

### Request flow
```
Browser → Vue 3 (Naive UI) → REST/WS → Spring Security (JWT + OrgTag filters) → Controller → Service → Repository/JPA
                                                                                          ↓
                                                                              Elasticsearch / Redis / Kafka / MinIO
```

### Security: dual-filter chain

1. **JwtAuthenticationFilter** — runs before `UsernamePasswordAuthenticationFilter`. Validates JWT from `Authorization: Bearer <token>`. Auto-refreshes tokens that are near expiry or within a grace period after expiry, returning the new token via the `New-Token` response header. Extracts username, loads `UserDetails`, sets `SecurityContextHolder`.
2. **OrgTagAuthorizationFilter** — runs after JWT filter. For organization-scoped API paths, validates that the user belongs to the relevant organization tag, then sets `orgTag` as a request attribute.

All endpoints are locked down by default (`.anyRequest().authenticated()`). Public paths: `/api/v1/users/register`, `/api/v1/users/login`, `/api/v1/test/**`, `/chat/**`, `/ws/**`, static resources. Admin-only paths: `/api/v1/admin/**`.

### RAG pipeline
```
Upload (chunked) → MinIO → Kafka (file-processing topic) → ParseService (Apache Tika) 
→ text chunks → EmbeddingClient (DashScope text-embedding-v4, 2048-dim) 
→ ES index with dense_vector + IK-analyzed text fields
```

Search is hybrid: ES `script_score` query (cosine similarity on vectors) combined with text match, results merged by `HybridSearchService`. Search respects document visibility (public vs org-tag-scoped).

### WebSocket chat flow
```
Frontend ws:// → ChatWebSocketHandler (Spring WebSocket) → ChatHandler.processMessage()
  1. Get/create conversation ID (Redis: `user:{userId}:current_conversation`)
  2. Load conversation history (Redis: `conversation:{id}`, last 20 messages)
  3. HybridSearchService.searchWithPermission() — retrieves top-5 relevant chunks
  4. DeepSeekClient.streamResponse() — streams LLM tokens back as JSON `{"chunk":"..."}`
  5. On completion, sends `{"type":"completion","status":"finished"}` and persists history
```

Stop generation: client sends `{"type":"stop"}`, ChatHandler sets a per-session flag that blocks further chunk sending.

### Kafka topics
- **file-processing** — file merge → parse → chunk → embed pipeline (consumer: `FileProcessingConsumer`)
- **vectorization** — dedicated embedding/indexing work
- DLT: `file-processing-dlt` for failed messages

### Multi-tenancy model
Organization tags form a hierarchical tree (`organization_tags` table, self-referencing `parent_tag`). Users have `primary_org` + a comma-separated `org_tags` list. Documents belong to one org tag and have a `is_public` flag. All data access filters by org + public visibility.

### Configuration profiles
- `application.yml` — defaults (localhost services, dev credentials)
- `application-dev.yml` — development overrides
- `application-docker.yml` — Docker Compose service addresses

Key custom properties are under `deepseek.*`, `embedding.*`, `ai.prompt.*`, `ai.generation.*`, `minio.*`, `jwt.*`, `admin.*`, `file.parsing.*`, `elasticsearch.*`.

### Frontend monorepo
`frontend/packages/` contains workspace packages (`@sa/axios`, `@sa/hooks`, `@sa/utils`, `@sa/color`, `@sa/materials`, `@sa/scripts`, `@sa/uno-preset`). The app uses:
- **alova** (not raw axios) for API requests, configured in `packages/alova/`
- **Naive UI** for components
- **UnoCSS** for styling (preset in `packages/uno-preset/`)
- **Elegant Router** — file-based route generation from `src/views/` structure (`pnpm gen-route`)
- **Pinia** stores: `app`, `auth`, `chat`, `knowledge-base`, `route`, `tab`, `theme`

## Key service classes

| Service | Role |
|---|---|
| `UploadService` | Chunked upload with MD5 dedup, merge via Kafka |
| `ParseService` | Apache Tika-based document parsing, streaming for large files |
| `VectorizationService` | Batched embedding calls to DashScope (max 10 rows/batch) |
| `ElasticsearchService` | Index management, bulk indexing with IK analyzer |
| `HybridSearchService` | Combined vector + keyword search with permission filtering |
| `ChatHandler` | Orchestrates the full RAG conversation loop |
| `DocumentService` | Document lifecycle: delete from DB + MinIO + ES |
| `UserService` | Auth, registration, org-tag assignment |
| `OrgTagCacheService` | Cached org tag hierarchy for authorization decisions |
| `FileTypeValidationService` | Content-type detection + extension allowlist |

## Database

MySQL 8.0 with JPA (`ddl-auto: update` in dev). Key tables: `users`, `organization_tags`, `file_upload`, `chunk_info`, `document_vectors`. An `AdminUserInitializer` runs on startup to create the default admin account from `admin.*` config properties. An `OrgTagInitializer` seeds default org tags.

## WebSocket auth

WebSocket connections bypass the JWT filter chain (permitted in SecurityConfig). Instead, the frontend first calls `GET /api/chat/websocket-token` to obtain a short-lived token, then passes it as a query parameter when establishing the WebSocket connection. The `ChatWebSocketHandler` validates this token on connect.
