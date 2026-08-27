-- Atomic token bucket with lazy refill.
--
-- KEYS[1]  bucket key
-- ARGV[1]  capacity          (max tokens the bucket holds)
-- ARGV[2]  refill_per_second (tokens added per second)
-- ARGV[3]  now_millis        (server-supplied, see note below)
-- ARGV[4]  requested         (tokens to take; 0 is a pure read)
-- ARGV[5]  force             ("1" = take even if it overdraws, for post-hoc settlement)
--
-- Returns {allowed, remaining, retry_after_millis}
--
-- why a script rather than GET/compute/SET: those are three round trips with a gap in the middle.
-- Two concurrent requests both read the same remaining count, both decide there is room, and both
-- spend it — turning a limit of N into N plus however many callers happened to race. Redis runs a
-- script to completion without interleaving, so the read-modify-write is indivisible.
--
-- why the caller supplies the time instead of the script calling redis.call('TIME'): scripts that
-- read the clock are non-deterministic, which historically made them unsafe to replicate and still
-- makes them impossible to reason about in a test. Passing the time in also lets tests drive the
-- bucket with a fixed clock instead of sleeping.
--
-- why lazy refill rather than a background job: there is nothing to run and nothing to schedule.
-- The bucket is refilled arithmetically from the elapsed time whenever it is next touched, so an
-- idle tenant costs nothing at all and an expired key simply starts full.

local capacity  = tonumber(ARGV[1])
local refill    = tonumber(ARGV[2])
local now       = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])
local force     = ARGV[5] == '1'

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts     = tonumber(state[2])

if tokens == nil or ts == nil then
    -- A bucket that has never been seen, or whose key expired while idle, starts full.
    tokens = capacity
    ts = now
end

local elapsed_ms = math.max(0, now - ts)
tokens = math.min(capacity, tokens + (elapsed_ms / 1000.0) * refill)

local allowed = 0
local retry_after_ms = 0

if force or tokens >= requested then
    tokens = tokens - requested
    if tokens < 0 then
        -- Only reachable when force is set: settlement may overdraw, which delays the next
        -- request rather than retroactively failing one that already completed.
        tokens = 0
    end
    allowed = 1
else
    local shortfall = requested - tokens
    retry_after_ms = math.ceil((shortfall / refill) * 1000)
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)

-- Expire well after a full refill would have happened. Keeping the key alive any longer stores
-- state that is indistinguishable from a full bucket, and Redis is configured with volatile-lru,
-- so a key without a TTL would never be evicted under memory pressure.
local ttl_seconds = math.ceil(capacity / refill) * 2 + 10
redis.call('EXPIRE', KEYS[1], ttl_seconds)

return { allowed, math.floor(tokens), retry_after_ms }
