package com.github.galpiii.galpi.domain.project.entity;

import com.github.galpiii.galpi.domain.user.entity.User;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Builder
    private Project(String name, User user) {
        this.name = name;
        this.user = user;
    }

    // 실제 Project 도메인 구현이 병합되기 전 로컬 개발에만 사용하는 임시 생성 메서드입니다.
    public static Project createTemporary(String name, User user) {
        return Project.builder()
                .name(name)
                .user(user)
                .build();
    }
}
