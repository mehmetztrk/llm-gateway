package io.github.mehmetztrk.llmgateway.domain.tenant;

import java.util.Objects;
import java.util.Set;

/**
 * Which models a tenant may call.
 *
 * <p>why an empty list denies everything rather than allowing everything: the two readings are
 * equally plausible to someone writing configuration, and only one of them is safe when a
 * migration, a typo or a failed insert leaves the list empty. Fail closed.
 */
public record ModelAllowList(Set<String> models) {

    /** The one value that means "anything this gateway serves". */
    public static final String WILDCARD = "*";

    public static final ModelAllowList NONE = new ModelAllowList(Set.of());
    public static final ModelAllowList ANY = new ModelAllowList(Set.of(WILDCARD));

    public ModelAllowList {
        Objects.requireNonNull(models, "models");
        models = Set.copyOf(models);
    }

    public static ModelAllowList of(String... models) {
        return new ModelAllowList(Set.of(models));
    }

    public boolean permits(String model) {
        return models.contains(WILDCARD) || models.contains(model);
    }

    public boolean isEmpty() {
        return models.isEmpty();
    }
}
