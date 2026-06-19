package io.emcip.knowledge.engine.controller;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.emcip.knowledge.engine.repository.GraphRepository;
import io.emcip.knowledge.engine.service.KnowledgeQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
class KnowledgeSearchControllerTest {

    @Mock private KnowledgeQueryService queryService;
    @Mock private GraphRepository graphRepository;

    @Test
    void search_blankQuery_returns400() throws Exception {
        KnowledgeSearchController controller =
                new KnowledgeSearchController(queryService, graphRepository);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        MockMvc mockMvc =
                MockMvcBuilders.standaloneSetup(controller).setValidator(validator).build();

        mockMvc.perform(
                        post("/api/knowledge/search")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"query":"","searchType":"HYBRID","limit":20}
                                        """))
                .andExpect(status().isBadRequest());

        verify(queryService, never()).search(org.mockito.ArgumentMatchers.any());
    }
}
