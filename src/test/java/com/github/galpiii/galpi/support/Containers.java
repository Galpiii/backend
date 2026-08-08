package com.github.galpiii.galpi.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트 전체가 공유하는 컨테이너.
 *
 * <p>클래스마다 띄우면 느리므로 JVM당 한 번만 올리고 JVM이 끝날 때 함께 내린다
 * (Ryuk이 정리한다). 상태를 남기는 테스트는 각자 격리를 책임진다.
 */
public final class Containers {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:17-alpine");
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");

    private static final PostgreSQLContainer<?> POSTGRES;
    private static final GenericContainer<?> REDIS;

    static {
        POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("galpi")
                .withUsername("galpi")
                .withPassword("galpi");
        REDIS = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
        POSTGRES.start();
        REDIS.start();
    }

    private Containers() {
    }

    public static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    public static String username() {
        return POSTGRES.getUsername();
    }

    public static String password() {
        return POSTGRES.getPassword();
    }

    public static String redisHost() {
        return REDIS.getHost();
    }

    public static int redisPort() {
        return REDIS.getMappedPort(6379);
    }
}
