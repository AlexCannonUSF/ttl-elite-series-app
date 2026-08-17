package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.ExperimentCollection;
import com.ttl.tabletennis.domain.ExperimentRunLink;
import com.ttl.tabletennis.dto.ExperimentCollectionDto;
import com.ttl.tabletennis.dto.ExperimentCollectionRequest;
import com.ttl.tabletennis.dto.ExperimentRunLinkRequest;
import com.ttl.tabletennis.exception.ResourceNotFoundException;
import com.ttl.tabletennis.repository.ExperimentCollectionRepository;
import com.ttl.tabletennis.repository.ExperimentRunLinkRepository;
import com.ttl.tabletennis.repository.PaperTradeSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class ExperimentCollectionService {
    private final ExperimentCollectionRepository collectionRepository;
    private final ExperimentRunLinkRepository linkRepository;
    private final PaperTradeSessionRepository sessionRepository;

    public ExperimentCollectionService(ExperimentCollectionRepository collectionRepository,
                                       ExperimentRunLinkRepository linkRepository,
                                       PaperTradeSessionRepository sessionRepository) {
        this.collectionRepository = collectionRepository;
        this.linkRepository = linkRepository;
        this.sessionRepository = sessionRepository;
    }

    @Transactional(readOnly = true)
    public List<ExperimentCollectionDto> all() {
        return collectionRepository.findAllByOrderByUpdatedAtDesc().stream().map(this::dto).toList();
    }

    @Transactional
    public ExperimentCollectionDto create(ExperimentCollectionRequest request) {
        ExperimentCollection collection = new ExperimentCollection();
        collection.setName(request.name().trim());
        collection.setDescription(trimToNull(request.description()));
        collection.setHypothesis(trimToNull(request.hypothesis()));
        collection.setStatus("OPEN");
        collection.setCreatedBy(StringUtils.hasText(request.createdBy()) ? request.createdBy().trim() : "OPERATOR");
        return dto(collectionRepository.save(collection));
    }

    @Transactional
    public ExperimentCollectionDto link(long experimentId, ExperimentRunLinkRequest request) {
        ExperimentCollection collection = requireCollection(experimentId);
        if (!sessionRepository.existsById(request.runId())) {
            throw new ResourceNotFoundException("Run " + request.runId() + " was not found");
        }
        ExperimentRunLink link = linkRepository.findByExperimentIdAndSessionId(experimentId, request.runId())
                .orElseGet(ExperimentRunLink::new);
        link.setExperimentId(experimentId);
        link.setSessionId(request.runId());
        link.setRole(StringUtils.hasText(request.role()) ? request.role().trim().toUpperCase(Locale.ROOT) : "CANDIDATE");
        link.setNote(trimToNull(request.note()));
        linkRepository.save(link);
        collection.setUpdatedAt(LocalDateTime.now());
        collectionRepository.save(collection);
        return dto(collection);
    }

    private ExperimentCollectionDto dto(ExperimentCollection row) {
        List<ExperimentCollectionDto.RunLink> links = linkRepository.findByExperimentIdOrderByLinkedAtAsc(row.getId())
                .stream().map(link -> new ExperimentCollectionDto.RunLink(link.getId(), link.getSessionId(),
                        link.getRole(), link.getNote(), link.getLinkedAt())).toList();
        return new ExperimentCollectionDto(row.getId(), row.getName(), row.getDescription(), row.getHypothesis(),
                row.getStatus(), row.getCreatedBy(), row.getCreatedAt(), row.getUpdatedAt(), links);
    }

    private ExperimentCollection requireCollection(long id) {
        return collectionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Experiment " + id + " was not found"));
    }

    private static String trimToNull(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
}
