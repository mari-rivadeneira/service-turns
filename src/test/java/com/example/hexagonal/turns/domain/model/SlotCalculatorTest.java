package com.example.hexagonal.turns.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotCalculatorTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 12, 7);

    private CatalogSchedule schedule(DayOfWeek day, String start, String end, int minutes, boolean enabled) {
        return new CatalogSchedule(21L, 15L, day, LocalTime.parse(start), LocalTime.parse(end), minutes, enabled);
    }

    private AvailableSlot slot(String start, String end) {
        return new AvailableSlot(LocalTime.parse(start), LocalTime.parse(end));
    }

    private SlotOccupancy occupancy(LocalDate date, String start, SlotOccupancy.Status status) {
        return new SlotOccupancy(date, LocalTime.parse(start), status, null);
    }

    private List<LocalTime> starts(List<AvailableSlot> slots) {
        return slots.stream().map(AvailableSlot::startTime).toList();
    }

    // ---------- generacion ----------

    @Test
    @DisplayName("Parte la agenda en turnos de la duracion indicada")
    void shouldSplitScheduleIntoSlots() {
        List<AvailableSlot> slots = SlotCalculator.generateSlots(
                List.of(schedule(DayOfWeek.MONDAY, "09:00", "12:00", 30, true)), MONDAY);

        assertEquals(6, slots.size());
        assertEquals(slot("09:00", "09:30"), slots.get(0));
        assertEquals(slot("11:30", "12:00"), slots.get(5));
    }

    @Test
    @DisplayName("Un turno que no entra entero en la agenda se descarta")
    void shouldDropSlotsThatDoNotFit() {
        List<AvailableSlot> slots = SlotCalculator.generateSlots(
                List.of(schedule(DayOfWeek.MONDAY, "09:00", "10:10", 30, true)), MONDAY);

        assertEquals(List.of(slot("09:00", "09:30"), slot("09:30", "10:00")), slots);
    }

    @Test
    @DisplayName("Varias agendas del mismo dia se unen y quedan ordenadas")
    void shouldUnionSchedulesOfTheSameDay() {
        List<AvailableSlot> slots = SlotCalculator.generateSlots(List.of(
                schedule(DayOfWeek.MONDAY, "14:00", "15:00", 20, true),
                schedule(DayOfWeek.MONDAY, "09:00", "10:00", 30, true)), MONDAY);

        assertEquals(List.of(LocalTime.of(9, 0), LocalTime.of(9, 30), LocalTime.of(14, 0),
                LocalTime.of(14, 20), LocalTime.of(14, 40)), starts(slots));
    }

    @Test
    @DisplayName("Se ignoran las agendas deshabilitadas y las de otros dias")
    void shouldIgnoreDisabledAndOtherDaySchedules() {
        List<AvailableSlot> slots = SlotCalculator.generateSlots(List.of(
                schedule(DayOfWeek.MONDAY, "09:00", "10:00", 30, false),
                schedule(DayOfWeek.TUESDAY, "09:00", "10:00", 30, true)), MONDAY);

        assertTrue(slots.isEmpty());
    }

    @Test
    @DisplayName("Dos agendas que se superponen no repiten turnos")
    void shouldNotRepeatSlotsOfOverlappingSchedules() {
        List<AvailableSlot> slots = SlotCalculator.generateSlots(List.of(
                schedule(DayOfWeek.MONDAY, "09:00", "10:00", 30, true),
                schedule(DayOfWeek.MONDAY, "09:00", "10:00", 30, true)), MONDAY);

        assertEquals(2, slots.size());
    }

    @Test
    @DisplayName("Una duracion cero o negativa se ignora: no se cuelga")
    void shouldIgnoreNonPositiveDuration() {
        List<AvailableSlot> slots = SlotCalculator.generateSlots(List.of(
                schedule(DayOfWeek.MONDAY, "09:00", "10:00", 0, true),
                schedule(DayOfWeek.MONDAY, "09:00", "10:00", -5, true)), MONDAY);

        assertTrue(slots.isEmpty());
    }

    @Test
    @DisplayName("Cerca de la medianoche no se pasa al dia siguiente")
    void shouldNotOverflowPastMidnight() {
        CatalogSchedule late = new CatalogSchedule(21L, 15L, DayOfWeek.MONDAY, LocalTime.of(23, 0),
                LocalTime.of(23, 59, 59), 30, true);

        List<AvailableSlot> slots = SlotCalculator.generateSlots(List.of(late), MONDAY);

        assertEquals(List.of(slot("23:00", "23:30")), slots);
    }

    // ---------- ocupaciones ----------

    @Test
    @DisplayName("Descuenta los turnos ocupados, tanto confirmados como con hold")
    void shouldRemoveConfirmedAndHeldSlots() {
        List<AvailableSlot> slots = List.of(slot("09:00", "09:30"), slot("09:30", "10:00"), slot("10:00", "10:30"));

        List<AvailableSlot> free = SlotCalculator.removeOccupied(slots, MONDAY, List.of(
                occupancy(MONDAY, "09:00", SlotOccupancy.Status.CONFIRMED),
                occupancy(MONDAY, "10:00", SlotOccupancy.Status.HELD)));

        assertEquals(List.of(slot("09:30", "10:00")), free);
    }

    @Test
    @DisplayName("Una ocupacion que empieza dentro del turno lo bloquea; la que empieza donde termina, no")
    void shouldBlockSlotWhenOccupancyStartsInsideIt() {
        List<AvailableSlot> slots = List.of(slot("09:00", "09:30"), slot("09:30", "10:00"));

        List<AvailableSlot> free = SlotCalculator.removeOccupied(slots, MONDAY,
                List.of(occupancy(MONDAY, "09:15", SlotOccupancy.Status.HELD)));

        // 09:15 cae dentro de 09:00-09:30; 09:30-10:00 queda libre
        assertEquals(List.of(slot("09:30", "10:00")), free);
    }

    @Test
    @DisplayName("Las ocupaciones de otra fecha no afectan")
    void shouldIgnoreOccupanciesOfOtherDates() {
        List<AvailableSlot> slots = List.of(slot("09:00", "09:30"));

        List<AvailableSlot> free = SlotCalculator.removeOccupied(slots, MONDAY,
                List.of(occupancy(MONDAY.plusDays(1), "09:00", SlotOccupancy.Status.CONFIRMED)));

        assertEquals(slots, free);
    }

    // ---------- turnos ya empezados ----------

    @Test
    @DisplayName("Los turnos que ya empezaron se descartan, incluido el que empieza justo ahora")
    void shouldRemoveStartedSlots() {
        List<AvailableSlot> slots = List.of(slot("10:00", "10:30"), slot("10:15", "10:45"), slot("10:30", "11:00"));

        List<AvailableSlot> upcoming = SlotCalculator.removeStarted(slots, LocalTime.of(10, 15));

        assertEquals(List.of(slot("10:30", "11:00")), upcoming);
    }
}
