package com.example.livetranscription.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class CognitoTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CognitoTokenFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CLAIM_TOKEN_USE = "token_use";
    private static final String ACCESS_TOKEN = "access";

    private final String expectedIssuer;
    private final DefaultJWTProcessor<SecurityContext> jwtProcessor;

    public CognitoTokenFilter(String expectedIssuer) throws MalformedURLException {
        this.expectedIssuer = expectedIssuer;
        JWKSource<SecurityContext> jwkSource = JWKSourceBuilder
                .create(URI.create(expectedIssuer + "/.well-known/jwks.json").toURL())
                .build();
        JWSKeySelector<SecurityContext> keySelector =
                new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource);
        this.jwtProcessor = new DefaultJWTProcessor<>();
        this.jwtProcessor.setJWSKeySelector(keySelector);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null) {
            try {
                JWTClaimsSet claims = jwtProcessor.process(token, null);
                if (expectedIssuer.equals(claims.getIssuer())
                        && ACCESS_TOKEN.equals(claims.getClaim(CLAIM_TOKEN_USE))) {
                    String username = claims.getStringClaim("username");
                    Collection<? extends GrantedAuthority> authorities = extractAuthorities(claims);
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(username, null, authorities));
                }
            } catch (Exception e) {
                log.debug("JWT validation failed: {}", e.getMessage());
            }
        }
        chain.doFilter(request, response);
    }

    private static String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        // Browsers cannot set Authorization on `new WebSocket(url)`. For WS upgrades
        // only, accept the token from a `?token=` query param. Restricted to upgrade
        // requests so HTTP traffic can't smuggle tokens past header logging.
        if (isWebSocketUpgrade(request)) {
            return request.getParameter("token");
        }
        return null;
    }

    private static boolean isWebSocketUpgrade(HttpServletRequest request) {
        String upgrade = request.getHeader("Upgrade");
        return "websocket".equalsIgnoreCase(upgrade);
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
