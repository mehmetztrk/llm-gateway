package io.github.mehmetztrk.llmgateway.adapter.out.persistence;

import io.github.mehmetztrk.llmgateway.application.port.out.UsageLedger;
import io.github.mehmetztrk.llmgateway.domain.cache.CacheStatus;
import io.github.mehmetztrk.llmgateway.domain.chat.TokenUsage;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import io.github.mehmetztrk.llmgateway.domain.tenant.TenantId;
import io.github.mehmetztrk.llmgateway.domain.usage.Money;
import io.github.mehmetztrk.llmgateway.domain.usage.UsageRecord;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Writes ledger rows in batches, off the request path.
 *
 * <p><b>The whole point is that {@link #record} never touches a database.</b> It offers the row to a
 * bounded queue and returns; a single drainer thread batches whatever has accumulated and writes it.
 * A synchronous insert per request would add a connection checkout and a round trip to every
 * completion — inside a p99 budget of 15 ms, which is the budget the rest of the design exists to
 * protect.
 *
 * <p><b>The queue is bounded, and a full queue drops rows.</b> That is the uncomfortable choice, and
 * it is the right one: the alternatives are blocking the request (turning a ledger problem into a
 * latency problem) or growing without limit (turning it into an out-of-memory kill). Drops are
 * counted and logged, so the ledger's own gaps are visible rather than silent — a ledger that
 * quietly loses rows is worse than one that admits it did.
 *
 * <p>The drainer runs on a virtual thread: it blocks on the queue and on JDBC, which is exactly what
 * virtual threads are for, and it costs no platform thread while idle.
 */
public class BufferedUsageLedger implements UsageLedger, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(BufferedUsageLedger.class);

    private final JdbcClient jdbc;
    private final BlockingQueue<UsageRecord> queue;
    private final ExecutorService drainer =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private final int batchSize;
    private final Duration flushInterval;
    private final LongAdder dropped = new LongAdder();
    private final LongAdder written = new LongAdder();

    private volatile boolean running = true;

    public BufferedUsageLedger(JdbcClient jdbc, int queueCapacity, int batchSize, Duration flushInterval) {
        this.jdbc = jdbc;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.batchSize = batchSize;
        this.flushInterval = flushInterval;
        drainer.submit(this::drainLoop);
    }

    @Override
    public void record(UsageRecord usage) {
        // offer, not put: put would block the caller when the queue is full, which is precisely
        // what this class exists to avoid.
        if (!queue.offer(usage)) {
            dropped.increment();
            if (dropped.sum() % 100 == 1) {
                log.warn("usage ledger queue is full; {} rows dropped so far", dropped.sum());
            }
        }
    }

    private void drainLoop() {
        List<UsageRecord> batch = new ArrayList<>(batchSize);
        while (running) {
            try {
                UsageRecord first = queue.poll(flushInterval.toMillis(), TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
                writeBatch(batch);
                batch.clear();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // A failed batch must not kill the drainer, or one bad row would silently stop all
                // accounting for the lifetime of the process.
                log.error("failed to write a usage batch of {} rows", batch.size(), e);
                batch.clear();
            }
        }
    }

    private void writeBatch(List<UsageRecord> batch) {
        for (UsageRecord usage : batch) {
            jdbc.sql("""
                            insert into usage_records
                                (id, tenant_id, api_key_id, model, provider, prompt_tokens,
                                 completion_tokens, cost_micros, currency, latency_ms, cache_status,
                                 streamed, outcome, created_at)
                            values
                                (:id, :tenantId, :apiKeyId, :model, :provider, :promptTokens,
                                 :completionTokens, :costMicros, :currency, :latencyMs, :cacheStatus,
                                 :streamed, :outcome, :createdAt)
                            """)
                    .param("id", usage.id())
                    .param("tenantId", usage.tenantId().value())
                    .param("apiKeyId", usage.apiKeyId())
                    .param("model", usage.model())
                    .param("provider", usage.provider().value())
                    .param("promptTokens", usage.usage().promptTokens())
                    .param("completionTokens", usage.usage().completionTokens())
                    .param("costMicros", usage.cost().micros())
                    .param("currency", usage.cost().currency())
                    .param("latencyMs", usage.latency().toMillis())
                    .param("cacheStatus", usage.cacheStatus().wireValue())
                    .param("streamed", usage.streamed())
                    .param("outcome", usage.outcome().name())
                    .param("createdAt", Timestamp.from(usage.createdAt()))
                    .update();
        }
        written.add(batch.size());
    }

    @Override
    public List<UsageRecord> findByTenant(TenantId tenant, Instant from, Instant to, int limit) {
        return jdbc.sql("""
                        select id, tenant_id, api_key_id, model, provider, prompt_tokens,
                               completion_tokens, cost_micros, currency, latency_ms, cache_status,
                               streamed, outcome, created_at
                          from usage_records
                         where tenant_id = :tenantId
                           and created_at >= :from
                           and created_at < :to
                         order by created_at desc
                         limit :limit
                        """)
                .param("tenantId", tenant.value())
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .param("limit", limit)
                .query((ResultSet rs, int rowNum) -> new UsageRecord(
                        rs.getObject("id", UUID.class),
                        TenantId.of(rs.getObject("tenant_id", UUID.class)),
                        rs.getObject("api_key_id", UUID.class),
                        rs.getString("model"),
                        ProviderId.of(rs.getString("provider")),
                        new TokenUsage(rs.getInt("prompt_tokens"), rs.getInt("completion_tokens")),
                        new Money(rs.getLong("cost_micros"), rs.getString("currency")),
                        Duration.ofMillis(rs.getInt("latency_ms")),
                        parseCacheStatus(rs.getString("cache_status")),
                        rs.getBoolean("streamed"),
                        UsageRecord.Outcome.valueOf(rs.getString("outcome")),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    @Override
    public UsageSummary summarise(TenantId tenant, Instant from, Instant to) {
        // Aggregated in SQL rather than by loading rows: a month of traffic for a busy tenant is
        // millions of rows, and summing them in the JVM would be a memory profile nobody wants.
        return jdbc.sql("""
                        select count(*)                                            as requests,
                               count(*) filter (where cache_status <> 'miss')       as cached,
                               coalesce(sum(prompt_tokens), 0)                      as prompt_tokens,
                               coalesce(sum(completion_tokens), 0)                  as completion_tokens,
                               coalesce(sum(cost_micros), 0)                        as cost_micros
                          from usage_records
                         where tenant_id = :tenantId
                           and created_at >= :from
                           and created_at < :to
                        """)
                .param("tenantId", tenant.value())
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((ResultSet rs, int rowNum) -> new UsageSummary(
                        rs.getLong("requests"),
                        rs.getLong("cached"),
                        rs.getLong("prompt_tokens"),
                        rs.getLong("completion_tokens"),
                        rs.getLong("cost_micros"),
                        "USD"))
                .single();
    }

    /** Test seam: lets an integration test wait for the buffer to reach the database. */
    public long writtenCount() {
        return written.sum();
    }

    public long droppedCount() {
        return dropped.sum();
    }

    private CacheStatus parseCacheStatus(String wire) {
        return switch (wire) {
            case "hit-exact" -> CacheStatus.EXACT_HIT;
            case "hit-semantic" -> CacheStatus.SEMANTIC_HIT;
            default -> CacheStatus.MISS;
        };
    }

    @Override
    public void destroy() {
        running = false;
        drainer.shutdownNow();
    }
}
