package com.example.hexagonal.turns.domain.model;

/** Profesional vigente segun service-catalog. */
public record CatalogProfessional(long id, long categoryId, String firstName, String lastName, boolean enabled) {
}