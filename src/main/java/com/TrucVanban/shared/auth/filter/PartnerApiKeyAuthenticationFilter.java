package com.TrucVanban.shared.auth.filter;

import com.TrucVanban.shared.ResponseData;
import com.TrucVanban.shared.auth.service.SystemConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class PartnerApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";
    public static final String SYSTEM_CODE_HEADER = "X-System-Code";

    private final SystemConfigService systemConfigService;
    private final ObjectMapper objectMapper;

    private final RequestMatcher protectedEndpoints = new OrRequestMatcher(
            new AntPathRequestMatcher("/api/**")
    );
    private final RequestMatcher excludedEndpoints = new OrRequestMatcher(
            new AntPathRequestMatcher("/api/public/**")
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        return excludedEndpoints.matches(request) || !protectedEndpoints.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String systemCode = request.getHeader(SYSTEM_CODE_HEADER);
        String apiKey = request.getHeader(API_KEY_HEADER);

        if (!systemConfigService.isValidInboundAccess(systemCode, apiKey)) {
            writeUnauthorized(response);
            SecurityContextHolder.clearContext();
            return;
        }

        try {
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(systemCode, apiKey, AuthorityUtils.NO_AUTHORITIES);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        ResponseData<Object> body = ResponseData.builder()
                .success(false)
                .message("X-System-Code hoặc X-API-Key không hợp lệ")
                .data(null)
                .build();

        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
