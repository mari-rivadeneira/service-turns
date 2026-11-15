package com.example.hexagonal.turns.infrastructure.persistence;

import com.example.hexagonal.turns.domain.port.out.ProcessedEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Requiere el compose activo (PostgreSQL). Los eventos de prueba empiezan con "test-" y se borran al terminar. */
@SpringBootTest
class ProcessedEventJpaAdapterIntegrationTest {

    @Autowired
    private ProcessedEventRepository repository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        jdbc.update("delete from processed_events where event_id like 'test-%'");
    }

    @Test
    @DisplayName("Un evento se registra una sola vez")
    void shouldRegisterAnEventOnlyOnce() {
        assertFalse(repository.exists("test-evt-1"));

        assertTrue(repository.markProcessed("test-evt-1"));
        assertTrue(repository.exists("test-evt-1"));

        assertFalse(repository.markProcessed("test-evt-1"));
    }

    @Test
    @DisplayName("Un duplicado dentro de una transaccion no la deja inutilizable")
    void shouldNotBreakTheOuterTransactionWhenTheEventWasAlreadyRegistered() {
        assertTrue(repository.markProcessed("test-evt-tx"));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertFalse(repository.markProcessed("test-evt-tx"));
            assertTrue(repository.exists("test-evt-tx"));
            assertFalse(status.isRollbackOnly());
        });
    }

    @Test
    @DisplayName("Con varios hilos a la vez, el evento se registra exactamente una vez")
    void shouldRegisterExactlyOnceUnderConcurrency() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return repository.markProcessed("test-evt-race");
            }));
        }
        start.countDown();

        int registered = 0;
        for (Future<Boolean> result : results) {
            if (result.get(15, TimeUnit.SECONDS)) {
                registered++;
            }
        }
        pool.shutdown();

        assertEquals(1, registered);
    }
}