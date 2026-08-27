-- Tenants and their API keys.
--
-- Design notes that are not obvious from the DDL:
--
--  * Keys are stored as an HMAC-SHA256 digest, never the key itself and never anything reversible.
--    A dump of this table lets nobody call the gateway. See ADR-0009 for why not Argon2.
--  * key_prefix is the only part of a key ever shown again, so an operator can identify a key in
--    a list or a log line without the table holding anything that could authenticate.
--  * Allowed models live in a child table rather than a text[] column: M4 attaches per-model
--    limits, and a scalar column would have to be reshaped at that point.

create table tenants (
    id          uuid        primary key,
    name        text        not null unique,
    active      boolean     not null default true,
    created_at  timestamptz not null default now()
);

comment on column tenants.active is
    'Soft disable. Deleting a tenant would orphan its usage ledger rows, which are append-only.';

create table api_keys (
    id          uuid        primary key,
    tenant_id   uuid        not null references tenants (id) on delete cascade,
    -- Unique so an accidental duplicate issue is a constraint violation rather than an
    -- ambiguous lookup that silently picks one of two tenants.
    key_hash    text        not null unique,
    key_prefix  text        not null,
    role        text        not null check (role in ('TENANT', 'ADMIN')),
    label       text,
    created_at  timestamptz not null default now(),
    revoked_at  timestamptz
);

-- The authentication hot path is a single lookup by hash. Unique already gives an index; this
-- one supports "list the keys of this tenant" in the admin API.
create index api_keys_tenant_id_idx on api_keys (tenant_id);

comment on column api_keys.revoked_at is
    'Null means active. Revocation is never a delete: an audit trail that can be erased is not one.';

create table tenant_models (
    tenant_id uuid not null references tenants (id) on delete cascade,
    model     text not null,
    primary key (tenant_id, model)
);

comment on table tenant_models is
    'Per-tenant model allow-list. The literal value ''*'' permits every model the gateway serves.';
