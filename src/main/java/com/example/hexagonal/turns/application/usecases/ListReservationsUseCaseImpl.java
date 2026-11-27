package com.example.hexagonal.turns.application.usecases;

import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.model.ReservationStatus;
import com.example.hexagonal.turns.domain.port.in.ListReservationsUseCase;
import com.example.hexagonal.turns.domain.port.out.ReservationProcessRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ListReservationsUseCaseImpl implements ListReservationsUseCase {

    private final ReservationProcessRepository repository;

    public ListReservationsUseCaseImpl(ReservationProcessRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ReservationProcess> list(long userId, ReservationStatus status) {
        // La consulta ya filtra por el usuario y ordena de la mas nueva a la mas vieja
        return repository.findByUserId(userId).stream()
                .filter(process -> status == null || process.getStatus() == status)
                .limit(MAX_RESULTS)
                .toList();
    }
}
