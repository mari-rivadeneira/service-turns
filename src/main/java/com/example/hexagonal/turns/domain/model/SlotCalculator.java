package com.example.hexagonal.turns.domain.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.TreeMap;

/** Reglas de calculo de los turnos de un dia. Son funciones puras: no consultan nada. */
public final class SlotCalculator {

    private SlotCalculator() {
    }

    /**
     * Todos los turnos posibles del dia segun las agendas habilitadas de ese dia de la semana, ordenados y sin repetir.
     * Solo cuentan los turnos que entran enteros dentro de la agenda. Una duracion no positiva se ignora.
     */
    public static List<AvailableSlot> generateSlots(List<CatalogSchedule> schedules, LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        TreeMap<LocalTime, AvailableSlot> slots = new TreeMap<>();

        for (CatalogSchedule schedule : schedules) {
            if (!schedule.enabled() || schedule.dayOfWeek() != day || schedule.slotDurationMinutes() <= 0
                    || schedule.startTime() == null || schedule.endTime() == null) {
                continue;
            }
            // Se calcula en segundos para no pasar la medianoche ni desbordar LocalTime
            long step = schedule.slotDurationMinutes() * 60L;
            long end = schedule.endTime().toSecondOfDay();
            for (long start = schedule.startTime().toSecondOfDay(); start + step <= end; start += step) {
                LocalTime from = LocalTime.ofSecondOfDay(start);
                slots.putIfAbsent(from, new AvailableSlot(from, LocalTime.ofSecondOfDay(start + step)));
            }
        }
        return List.copyOf(slots.values());
    }

    /**
     * Descuenta los turnos ocupados segun la catedra (confirmados o con hold activo). Un turno esta ocupado si alguna
     * ocupacion de esa fecha empieza dentro de el: ante la duda, el turno no se ofrece.
     */
    public static List<AvailableSlot> removeOccupied(List<AvailableSlot> slots, LocalDate date,
                                                     List<SlotOccupancy> occupancies) {
        return slots.stream()
                .filter(slot -> occupancies.stream().noneMatch(occupancy -> occupies(occupancy, date, slot)))
                .toList();
    }

    /** Descarta los turnos que ya empezaron (se usa cuando la fecha consultada es hoy). */
    public static List<AvailableSlot> removeStarted(List<AvailableSlot> slots, LocalTime now) {
        return slots.stream().filter(slot -> slot.startTime().isAfter(now)).toList();
    }

    private static boolean occupies(SlotOccupancy occupancy, LocalDate date, AvailableSlot slot) {
        return occupancy.date() != null
                && occupancy.startTime() != null
                && occupancy.date().equals(date)
                && !occupancy.startTime().isBefore(slot.startTime())
                && occupancy.startTime().isBefore(slot.endTime());
    }
}