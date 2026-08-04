package com.github.galpiii.galpi.domain.user.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nickname;

    @Builder
    private User(String nickname) {
        this.nickname = nickname;
    }

    // 실제 User 도메인 구현이 병합되기 전 로컬 개발에만 사용하는 임시 생성 메서드입니다.
    public static User createTemporary(String nickname) {
        return User.builder()
                .nickname(nickname)
                .build();
    }
}
