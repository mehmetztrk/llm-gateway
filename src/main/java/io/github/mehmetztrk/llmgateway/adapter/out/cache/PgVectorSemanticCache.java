package io.github.mehmetztrk.llmgateway.adapter.out.cache;

import io.github.mehmetztrk.llmgateway.application.port.out.EmbeddingProvider;
import io.github.mehmetztrk.llmgateway.application.port.out.ResponseCache;
import io.github.mehmetztrk.llmgateway.domain.cache.CachedCompletion;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatMessage;
import io.github.mehmetztrk.llmgateway.domain.chat.ChatRequest;
import io.github.mehmetztrk.llmgateway.domain.chat.Completion;
import io.github.mehmetztrk.llmgateway.domain.chat.FinishReason;
import io.github.mehmetztrk.llmgateway.domain.chat.Role;
import io.github.mehmetztrk.llmgateway.domain.chat.TokenUsage;
import io.github.mehmetztrk.llmgateway.domain.routing.ProviderId;
import io.github.mehmetztrk.llmgateway.domain.tenant.TenantId;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * Semantic cache over pgvector: a prompt that is not identical but close enough.
 *
 * <p><b>Tenant isolation is structural.</b> {@code tenant_id} is the leading column of the lookup
 * and of the index, so a query that forgot it would not merely leak — it would not compile into a
 * sensible plan. The isolation test asserts the behaviour anyway, because "it is in the WHERE
 * clause" is a claim and a test is evidence.
 *
 * <p>Blocking JDBC, like the tenant repository, so every call is moved to the virtual-thread
 * scheduler. Unlike authentication this is not on a cached hot path — a semantic lookup is a vector
 * scan and always costs a round trip. See ADR-0006.
 */
public class PgVectorSemanticCache implements ResponseCache {

    private static final Logger log = LoggerFactory.getLogger(PgVectorSemanticCache.class);

    private final JdbcClient jdbc;
    private final EmbeddingProvider embeddings;
    private final Scheduler blockingScheduler;
    private final double similarityThreshold;
    private final Duration ttl;

    public PgVectorSemanticCache(
            JdbcClient jdbc,
            EmbeddingProvider embeddings,
            Scheduler blockingScheduler,
            double similarityThreshold,
            Duration ttl) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.blockingScheduler = blockingScheduler;
        this.similarityThreshold = similarityThreshold;
        this.ttl = ttl;
    }

    @Override
    public Mono<CachedCompletion> lookup(TenantId tenant, ChatRequest request) {
        String prompt = flatten(request);

        return embeddings
                .embed(prompt)
                .flatMap(vector ->
                        Mono.fromCallable(() -> search(tenant, request, vector)).subscribeOn(blockingScheduler))
                .flatMap(Mono::justOrEmpty)
                .onErrorResume(error -> {
                    log.debug("semantic cache unavailable, treating as a miss: {}", error.toString());
                    return Mono.empty();
                });
    }

    @Override
    public Mono<Void> store(TenantId tenant, ChatRequest request, Completion completion) {
        String prompt = flatten(request);

        return embeddings
                .embed(prompt)
                .flatMap(vector -> Mono.fromCallable(() -> insert(tenant, request, completion, prompt, vector))
                        .subscribeOn(blockingScheduler))
                .onErrorResume(error -> {
                    log.debug("could not write to the semantic cache: {}", error.toString());
                    return Mono.empty();
                })
                .then();
    }

    private Optional<CachedCompletion> search(TenantId tenant, ChatRequest request, float[] vector) {
        // 1 - cosine_distance is cosine similarity, which is the number the threshold is expressed
        // in. Converting here rather than in Java keeps the ordering and the filter in the same
        // expression the index understands.
        record Row(String content, int promptTokens, int completionTokens, String servedBy, double similarity) {}

        Optional<Row> match = jdbc.sql("""
                        select response_json ->> 'content'      as content,
                               prompt_tokens                     as prompt_tokens,
                               completion_tokens                 as completion_tokens,
                               response_json ->> 'servedBy'      as served_by,
                               1 - (embedding <=> cast(:embedding as vector)) as similarity
                          from semantic_cache_entries
                         where tenant_id = :tenantId
                           and model = :model
                           and expires_at > now()
                         order by embedding <=> cast(:embedding as vector)
                         limit 1
                        """)
                .param("embedding", toVectorLiteral(vector))
                .param("tenantId", tenant.value())
                .param("model", request.model())
                .query((ResultSet rs, int rowNum) -> new Row(
                        rs.getString("content"),
                        rs.getInt("prompt_tokens"),
                        rs.getInt("completion_tokens"),
                        rs.getString("served_by"),
                        rs.getDouble("similarity")))
                .optional();

        return match.filter(row -> row.similarity() >= similarityThreshold).map(row -> {
            log.debug("semantic cache hit at similarity {}", row.similarity());
            return CachedCompletion.semantic(
                    new Completion(
                            "chatcmpl-cached-" + UUID.randomUUID(),
                            request.model(),
                            ProviderId.of(row.servedBy()),
                            new ChatMessage(Role.ASSISTANT, row.content()),
                            new TokenUsage(row.promptTokens(), row.completionTokens()),
                            FinishReason.STOP,
                            Instant.now()),
                    row.similarity());
        });
    }

    private Void insert(TenantId tenant, ChatRequest request, Completion completion, String prompt, float[] vector) {
        // Sweep expired rows opportunistically on write. There is no scheduled job to own, nothing
        // to run on exactly one node, and the work happens in proportion to the traffic that
        // created it.
        jdbc.sql("delete from semantic_cache_entries where tenant_id = :tenantId and expires_at <= now()")
                .param("tenantId", tenant.value())
                .update();

        jdbc.sql("""
                        insert into semantic_cache_entries
                            (id, tenant_id, model, prompt, embedding, response_json,
                             prompt_tokens, completion_tokens, expires_at)
                        values
                            (:id, :tenantId, :model, :prompt, cast(:embedding as vector),
                             cast(:response as jsonb), :promptTokens, :completionTokens, :expiresAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenant.value())
                .param("model", request.model())
                .param("prompt", prompt)
                .param("embedding", toVectorLiteral(vector))
                .param("response", responseJson(completion))
                .param("promptTokens", completion.usage().promptTokens())
                .param("completionTokens", completion.usage().completionTokens())
                .param("expiresAt", java.sql.Timestamp.from(Instant.now().plus(ttl)))
                .update();
        return null;
    }

    /** Everything the client said, in order. The answer depends on all of it, so the key must too. */
    private String flatten(ChatRequest request) {
        StringJoiner joiner = new StringJoiner("\n");
        request.messages().forEach(message -> joiner.add(message.role().wireValue() + ": " + message.content()));
        return joiner.toString();
    }

    private String responseJson(Completion completion) {
        // Hand-built rather than Jackson: two fields, and building them here keeps the SQL above
        // readable as the source of truth for what the column contains.
        return "{\"content\":%s,\"servedBy\":%s}"
                .formatted(
                        quote(completion.message().content()),
                        quote(completion.servedBy().value()));
    }

    private String quote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private String toVectorLiteral(float[] vector) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (float value : vector) {
            joiner.add(Float.toString(value));
        }
        return joiner.toString();
    }
}
