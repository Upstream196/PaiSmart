package com.yizhaoqi.smartpai.config;

import com.yizhaoqi.smartpai.service.CustomUserDetailsService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 自定义的过滤器，用于解析请求头中的 JWT Token，并验证用户身份。
 * 如果 Token 有效，则将用户信息和权限设置到 Spring Security 的上下文中，后续的请求可以基于用户角色进行授权。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtils jwtUtils; // 用于生成和解析 JWT Token

    @Autowired
    private CustomUserDetailsService userDetailsService; // 加载用户详细信息

    /**
     * 每次请求都会调用此方法，用于解析 JWT Token 并设置用户认证信息。
     * 实现无感知的token自动刷新机制。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            // 从请求头中提取 JWT Token
            String token = extractToken(request);
            // 检查token是否为非空值
            if (token != null) {
                String newToken = null;
                String username = null;
                
                // 首先检查token是否有效
                if (jwtUtils.validateToken(token)) {
                    // Token有效，检查是否需要预刷新
                    if (jwtUtils.shouldRefreshToken(token)) {
                        newToken = jwtUtils.refreshToken(token);
                        // 检查新令牌是否不为空 ->刷新之后为什么还要检查令牌是否为空呢？
                        if (newToken != null) {
                            logger.info("Token auto-refreshed proactively");
                        }
                    }
                    // 从JWT令牌中提取用户名
                    username = jwtUtils.extractUsernameFromToken(token);
                } else {
                    // Token无效/过期，检查是否在宽限期内可以刷新
                    if (jwtUtils.canRefreshExpiredToken(token)) {
                        newToken = jwtUtils.refreshToken(token);
                        // 检查新令牌是否不为空
                        if (newToken != null) {  // 如果新令牌存在，则执行后续操作
                            logger.info("Expired token refreshed within grace period");
                            username = jwtUtils.extractUsernameFromToken(newToken);
                        }
                    }
                }
                
                // 如果有新token，通过响应头返回给前端
                if (newToken != null) {
                    response.setHeader("New-Token", newToken);
                }
                
                // 设置用户认证信息
                if (username != null && !username.isEmpty()) {
                    // 通过用户名加载用户详细信息
                    UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                    // 创建一个安全上下文对象，用于存储用户的认证信息与权限信息
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
                    // 使用WebAuthenticationDetailsSource构建Web认证详情对象，添加用户的登录环境
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    // 这行代码通常用于用户登录成功后，将认证信息保存到安全上下文中，以便后续的权限验证
                    SecurityContextHolder.getContext().setAuthentication(authentication); //将当前安全信息上下文存储到安全仓库中
                }
            }
            filterChain.doFilter(request, response); // 将HTTP中的请求与响应对象继续传递到下一个过滤器
        } catch (Exception e) {
            // 记录错误日志
            logger.error("Cannot set user authentication: {}", e);
        }
    }

    /**
     * 从请求头中提取 JWT Token。
     */
    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7); // 去掉 "Bearer " 前缀
        }
        return null;
    }
}
