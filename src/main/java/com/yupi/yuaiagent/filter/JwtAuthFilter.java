package com.yupi.yuaiagent.filter;

import com.yupi.yuaiagent.model.entity.User;
import com.yupi.yuaiagent.service.UserService;
import com.yupi.yuaiagent.utils.JwtUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * JWT 认证过滤器：验证请求头中的 Token，将登录用户信息写入 request attribute
 *
 * <p>拦截路径由 WebMvcConfigurer（AuthWebMvcConfig）配置，
 * 默认拦截所有 /ai/* 接口。
 */
@Component
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    /** 请求头中 Token 的 key */
    public static final String AUTH_HEADER = "Authorization";

    /** Token 前缀（Bearer xxx） */
    private static final String BEARER_PREFIX = "Bearer ";

    /** 写入 request attribute 的 key */
    public static final String CURRENT_USER_ATTR = "currentUser";

    @Resource
    private JwtUtils jwtUtils;

    @Resource
    private UserService userService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length());
        if (!jwtUtils.validateToken(token)) {
            // Token 无效，但不过滤——让业务层统一返回 401
            filterChain.doFilter(request, response);
            return;
        }

        String username = jwtUtils.getUsernameFromToken(token);
        Optional<User> userOpt = userService.findByUsername(username);
        if (userOpt.isPresent()) {
            // 将当前登录用户写入 request attribute，业务代码可随时取用
            request.setAttribute(CURRENT_USER_ATTR, userOpt.get());
            log.debug("[JWT] 认证用户: {}", username);
        }

        filterChain.doFilter(request, response);
    }
}
