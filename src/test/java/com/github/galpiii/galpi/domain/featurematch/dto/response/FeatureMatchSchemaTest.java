package com.github.galpiii.galpi.domain.featurematch.dto.response;

import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureReviewResponse;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverterContextImpl;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureMatchSchemaTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void reviewAndMatchSchemasRemainDistinctInEitherResolutionOrder(boolean reviewFirst) {
        var context = new ModelConverterContextImpl(ModelConverters.getInstance().getConverters());
        if (reviewFirst) {
            context.resolve(new AnnotatedType(FeatureReviewResponse.class));
        }
        context.resolve(new AnnotatedType(FeatureMatchResultsResponse.class));
        context.resolve(new AnnotatedType(FeatureMatchDetailResponse.class));
        context.resolve(new AnnotatedType(FeatureReviewResponse.class));
        var schemas = context.getDefinedModels();
        var results = schemas.get("FeatureMatchResultsResponse");
        var section = schemas.get("FeatureMatchResultSection");
        var feature = schemas.get("FeatureMatchResultFeature");
        assertThat(((Schema<?>) results.getProperties().get("sections")).getItems().get$ref())
                .isEqualTo("#/components/schemas/FeatureMatchResultSection");
        assertThat(((Schema<?>) section.getProperties().get("features")).getItems().get$ref())
                .isEqualTo("#/components/schemas/FeatureMatchResultFeature");
        assertThat(feature.getProperties()).containsKeys("evidenceStatus", "relatedPullRequestCount", "reviewStatus");
        assertThat(((Schema<?>) feature.getProperties().get("evidenceStatus")).getEnum().stream().map(Object::toString).toList())
                .containsExactly("EVIDENCE_FOUND", "NO_EVIDENCE");
        assertThat(((Schema<?>) results.getProperties().get("status")).getEnum().stream().map(Object::toString).toList())
                .contains("COMPLETED", "PARTIALLY_COMPLETED");
        assertThat(schemas.get("Feature").getProperties()).containsKeys("requirements", "issues")
                .doesNotContainKey("evidenceStatus");
        assertThat(schemas.get("FeatureMatchDetailRequirement").getProperties())
                .containsKey("relatedPullRequestCount").doesNotContainKey("sourceText");
        assertThat(schemas.get("Requirement").getProperties()).containsKey("sourceText")
                .doesNotContainKey("relatedPullRequestCount");
    }
}
