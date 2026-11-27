package com.example.hexagonal.turns.application.usecases;

import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.model.ReservationStatus;
import com.example.hexagonal.turns.domain.port.in.ListReservationsUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListReservationsUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-20T15:00:00Z");

    private final InMemoryReservationProcessRepository repository = new InMemoryReservationProcessRepository();
    private final ListReservationsUseCaseImpl useCase = new ListReservationsUseCaseImpl(repository);

    private ReservationProcess processOf(long userId, int minutesAgo, ReservationStatus status) {
        ReservationProcess created = repository.create(ReservationProcess.start(userId, "user-" + userId, 15L, "Ana",
                "Gomez", LocalDate.of(2026, 12, 7), LocalTime.of(9, 0), LocalTime.of(9, 30), "Juan", "Perez",
                NOW.minusSeconds(minutesAgo * 60L)));
        repository.modify(created.getId(), p -> p.setStatus(status));
        return repository.get(created.getId());
    }

    @Test
    @DisplayName("Devuelve solo las reservas del usuario, de la mas nueva a la mas vieja")
    void shouldReturnOnlyTheUsersReservationsNewestFirst() {
        ReservationProcess oldest = processOf(42L, 30, ReservationStatus.FAILED);
        ReservationProcess newest = processOf(42L, 5, ReservationStatus.CONFIRMED);
        ReservationProcess middle = processOf(42L, 15, ReservationStatus.HELD);
        processOf(43L, 1, ReservationStatus.CONFIRMED);

        List<ReservationProcess> result = useCase.list(42L, null);

        assertEquals(List.of(newest.getId(), middle.getId(), oldest.getId()),
                result.stream().map(ReservationProcess::getId).toList());
    }

    @Test
    @DisplayName("Filtra por estado cuando se pide")
    void shouldFilterByStatus() {
        processOf(42L, 30, ReservationStatus.FAILED);
        ReservationProcess confirmed = processOf(42L, 5, ReservationStatus.CONFIRMED);

        List<ReservationProcess> result = useCase.list(42L, ReservationStatus.CONFIRMED);

        assertEquals(List.of(confirmed.getId()), result.stream().map(ReservationProcess::getId).toList());
    }

    @Test
    @DisplayName("Un usuario sin reservas recibe una lista vacia")
    void shouldReturnAnEmptyListWhenThereAreNone() {
        processOf(43L, 5, ReservationStatus.CONFIRMED);

        assertTrue(useCase.list(42L, null).isEmpty());
    }

    @Test
    @DisplayName("Devuelve como maximo el tope de resultados, conservando las mas nuevas")
    void shouldCapTheResults() {
        for (int i = 0; i < ListReservationsUseCase.MAX_RESULTS + 5; i++) {
            processOf(42L, i + 1, ReservationStatus.FAILED);
        }

        List<ReservationProcess> result = useCase.list(42L, null);

        assertEquals(ListReservationsUseCase.MAX_RESULTS, result.size());
        assertEquals(NOW.minusSeconds(60), result.get(0).getCreatedAt());
    }
}