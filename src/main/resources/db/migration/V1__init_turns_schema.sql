-- Proceso de reserva de un usuario: desde que inicia hasta que queda confirmado, cancelado, vencido, invalido o fallido.
-- Una reserva confirmada es una fila en estado CONFIRMED.
CREATE TABLE reservation_processes (
                                       id UUID PRIMARY KEY,
                                       version BIGINT NOT NULL DEFAULT 0,
                                       user_id BIGINT NOT NULL,
                                       external_patient_id VARCHAR(100) NOT NULL,
                                       professional_id BIGINT NOT NULL,
                                       professional_first_name VARCHAR(100),
                                       professional_last_name VARCHAR(100),
                                       appointment_date DATE NOT NULL,
                                       start_time TIME NOT NULL,
                                       end_time TIME,
                                       patient_first_name VARCHAR(100) NOT NULL,
                                       patient_last_name VARCHAR(100) NOT NULL,
                                       patient_phone VARCHAR(20),
                                       catedra_process_id VARCHAR(100),
                                       hold_id VARCHAR(100),
                                       request_event_id VARCHAR(100),
                                       reservation_id BIGINT,
                                       status VARCHAR(30) NOT NULL,
                                       status_reason VARCHAR(500),
                                       expires_at TIMESTAMP WITH TIME ZONE,
                                       created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                                       updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                                       confirmed_at TIMESTAMP WITH TIME ZONE,
                                       cancelled_at TIMESTAMP WITH TIME ZONE,
                                       CONSTRAINT uk_reservation_processes_catedra_process UNIQUE (catedra_process_id),
                                       CONSTRAINT uk_reservation_processes_reservation UNIQUE (reservation_id),
                                       CONSTRAINT chk_reservation_status CHECK (status IN (
                                                                                           'STARTED', 'HELD', 'WAITING_FOR_PHONE', 'PHONE_REQUESTED', 'PHONE_SUBMITTED', 'PHONE_REJECTED',
                                                                                           'CONFIRMED', 'CANCELLED', 'EXPIRED', 'INVALID', 'FAILED')),
                                       CONSTRAINT chk_reservation_time_window CHECK (end_time IS NULL OR end_time > start_time),
    -- Pasado STARTED, el proceso ya existe en la catedra: tiene sus identificadores
                                       CONSTRAINT chk_reservation_catedra_ids CHECK (
                                           status IN ('STARTED', 'FAILED') OR (catedra_process_id IS NOT NULL AND hold_id IS NOT NULL)),
                                       CONSTRAINT chk_reservation_has_reservation_id CHECK (
                                           status NOT IN ('CONFIRMED', 'CANCELLED') OR reservation_id IS NOT NULL),
                                       CONSTRAINT chk_reservation_submitted_data CHECK (
                                           status <> 'PHONE_SUBMITTED' OR (patient_phone IS NOT NULL AND request_event_id IS NOT NULL))
);

-- Red de seguridad: nunca dos reservas CONFIRMADAS para el mismo turno. Solo CONFIRMED: un hold vencido cuyo evento
-- todavia no se proceso no debe bloquear un nuevo intento; la catedra es la autoridad para los holds.
CREATE UNIQUE INDEX uk_reservation_processes_confirmed_slot
    ON reservation_processes (professional_id, appointment_date, start_time)
    WHERE status = 'CONFIRMED';

-- Consulta de las reservas de un usuario
CREATE INDEX idx_reservation_processes_user ON reservation_processes (user_id, created_at DESC);

-- Busqueda de procesos pendientes para vencerlos o recuperarlos
CREATE INDEX idx_reservation_processes_status_expires ON reservation_processes (status, expires_at);

-- Eventos de Kafka ya procesados (idempotencia por eventId)
CREATE TABLE processed_events (
                                  event_id VARCHAR(200) PRIMARY KEY,
                                  processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);