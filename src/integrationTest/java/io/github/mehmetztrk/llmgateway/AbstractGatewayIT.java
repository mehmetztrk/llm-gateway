package io.github.mehmetztrk.llmgateway;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for every test that starts the full application context.
 *
 * <p><b>why a real Postgres instead of H2 or a mock.</b> The schema uses {@code uuid}, {@code
 * timestamptz}, a composite primary key and a check constraint, and M6 adds pgvector. An in-memory
 * substitute would pass while the production database rejected the same SQL, which is the specific
 * failure Testcontainers exists to prevent. The image is the same one compose runs.
 *
 * <p><b>why the container is started by hand instead of with {@code @Testcontainers} and
 * {@code @Container}.</b> That extension starts and stops the container around <em>each test
 * class</em>, and a {@code static} field does not change this once the field is inherited: every
 * subclass is its own class, so every subclass gets a fresh container on a fresh random port.
 * Spring, meanwhile, caches one application context across all of them — so from the second class
 * onwards the cached Hikari pool points at a container that has already been stopped. The symptom
 * is not a clean failure but a 30-second connection timeout on every request, which reads like a
 * deadlock and is nothing of the sort.
 *
 * <p>Starting it in a static initialiser and never stopping it — the documented "singleton
 * container" pattern — gives exactly one container for the JVM, matching the single cached context.
 * Testcontainers' Ryuk sidecar removes it when the JVM exits, so nothing leaks.
 *
 * <p>Tests must therefore not assume an empty database. They create their own tenants with unique
 * names instead, which is also what makes them safe to reorder.
 *
 * <p>{@code @ServiceConnection} replaces the usual {@code @DynamicPropertySource} block: Spring
 * Boot reads the JDBC URL, username and password straight off the container.
 */
@SpringBootTest(
        // why a real port rather than the default MOCK environment: this application's contract is
        // made of things a mock server only approximates — SSE framing, content-type negotiation,
        // and the scheduler hop that moves blocking authentication off the event loop. Binding to
        // a real Netty listener means the tests exercise the same path curl does.
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "gateway.security.key-pepper=integration-test-pepper",
            "gateway.security.bootstrap-admin-key=" + AbstractGatewayIT.ADMIN_KEY,
            "gateway.security.bootstrap-tenant-key=" + AbstractGatewayIT.TENANT_KEY,
            // Short enough that a revocation test does not have to wait a minute, long enough that
            // it still proves entries are actually cached.
            "gateway.security.cache-ttl=2s"
        })
@AutoConfigureWebTestClient(timeout = "30s")
public abstract class AbstractGatewayIT {

    public static final String ADMIN_KEY = "llmgw_test_admin_key";
    public static final String TENANT_KEY = "llmgw_test_tenant_key";

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("llmgw")
            .withUsername("llmgw")
            .withPassword("llmgw");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected WebTestClient client;

    /** A client that presents the demo tenant key on every request. */
    protected WebTestClient asTenant() {
        return client.mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TENANT_KEY)
                .build();
    }

    protected WebTestClient asAdmin() {
        return client.mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_KEY)
                .build();
    }

    protected WebTestClient withKey(String key) {
        return client.mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                .build();
    }
}
