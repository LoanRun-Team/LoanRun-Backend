package com.loanrun.common.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.common.api.ApiError;
import com.loanrun.common.config.ApplicationConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-100)
public class OriginGuardFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private final ApplicationConfig config;
    private final ObjectMapper mapper;

    public OriginGuardFilter(ApplicationConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        boolean disallowedOrigin = origin != null && !config.allowedOrigins().contains(origin);
        boolean missingMutationOrigin = origin == null && !SAFE_METHODS.contains(request.getMethod());
        if (request.getRequestURI().startsWith("/api/") && (disallowedOrigin || missingMutationOrigin)) {
            response.setStatus(403);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            mapper.writeValue(response.getWriter(), ApiError.of("ORIGIN_NOT_ALLOWED", "허용되지 않는 요청 출처입니다.", Map.of()));
            return;
        }
        chain.doFilter(request, response);
    }
}
