package com.example.hexagonal.turns.domain.port.out;

import com.example.hexagonal.turns.domain.model.AppointmentQuery;
import com.example.hexagonal.turns.domain.model.CatedraAppointmentPage;
import com.example.hexagonal.turns.domain.model.CatedraCancellation;
import com.example.hexagonal.turns.domain.model.CatedraHold;
import com.example.hexagonal.turns.domain.model.CatedraHoldConfirmation;
import com.example.hexagonal.turns.domain.model.SlotOccupancy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * API REST de la catedra, con el JWT tecnico del proyecto. Todas las operaciones pueden lanzar:
 * CatedraRejectedException (rechazo firme: la operacion no tuvo efecto) y CatedraUnavailableException (no se pudo
 * completar la llamada: en las operaciones con efectos, outcomeUnknown indica si pudo haberse ejecutado). El cliente no
 * reintenta: antes de repetir una operacion con efectos hay que reconciliar su estado.
 */
public interface CatedraAppointmentClient {

    /** Crea un hold temporal sobre un turno. Tiene efectos. */
    CatedraHold createHold(long professionalId, LocalDate date, LocalTime startTime, String externalPatientId);

    /** Confirmacion inicial del hold: la catedra pasa a pedir el telefono. Tiene efectos. */
    CatedraHoldConfirmation confirmHold(String holdId, String externalPatientId,
                                        String patientFirstName, String patientLastName);

    /** Turnos no disponibles del profesional en el rango (inclusivo). Una lista vacia es valida. */
    List<SlotOccupancy> getOccupancies(long professionalId, LocalDate from, LocalDate to);

    /** Reservas de toda la cuenta tecnica; el backend debe aplicar su propia asociacion con el usuario. */
    CatedraAppointmentPage listAppointments(AppointmentQuery query);

    /** Cancela una reserva confirmada. Es idempotente en la catedra. Tiene efectos. */
    CatedraCancellation cancelAppointment(String reservationProcessId, String reason);
}
