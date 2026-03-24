package com.ragnarok.antifraude.infrastructure.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

/**
 * Filtro de autenticação mínimo entre serviços.
 * Valida header X-API-Key em todos os endpoints exceto /health e /swagger.
 *
 * Em produção, trocar por mTLS ou JWT com service mesh.
 */
@Component
@Order(1)
public class ApiKeyFilter implements Filter {

    private static final String API_KEY_HEADER = "X-API-Key";

    private static final Set<String> PUBLIC_PATHS = Set.of(
        "/api/fraud/health",
        "/swagger-ui",
        "/v3/api-docs",
        "/actuator"
    );

    @Value("${antifraude.api-key:#{null}}")
    private String expectedApiKey;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        String path = httpReq.getRequestURI();

        // Paths públicos — sem autenticação
        if (isPublicPath(path)) {
            chain.doFilter(request, response);
            return;
        }

        // Se API key não está configurada — desabilita filtro (dev mode)
        if (expectedApiKey == null || expectedApiKey.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        // Valida API key
        String providedKey = httpReq.getHeader(API_KEY_HEADER);
        if (expectedApiKey.equals(providedKey)) {
            chain.doFilter(request, response);
        } else {
            HttpServletResponse httpRes = (HttpServletResponse) response;
            httpRes.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            httpRes.setContentType("application/json");
            httpRes.getWriter().write("{\"error\":\"Invalid or missing API key\"}");
        }
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }
}
