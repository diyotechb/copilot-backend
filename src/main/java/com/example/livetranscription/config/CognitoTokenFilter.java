package com.example.livetranscription.config;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

// Mirrors Diyo's CognitoTokenValidationFilter — checks issuer + token_use only.
// No signature verification, no expiry check, matching Diyo behaviour.
public class CognitoTokenFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CLAIM_TOKEN_USE = "token_use";
    private static final String ACCESS_TOKEN = "access";

    private final String expectedIssuer;

    public CognitoTokenFilter(String expectedIssuer) {
        this.expectedIssuer = expectedIssuer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null) {
            try {
                JWTClaimsSet claims = JWTParser.parse(token).getJWTClaimsSet();
                if (expectedIssuer.equals(claims.getIssuer())
                        && ACCESS_TOKEN.equals(claims.getClaim(CLAIM_TOKEN_USE))) {
                    String username = claims.getStringClaim("username");
                    Collection<? extends GrantedAuthority> authorities = extractAuthorities(claims);
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(username, null, authorities));
                }
            } catch (Exception ignored) {
                // Bad token → no authentication set; downstream returns 401.
            }
        }
        chain.doFilter(request, response);
    }

    private static String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Collection<? extends GrantedAuthority> extractAuthorities(JWTClaimsSet claims) {
        Object groups = claims.getClaim("cognito:groups");
        if (!(groups instanceof List<?> list)) return List.of();
        return ((List<String>) list).stream()
                .map(g -> new SimpleGrantedAuthority("ROLE_" + g.toUpperCase()))
                .collect(Collectors.toList());
    }
}
