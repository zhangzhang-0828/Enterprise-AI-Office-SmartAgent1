package com.yupi.yuaiagent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class User {

    private Long id;

    private String username;

    /** 密码经过 BCrypt 哈希存储 */
    private String password;

    private String email;

    private String role = "user";

    private Integer status = 1;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
