package io.github.mehmetztrk.llmgateway.adapter.out.health;

import io.github.mehmetztrk.llmgateway.application.port.out.ProviderHealthRegistry;
import io.github.mehmetztrk.llmgateway.application.service.ProviderRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Probes every provider on a schedule.
 *
 * <p><b>Why probe at all when live traffic already reports failures.</b> Traffic tells you a
 * provider is broken; only a probe tells you it is fixed. Without one, a provider marked down would
 * stay down until someone happened to send it a request — and routing deliberately avoids sending
 * it requests. The probe is what closes that loop.
 *
 * <p>Probes are fired concurrently rather than one after another, so a provider that has stopped
 * answering cannot delay the check of every provider behind it in the list.
 */
public class ProviderHealthMonitor implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ProviderHealthMonitor.class);

    private final ProviderRegistry providers;
    private final ProviderHealthRegistry health;
    private final Duration interval;
    private final Duration timeout;

    private Disposable subscription;

    public ProviderHealthMonitor(
            ProviderRegistry providers, ProviderHealthRegistry health, Duration interval, Duration timeout) {
        this.providers = providers;
        this.health = health;
        this.interval = interval;
        this.timeout = timeout;
    }

    /**
     * why start on ApplicationReadyEvent rather than in the constructor: probing a provider before
     * the context has finished starting produces failures that say more about start-up ordering
     * than about the provider.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        log.info("probing {} providers every {}", providers.all().size(), interval);
        subscription = Flux.interval(Duration.ZERO, interval)
                .onBackpressureDrop()
                .concatMap(tick -> probeAll())
                .subscribeOn(Schedulers.parallel())
                .subscribe(unused -> {}, error -> log.error("health monitor stopped unexpectedly", error));
    }

    private Flux<Void> probeAll() {
        return Flux.fromIterable(providers.all())
                .flatMap(provider -> provider.isHealthy()
                        .timeout(timeout)
                        .onErrorReturn(false)
                        .doOnNext(healthy -> {
                            if (healthy) {
                                health.recordSuccess(provider.id());
                            } else {
                                health.recordFailure(provider.id());
                            }
                        })
                        .then())
                // Concurrency, not sequence: one unreachable provider must not hold up the probe
                // of the one that is about to take its traffic.
                .subscribeOn(Schedulers.parallel());
    }

    @Override
    public void destroy() {
        if (subscription != null) {
            subscription.dispose();
        }
    }
}
