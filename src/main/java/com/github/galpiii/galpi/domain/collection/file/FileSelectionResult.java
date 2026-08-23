package com.github.galpiii.galpi.domain.collection.file;

import com.github.galpiii.galpi.domain.collection.entity.IncompleteReason;
import com.github.galpiii.galpi.domain.collection.pipeline.CollectedRepositorySnapshot.CollectedFile;
import com.github.galpiii.galpi.domain.collection.pipeline.CollectedRepositorySnapshot.ExcludedFile;

import java.util.List;

/**
 * 파일 선별 결과.
 *
 * <p>{@code excludedFiles}는 여기까지만 온전한 목록으로 존재한다. DB에는 개수와 사유 요약만
 * 남는다 — 저장소 하나에서 수천 건이 나오는데, 그 목록이 분석 결과에 더해 주는 것이 없다.
 */
public record FileSelectionResult(List<CollectedFile> collectedFiles,
                                  List<ExcludedFile> excludedFiles,
                                  List<String> fileTree,
                                  List<IncompleteReason> incompleteReasons,
                                  long collectedBytes) {
}
