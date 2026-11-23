package com.example.hexagonal.turns.application.usecases;

import com.example.hexagonal.turns.domain.exception.ConcurrentUpdateException;
import com.example.hexagonal.turns.domain.model.AppointmentActionEvent;
import com.example.hexagonal.turns.domain.port.in.ApplyAppointmentActionUseCase;
import com.example.hexagonal.turns.domain.port.in.ApplyAppointmentActionUseCase.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessAppointmentActionUseCaseTest {

    private static final AppointmentActionEvent EVENT = new AppointmentActionEvent("event-1",
            AppointmentActionEvent.Type.REQUESTED, "process-1", "hold-1", null, null, null, null);

    @Mock
    private ApplyAppointmentActionUseCase applyUseCase;

    private ProcessAppointmentActionUseCaseImpl useCase() {
        return new ProcessAppointmentActionUseCaseImpl(applyUseCase);
    }

    private ConcurrentUpdateException conflict() {
        return new ConcurrentUpdateException(UUID.randomUUID(), null);
    }

    @Test
    @DisplayName("Devuelve el resultado de aplicar el evento")
    void shouldReturnTheResult() {
        when(applyUseCase.apply(EVENT)).thenReturn(Result.APPLIED);

        assertEquals(Result.APPLIED, useCase().process(EVENT));

        verify(applyUseCase, times(1)).apply(EVENT);
    }

    @Test
    @DisplayName("Un conflicto de version se reintenta (cada intento es una transaccion nueva) y se recupera")
    void shouldRetryAVersionConflict() {
        when(applyUseCase.apply(EVENT)).thenThrow(conflict()).thenThrow(conflict()).thenReturn(Result.APPLIED);

        assertEquals(Result.APPLIED, useCase().process(EVENT));

        verify(applyUseCase, times(3)).apply(EVENT);
    }

    @Test
    @DisplayName("Los reintentos por conflicto tienen tope: despues se propaga el error")
    void shouldGiveUpAfterTheMaximumConflicts() {
        when(applyUseCase.apply(EVENT)).thenThrow(conflict());

        assertThrows(ConcurrentUpdateException.class, () -> useCase().process(EVENT));

        verify(applyUseCase, times(3)).apply(EVENT);
    }

    @Test
    @DisplayName("Otros errores no se reintentan aca: los maneja el reintento de Kafka")
    void shouldNotRetryOtherErrors() {
        when(applyUseCase.apply(EVENT)).thenThrow(new IllegalStateException("base de datos caida"));

        assertThrows(IllegalStateException.class, () -> useCase().process(EVENT));

        verify(applyUseCase, times(1)).apply(EVENT);
    }
}