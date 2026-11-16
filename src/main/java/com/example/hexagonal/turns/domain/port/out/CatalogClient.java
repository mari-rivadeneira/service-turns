package com.example.hexagonal.turns.domain.port.out;

import com.example.hexagonal.turns.domain.model.CatalogProfessional;
import com.example.hexagonal.turns.domain.model.CatalogSchedule;

import java.util.List;
import java.util.Optional;

/**
 * API de service-catalog. Se reenvia el JWT del usuario que origino la operacion (accessToken), para conservar su
 * identidad. Puede lanzar CatalogRejectedException (por ejemplo, token invalido) y CatalogUnavailableException
 * (servicio caido): en ese caso no se pueden iniciar operaciones que requieran validar informacion vigente.
 */
public interface CatalogClient {

    /** @return el profesional, o vacio si no existe */
    Optional<CatalogProfessional> findProfessional(long professionalId, String accessToken);

    /** @return las agendas del profesional (puede ser una lista vacia), o vacio si el profesional no existe */
    Optional<List<CatalogSchedule>> findSchedules(long professionalId, String accessToken);
}
