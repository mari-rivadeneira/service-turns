package com.example.hexagonal.turns.application.usecases;

import com.example.hexagonal.turns.application.exception.ReservationNotFoundException;
import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.port.in.GetReservationUseCase;
import com.example.hexagonal.turns.domain.port.out.ReservationProcessRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class GetReservationUseCaseImpl implements GetReservationUseCase {

    private final ReservationProcessRepository repository;

    public GetReservationUseCaseImpl(ReservationProcessRepository repository) {
        this.repository = repository;
    }

    @Override
    public ReservationProcess get(long userId, UUID reservationId) {
        return repository.findByIdAndUserId(reservationId, userId).orElseThrow(ReservationNotFoundException::new);
    }
}
