package com.andang.starter;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
class KspSmsCipherReference {
    private String propertyKey;
    private String label;
    private String jsonKey;
}
