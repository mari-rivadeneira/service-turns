package com.example.hexagonal.turns.application.usecases;

import com.example.hexagonal.turns.domain.port.out.ProcessedEventRepository;

import java.util.LinkedHashSet;
import java.util.Set;

public class InMemoryProcessedEventRepository implements ProcessedEventRepository {

    private final Set<String> events = new LinkedHashSet<>();

    @Override
    public boolean exists(String eventId) {
        return events.contains(eventId);
    }

    @Override
    public boolean markProcessed(String eventId) {
        return events.add(eventId);
    }
}