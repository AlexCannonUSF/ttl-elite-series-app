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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ParallelModelLaneServiceTests {

    @Test
    void marketAnchorIsSwapInvariantAndStaysBetweenMarketAndModel() {
        double weight = ParallelModelLaneService.adaptiveModelWeight(0.21, 0.08, 0.50);
        double player1 = ParallelModelLaneService.anchoredProbability(0.62, 0.70, weight);
        double swappedPlayer2 = ParallelModelLaneService.anchoredProbability(0.38, 0.30, weight);

        assertThat(player1).isBetween(0.62, 0.70);
        assertThat(player1 + swappedPlayer2).isCloseTo(1.0, within(1.0e-12));
    }

    @Test
    void marketAnchorTrustsModelLessAsDisagreementGrows() {
        double closeWeight = ParallelModelLaneService.adaptiveModelWeight(0.21, 0.02, 0.50);
        double wideWeight = ParallelModelLaneService.adaptiveModelWeight(0.21, 0.18, 0.50);

        assertThat(closeWeight).isGreaterThan(wideWeight);
        assertThat(wideWeight).isLessThan(0.15);
    }

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
