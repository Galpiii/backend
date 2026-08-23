package com.github.galpiii.galpi.domain.collection.entity;

import com.github.galpiii.galpi.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * PR이 바꾼 파일 한 개의 메타데이터.
 *
 * <p><b>{@code patch} 필드를 추가하지 마라.</b> diff 원문을 DB에 영구 보관하는 것은 코드 원문
 * 취급 정책 위반이다. patch는 수집 시점에 메모리에서 분석 파이프라인으로만 넘어가고 버려진다.
 * PR 카드 렌더링과 기능 대조 근거 표시는 경로·변경 유형·변경량으로 충분하다.
 *
 * <p>{@code patchOmitted}는 GitHub이 큰 파일이나 바이너리라서 patch를 응답에 넣지 않았다는
 * 사실만 기록한다. 우리가 지운 것과 GitHub이 안 준 것은 다르고, 대조 결과의 신뢰도를 보여줄 때
 * 후자를 알아야 한다.
 */
@Entity
@Getter
@Table(
        name = "pull_request_files",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pull_request_files_pull_request_path",
                columnNames = {"pull_request_id", "path"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PullRequestFile extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pull_request_id", nullable = false)
    private PullRequest pullRequest;

    @Column(nullable = false, length = 1000)
    private String path;

    @Column(length = 1000)
    private String previousPath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChangeStatus changeStatus;

    @Column(nullable = false)
    private int additions;

    @Column(nullable = false)
    private int deletions;

    @Column(nullable = false)
    private int changes;

    @Column(nullable = false)
    private boolean patchOmitted;

    private PullRequestFile(PullRequest pullRequest, String path, String previousPath,
                            ChangeStatus changeStatus, int additions, int deletions, int changes,
                            boolean patchOmitted) {
        this.pullRequest = pullRequest;
        this.path = path;
        this.previousPath = previousPath;
        this.changeStatus = changeStatus;
        this.additions = additions;
        this.deletions = deletions;
        this.changes = changes;
        this.patchOmitted = patchOmitted;
    }

    public static PullRequestFile of(PullRequest pullRequest, String path, String previousPath,
                                     ChangeStatus changeStatus, int additions, int deletions,
                                     int changes, boolean patchOmitted) {
        return new PullRequestFile(pullRequest, path, previousPath, changeStatus, additions,
                deletions, changes, patchOmitted);
    }
}
