package com.example.hexagonal.turns.infrastructure.persistence.adapter;

import com.example.hexagonal.turns.domain.port.out.ProcessedEventRepository;
import com.example.hexagonal.turns.infrastructure.persistence.repository.SpringDataProcessedEventRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ProcessedEventJpaAdapter implements ProcessedEventRepository {

    private final SpringDataProcessedEventRepository repository;

    public ProcessedEventJpaAdapter(SpringDataProcessedEventRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(String eventId) {
        return repository.existsById(eventId);
    }

    @Override
    @Transactional
    public boolean markProcessed(String eventId) {
        return repository.insertIfAbsent(eventId) == 1;
    }
}