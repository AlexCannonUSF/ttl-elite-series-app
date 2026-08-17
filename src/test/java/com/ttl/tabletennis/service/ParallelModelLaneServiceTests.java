package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.DecisionOpportunity;
import com.ttl.tabletennis.domain.PaperTradeModelCall;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.repository.RunModelLaneDefinitionRepository;
import com.ttl.tabletennis.repository.RunModelLaneEvaluationRepository;
import com.ttl.tabletennis.repository.RunPortfolioDecisionRepository;
import com.ttl.tabletennis.repository.RunPortfolioDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ParallelModelLaneServiceTests {

    @Test
    void disabledShadowEngineCannotTouchProductionOrResearchRepositories() {
        PredictionFacade prediction = mock(PredictionFacade.class);
        ModelArtifactIdentityService identity = mock(ModelArtifactIdentityService.class);
        RunModelLaneDefinitionRepository lanes = mock(RunModelLaneDefinitionRepository.class);
        RunModelLaneEvaluationRepository evaluations = mock(RunModelLaneEvaluationRepository.class);
        RunPortfolioDefinitionRepository portfolios = mock(RunPortfolioDefinitionRepository.class);
        RunPortfolioDecisionRepository decisions = mock(RunPortfolioDecisionRepository.class);
        ParallelModelLaneService service = new ParallelModelLaneService(
                prediction, identity, lanes, evaluations, portfolios, decisions);
        ReflectionTestUtils.setField(service, "enabled", false);

        service.captureShadows(new PaperTradeSession(), new DecisionOpportunity(), new PaperTradeModelCall());

        verifyNoInteractions(prediction, identity, lanes, evaluations, portfolios, decisions);
    }
}
