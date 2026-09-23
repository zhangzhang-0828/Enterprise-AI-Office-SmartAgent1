package com.yupi.yuaiagent.service;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.model.entity.User;
import com.yupi.yuaiagent.model.vo.LoginVO;
import com.yupi.yuaiagent.utils.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 用户服务：注册、登录、查询
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final JdbcTemplate jdbcTemplate;
    private final JwtUtils jwtUtils;

    private static final RowMapper<User> USER_ROW_MAPPER = (rs, rowNum) -> {
        User user = new User();
        user.setId(rs.getLong("id"));
        user.setUsername(rs.getString("username"));
        user.setPassword(rs.getString("password"));
        user.setEmail(rs.getString("email"));
        user.setRole(rs.getString("role"));
        user.setStatus(rs.getInt("status"));
        user.setCreateTime(toLocalDateTime(rs.getTimestamp("create_time")));
        user.setUpdateTime(toLocalDateTime(rs.getTimestamp("update_time")));
        return user;
    };

    private static LocalDateTime toLocalDateTime(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    /**
     * 注册：用户名不能重复
     *
     * @return true 注册成功，false 用户名已存在
     */
    public boolean register(String username, String password, String email) {
        if (StrUtil.isBlank(username) || StrUtil.isBlank(password)) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }
        if (findByUsername(username).isPresent()) {
            return false; // 用户名已存在
        }
        String hashed = jwtUtils.encodePassword(password);
        String sql = """
                INSERT INTO "user" (username, password, email, role, status, create_time, update_time)
                VALUES (?, ?, ?, 'user', 1, NOW(), NOW())
                """;
        jdbcTemplate.update(sql, username, hashed, StrUtil.blankToDefault(email, ""));
        log.info("[注册] 用户 {} 注册成功", username);
        return true;
    }

    /**
     * 登录：验证密码，生成 JWT
     */
    public Optional<LoginVO> login(String username, String password) {
        if (StrUtil.isBlank(username) || StrUtil.isBlank(password)) {
            return Optional.empty();
        }
        Optional<User> userOpt = findByUsername(username);
        if (userOpt.isEmpty()) {
            log.warn("[登录] 用户不存在: {}", username);
            return Optional.empty();
        }
        User user = userOpt.get();
        if (user.getStatus() != 1) {
            log.warn("[登录] 用户 {} 已被禁用", username);
            return Optional.empty();
        }
        if (!jwtUtils.checkPassword(password, user.getPassword())) {
            log.warn("[登录] 密码错误: {}", username);
            return Optional.empty();
        }
        String token = jwtUtils.generateToken(username, user.getId());
        log.info("[登录] 用户 {} 登录成功", username);
        return Optional.of(new LoginVO(token, username, jwtUtils.getExpiresInMs() / 1000));
    }

    /**
     * 根据用户名查找用户
     */
    public Optional<User> findByUsername(String username) {
        if (StrUtil.isBlank(username)) {
            return Optional.empty();
        }
        String sql = """
                SELECT id, username, password, email, role, status, create_time, update_time
                FROM "user"
                WHERE username = ?
                """;
        List<User> list = jdbcTemplate.query(sql, USER_ROW_MAPPER, username);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 根据 userId 查找用户
     */
    public Optional<User> findById(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        String sql = """
                SELECT id, username, password, email, role, status, create_time, update_time
                FROM "user"
                WHERE id = ?
                """;
        List<User> list = jdbcTemplate.query(sql, USER_ROW_MAPPER, userId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }
}
