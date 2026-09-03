-- Semantic cache, backed by pgvector.
--
-- The exact cache lives in Redis; this table holds the embedding-based one. Two stores rather than
-- one because they answer different questions: Redis answers "have I seen this exact prompt"
-- in microseconds, and this table answers "have I seen something close enough" with a vector scan.
--
-- tenant_id is on the row and in every index, and that is the whole isolation story. A semantic
-- cache without it would happily serve one customer's answer to another customer's question — the
-- single worst failure this system could have.

create extension if not exists vector;

create table semantic_cache_entries (
    id                uuid        primary key,
    tenant_id         uuid        not null references tenants (id) on delete cascade,
    -- The model the answer was produced by. Cached across models would mean a tenant asking for a
    -- large model could be served a small model's answer, which is a different product.
    model             text        not null,
    prompt            text        not null,
    -- 768 dimensions: the width of nomic-embed-text, the embedding model shipped in compose.
    -- Changing the embedding model changes this number and invalidates every row, which is why
    -- ADR-0007 treats the model as part of the schema rather than as configuration.
    embedding         vector(768) not null,
    response_json     jsonb       not null,
    prompt_tokens     integer     not null,
    completion_tokens integer     not null,
    created_at        timestamptz not null default now(),
    expires_at        timestamptz not null,
    hits              integer     not null default 0
);

-- The lookup is always "nearest neighbour *within one tenant*", so tenant_id leads the index.
-- An index on the vector alone would scan across tenants and then filter, which is both slower
-- and one missing WHERE clause away from a cross-tenant leak.
create index semantic_cache_tenant_model_idx on semantic_cache_entries (tenant_id, model);

-- ivfflat with cosine distance. Approximate on purpose: an exact scan is O(rows) per lookup, and
-- a cache that gets slower as it fills is a cache that stops being worth having. The recall cost
-- is acceptable because a miss is merely a slower correct answer.
create index semantic_cache_embedding_idx on semantic_cache_entries
    using ivfflat (embedding vector_cosine_ops) with (lists = 100);

create index semantic_cache_expiry_idx on semantic_cache_entries (expires_at);

comment on column semantic_cache_entries.expires_at is
    'Entries are swept on write rather than by a scheduled job: nothing to run, nothing to own.';
