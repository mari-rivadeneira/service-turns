package com.example.hexagonal.turns.infrastructure.client.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Cuerpo application/problem+json de los errores: solo interesan el code y el detail. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProblemResponse(String code, String detail) {
}