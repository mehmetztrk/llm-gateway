package io.github.mehmetztrk.llmgateway.adapter.in.web.security;

import io.github.mehmetztrk.llmgateway.domain.tenant.AuthenticatedCaller;
import java.util.Collection;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * A resolved caller, in the shape Spring Security expects.
 *
 * <p>The presented key is <b>not</b> retained after authentication. Spring's contract invites you
 * to keep credentials on the token; doing so here would leave the plaintext key reachable from any
 * component holding the security context, and one careless {@code toString()} away from a log file.
 */
public final class ApiKeyAuthentication extends AbstractAuthenticationToken {

    private final AuthenticatedCaller caller;

    public ApiKeyAuthentication(AuthenticatedCaller caller) {
        super(authorities(caller));
        this.caller = caller;
        setAuthenticated(true);
    }

    private static Collection<GrantedAuthority> authorities(AuthenticatedCaller caller) {
        return List.of(new SimpleGrantedAuthority("ROLE_" + caller.role().name()));
    }

    public AuthenticatedCaller caller() {
        return caller;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return caller;
    }

    @Override
    public String getName() {
        return caller.tenantId().toString();
    }

    /** Guards against the whole object ending up in a log line. */
    @Override
    public String toString() {
        return "ApiKeyAuthentication[tenant=" + caller.tenantId() + ", role=" + caller.role() + "]";
    }
}
