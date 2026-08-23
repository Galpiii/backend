package com.github.galpiii.galpi.domain.project.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 프로젝트 관련 상한.
 *
 * @param maxPerUser  사용자 한 명이 가질 수 있는 프로젝트 수. 삭제한 것은 세지 않는다.
 *                    기능 제한이 아니라 남용 방지용이라 넉넉하게 잡는다.
 *                    <b>정확한 상한이 아니라 soft limit이다</b> — 개수 확인과 생성 사이에
 *                    잠금이 없어 동시 요청이 겹치면 이 값을 조금 넘길 수 있다. 과금·정합성
 *                    경계가 아니므로 정상 경로마다 사용자 행을 잠그는 비용을 치르지 않는다.
 *                    정확한 상한이 필요해지면 별도 quota 행이나 advisory lock이 필요하다
 * @param maxPageSize 목록 한 페이지의 최대 크기. 클라이언트가 보낸 size를 여기서 자른다
 */
@Validated
@ConfigurationProperties(prefix = "galpi.project")
public record ProjectProperties(
        @DefaultValue("50") @Min(1) int maxPerUser,
        @DefaultValue("100") @Min(1) @Max(200) int maxPageSize,
        @DefaultValue("20") @Min(1) int defaultPageSize
) {
}
