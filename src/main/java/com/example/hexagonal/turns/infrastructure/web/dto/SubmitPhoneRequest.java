package com.example.hexagonal.turns.infrastructure.web.dto;

/** El campo es opcional a proposito: el caso de uso valida y devuelve el detalle por campo. */
public record SubmitPhoneRequest(String phoneNumber) {
}