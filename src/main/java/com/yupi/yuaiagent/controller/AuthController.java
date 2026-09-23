package com.yupi.yuaiagent.controller;

import com.yupi.yuaiagent.model.dto.LoginRequest;
import com.yupi.yuaiagent.model.dto.RegisterRequest;
import com.yupi.yuaiagent.model.dto.Result;
import com.yupi.yuaiagent.model.vo.LoginVO;
import com.yupi.yuaiagent.service.UserService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 认证接口：注册、登录
 */
@RestController
@RequestMapping("/auth")
@Slf4j
public class AuthController {

    @Resource
    private UserService userService;

    /**
     * POST /api/auth/register
     * 注册新用户（注册成功后直接登录返回 Token）
     */
    @PostMapping("/register")
    public Result<LoginVO> register(@RequestBody @Valid RegisterRequest request) {
        try {
            boolean ok = userService.register(
                    request.getUsername(),
                    request.getPassword(),
                    request.getEmail());
            if (!ok) {
                return Result.error(400, "用户名已存在");
            }
            // 注册成功后自动登录
            return userService.login(request.getUsername(), request.getPassword())
                    .map(vo -> Result.success("注册成功", vo))
                    .orElse(Result.error("注册后自动登录失败"));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * POST /api/auth/login
     * 用户登录，返回 JWT Token
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody @Valid LoginRequest request) {
        return userService.login(request.getUsername(), request.getPassword())
                .map(vo -> Result.success("登录成功", vo))
                .orElse(Result.error(401, "用户名或密码错误"));
    }
}
