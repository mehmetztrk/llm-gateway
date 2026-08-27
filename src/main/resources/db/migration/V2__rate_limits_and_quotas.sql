-- Per-tenant rate limits and monthly quotas.
--
-- Limits live on the tenant, with optional per-key overrides. A key without an override inherits
-- its tenant's limit rather than being unlimited: the safe reading of "not configured".
--
-- Both limits are enforced together — a key cannot exceed its own limit, and the sum of a tenant's
-- keys cannot exceed the tenant's. That is the point of having both.

alter table tenants
    add column requests_per_minute integer not null default 60,
    add column tokens_per_minute   bigint  not null default 100000,
    -- Null means no monthly ceiling. Explicit, rather than a sentinel like 0 or -1 that reads as
    -- "block everything" to anyone who has not checked.
    add column monthly_token_budget bigint,
    add column quota_soft_threshold numeric(4, 3) not null default 0.800;

alter table tenants
    add constraint tenants_requests_per_minute_positive check (requests_per_minute > 0),
    add constraint tenants_tokens_per_minute_positive check (tokens_per_minute > 0),
    add constraint tenants_monthly_budget_positive check (monthly_token_budget is null or monthly_token_budget > 0),
    add constraint tenants_soft_threshold_fraction check (quota_soft_threshold > 0 and quota_soft_threshold <= 1);

comment on column tenants.quota_soft_threshold is
    'Fraction of the monthly budget at which responses start carrying a warning header.';

alter table api_keys
    -- Null means "inherit the tenant limit". A key-specific value is only ever a *tighter* bound
    -- in practice, because the tenant bucket is checked as well.
    add column requests_per_minute integer,
    add column tokens_per_minute   bigint;

alter table api_keys
    add constraint api_keys_requests_per_minute_positive
        check (requests_per_minute is null or requests_per_minute > 0),
    add constraint api_keys_tokens_per_minute_positive
        check (tokens_per_minute is null or tokens_per_minute > 0);
