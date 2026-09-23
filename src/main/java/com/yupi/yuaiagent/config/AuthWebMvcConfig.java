package com.yupi.yuaiagent.config;

import com.yupi.yuaiagent.filter.JwtAuthFilter;
import jakarta.annotation.Resource;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 认证配置：注册 JWT 过滤器 + 拦截需要登录的路径
 *
 * <p>拦截策略：
 * <ul>
 *   <li>/auth/* —— 放行（注册、登录不需要 Token）</li>
 *   <li>/ai/*   —— 需要登录（有 Token 才能调用 AI 服务）</li>
 *   <li>/health  —— 放行（健康检查）</li>
 * </ul>
 */
@Configuration
public class AuthWebMvcConfig implements WebMvcConfigurer {

    @Resource
    private JwtAuthFilter jwtAuthFilter;

    /**
     * 注册 JWT 过滤器，设置匹配路径（只拦截 /ai/*）
     */
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtFilterRegistration() {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(jwtAuthFilter);
        registration.addUrlPatterns("/ai/*");
        registration.setOrder(1);
        return registration;
    }

    /**
     * 添加拦截器，排除公开路径
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginCheckInterceptor())
                .addPathPatterns("/ai/**")           // AI 接口需要登录
                .excludePathPatterns(
                        "/auth/**",                   // 认证接口本身放行
                        "/health",                    // 健康检查放行
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/swagger-resources/**",
                        "/webjars/**",
                        "/doc.html",
                        "/favicon.ico"
                );
    }
}
