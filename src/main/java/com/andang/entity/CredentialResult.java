package com.andang.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CredentialResult {
    private String content;
    private int dbType;
    private long expireTime;
}
