package com.github.galpiii.galpi.global.crypto;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

@Validated
@ConfigurationProperties(prefix = "galpi.crypto.token")
public record TokenEncryptionProperties(
        @DefaultValue("1") int currentVersion,
        @NotEmpty Map<Integer, String> keys
) {

    public String keyFor(int version) {
        String key = keys.get(version);
        if (key == null) {
            throw new IllegalStateException(
                    "토큰 암호화 키 버전 " + version + "이 설정에 없습니다. galpi.crypto.token.keys를 확인하세요.");
        }
        return key;
    }
}
