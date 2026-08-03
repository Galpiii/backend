package com.github.galpiii.galpi.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 테스트 컨텍스트에서는 배치가 돌지 않게 한다. 테스트가 스케줄러의 부수효과에 흔들리면 안 된다.
 */
@Profile("!test")
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
