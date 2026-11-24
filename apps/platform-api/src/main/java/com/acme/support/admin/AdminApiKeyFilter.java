package com.acme.support.admin;

import com.acme.support.shared.SecureCompare;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Accepts the X-Admin-Api-Key header via constant-time comparison. Not a bean, so Boot does not auto-register it. */
final class AdminApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Admin-Api-Key";

    private final String expectedKey;

    AdminApiKeyFilter(String expectedKey) {
        this.expectedKey = expectedKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided != null && SecureCompare.equal(provided, expectedKey)) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "admin-api-key", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        }
        chain.doFilter(request, response);
    }
}
