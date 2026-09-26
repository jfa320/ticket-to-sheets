package com.opencode.facturas.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final String REQUEST_ID = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || !requestId.matches("[a-zA-Z0-9_-]{1,64}")) {
            requestId = UUID.randomUUID().toString().substring(0, 8);
        }

        long startedAt = System.nanoTime();
        MDC.put(REQUEST_ID, requestId);
        response.setHeader("X-Request-Id", requestId);
        log.info("Solicitud iniciada: {} {}", request.getMethod(), request.getRequestURI());
        try {
            filterChain.doFilter(request, response);
        } finally {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("Solicitud finalizada: {} {} -> {} ({} ms)",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), elapsedMs);
            MDC.remove(REQUEST_ID);
        }
    }
}
