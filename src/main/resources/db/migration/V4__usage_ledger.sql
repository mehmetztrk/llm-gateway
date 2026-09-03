-- Append-only usage ledger: one row per request that reached a decision.
--
-- "Append-only" is enforced by there being no UPDATE or DELETE path in the code, and by the
-- absence of any column that would tempt one. A ledger that can be edited is not a ledger, and
-- the whole point of this table is that it can answer "why did that cost that" months later.
--
-- Deliberately denormalised: model and provider are stored as text rather than as foreign keys.
-- A row must remain readable after the provider is decommissioned or the model renamed, and a
-- join that fails is worse than a string that is merely historical.

create table usage_records (
    id                uuid        primary key,
    tenant_id         uuid        not null references tenants (id),
    api_key_id        uuid,
    model             text        not null,
    provider          text        not null,
    prompt_tokens     integer     not null,
    completion_tokens integer     not null,
    -- Cost in micro-units of currency, as an integer. Money in floating point accumulates error
    -- and eventually disagrees with itself; micros keep sub-cent precision in exact arithmetic.
    cost_micros       bigint      not null,
    currency          text        not null default 'USD',
    latency_ms        integer     not null,
    cache_status      text        not null,
    streamed          boolean     not null,
    outcome           text        not null,
    created_at        timestamptz not null default now()
);

-- Usage queries are always "this tenant, this period", so the index leads with tenant_id and then
-- orders by time. Without the composite, a busy tenant's report scans every tenant's rows.
create index usage_records_tenant_created_idx on usage_records (tenant_id, created_at desc);
create index usage_records_created_idx on usage_records (created_at);

comment on column usage_records.cache_status is
    'miss / hit-exact / hit-semantic. A cache hit is recorded with zero cost, which is what makes
     the saving measurable rather than merely asserted.';

comment on column usage_records.api_key_id is
    'Nullable and intentionally not a foreign key: a key may be deleted, and the ledger row that
     records what it did must survive that.';
