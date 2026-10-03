package com.github.galpiii.galpi.domain.analysis.controller;

import com.github.galpiii.galpi.domain.auth.jwt.JwtTokenProvider;
import com.github.galpiii.galpi.domain.analysis.dto.AnalysisRunCreatedResponse;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunStatus;
import com.github.galpiii.galpi.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import java.util.List;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SelectedAnalysisControllerTest extends WebMvcTestSupport {
    @Autowired private JwtTokenProvider tokens;
    @Test void acceptsExplicitScope() throws Exception {
        given(analysisRunService.create(7L, 3L, List.of(2L)))
                .willReturn(new AnalysisRunCreatedResponse(8L, AnalysisRunStatus.QUEUED, 1, 0));
        mockMvc.perform(post("/projects/3/analyses/selected")
                .header("Authorization", "Bearer " + tokens.createAccessToken(7L))
                .contentType(MediaType.APPLICATION_JSON).content("{\"repositoryIds\":[2]}"))
                .andExpect(status().isAccepted());
        verify(analysisRunService).create(7L, 3L, List.of(2L));
    }
    @Test void rejectsMissingEmptyAndInvalidScopes() throws Exception {
        for (String body : List.of("{}", "{\"repositoryIds\":[]}", "{\"repositoryIds\":[null]}", "{\"repositoryIds\":[0]}")) {
            mockMvc.perform(post("/projects/3/analyses/selected")
                    .header("Authorization", "Bearer " + tokens.createAccessToken(7L))
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }
}
