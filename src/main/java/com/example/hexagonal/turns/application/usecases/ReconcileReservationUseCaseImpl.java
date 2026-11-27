package com.example.hexagonal.turns.application.usecases;

import com.example.hexagonal.turns.application.exception.ReservationNotFoundException;
import com.example.hexagonal.turns.application.service.ReservationReconciler;
import com.example.hexagonal.turns.domain.model.ReservationProcess;
import com.example.hexagonal.turns.domain.port.in.ReconcileReservationUseCase;
import com.example.hexagonal.turns.domain.port.out.ReservationProcessRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ReconcileReservationUseCaseImpl implements ReconcileReservationUseCase {

    private final ReservationProcessRepository repository;
    private final ReservationReconciler reconciler;

    public ReconcileReservationUseCaseImpl(ReservationProcessRepository repository, ReservationReconciler reconciler) {
        this.repository = repository;
        this.reconciler = reconciler;
    }

    @Override
    public ReservationProcess reconcile(long userId, UUID reservationId) {
        ReservationProcess process = repository.findByIdAndUserId(reservationId, userId)
                .orElseThrow(ReservationNotFoundException::new);
        return reconciler.reconcile(process);
    }
}