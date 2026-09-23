package com.yupi.yuaiagent.config;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.filter.JwtAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录检查拦截器：验证请求是否携带有效 Token
 *
 * <p>配合 JwtAuthFilter 使用：filter 负责解析 Token 并写入 request attribute，
 * 此拦截器负责检查 attribute 是否存在。
 */
@Component
@Slf4j
public class LoginCheckInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                            HttpServletResponse response,
                            Object handler) throws Exception {
        // 已经在 JwtAuthFilter 中写入了 currentUser
        Object currentUser = request.getAttribute(JwtAuthFilter.CURRENT_USER_ATTR);
        if (currentUser == null) {
            log.warn("[拦截] 未登录访问: {} {}", request.getMethod(), request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"请先登录\",\"data\":null}");
            return false;
        }
        return true;
    }
}
