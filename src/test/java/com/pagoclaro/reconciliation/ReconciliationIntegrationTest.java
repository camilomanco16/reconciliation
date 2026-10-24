package com.pagoclaro.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Pruebas de integración del endpoint de conciliación sobre H2 con los datos de data.sql.
 * Antes de cada prueba se vacía la base y se recarga data.sql, así que ninguna prueba
 * depende de los cambios (ya confirmados) de otra. Usa su propia base en memoria para no
 * chocar con el contexto de otras clases de prueba.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:reconciliation-it;MODE=Oracle;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Sql(scripts = { "classpath:reset.sql", "classpath:data.sql" }, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ReconciliationIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	// --- Casos de muestra según la tabla de decisión ---

	@Test
	void payAx91_pendienteConAprobacionYDebito_seConfirma() throws Exception {
		reconcile("PAY-AX91")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.externalRef").value("PAY-AX91"))
				.andExpect(jsonPath("$.result").value("CORREGIDO"));

		assertThat(paymentStatus("PAY-AX91")).isEqualTo("CONFIRMED");
		assertThat(auditCount("PAY-AX91", "STATUS_CONFIRMED")).isEqualTo(1);
		assertThat(ledgerCount("PAY-AX91")).isEqualTo(1);
	}

	@Test
	void payBx17_debitoDuplicado_seRevierte() throws Exception {
		reconcile("PAY-BX17")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result").value("CORREGIDO"));

		assertThat(ledgerCount("PAY-BX17", "REVERSAL")).isEqualTo(1);
		assertThat(netDebit("PAY-BX17")).isEqualByComparingTo(paymentAmount("PAY-BX17"));
		assertThat(auditCount("PAY-BX17", "DUPLICATE_DEBIT_REVERSED")).isEqualTo(1);
		assertThat(paymentStatus("PAY-BX17")).isEqualTo("CONFIRMED");
	}

	@Test
	void payCq22_eventosContradictorios_vaARevisionManual() throws Exception {
		reconcile("PAY-CQ22")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result").value("REVISION_MANUAL"));

		assertThat(paymentStatus("PAY-CQ22")).isEqualTo("PENDING");
		assertThat(ledgerCount("PAY-CQ22")).isZero();
		assertThat(auditCount("PAY-CQ22", "MANUAL_REVIEW")).isEqualTo(1);
	}

	@Test
	void payDk44_casoSano_esConsistenteYNoEscribe() throws Exception {
		reconcile("PAY-DK44")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result").value("CONSISTENTE"));

		assertThat(paymentStatus("PAY-DK44")).isEqualTo("CONFIRMED");
		assertThat(ledgerCount("PAY-DK44")).isEqualTo(1);
		assertThat(auditCount("PAY-DK44")).isZero();
	}

	// --- Idempotencia y auditoría ---

	@Test
	void payBx17_segundaEjecucion_noCambiaLedgerNiAuditoria() throws Exception {
		reconcile("PAY-BX17").andExpect(jsonPath("$.result").value("CORREGIDO"));
		int ledgerAfterFirst = ledgerCount("PAY-BX17");
		int auditAfterFirst = auditCount("PAY-BX17");

		reconcile("PAY-BX17")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result").value("CONSISTENTE"));

		assertThat(ledgerCount("PAY-BX17")).isEqualTo(ledgerAfterFirst);
		assertThat(auditCount("PAY-BX17")).isEqualTo(auditAfterFirst);
		assertThat(netDebit("PAY-BX17")).isEqualByComparingTo(paymentAmount("PAY-BX17"));
	}

	@Test
	void payCq22_dosEjecuciones_unSoloRegistroDeRevisionManual() throws Exception {
		reconcile("PAY-CQ22").andExpect(jsonPath("$.result").value("REVISION_MANUAL"));
		reconcile("PAY-CQ22").andExpect(jsonPath("$.result").value("REVISION_MANUAL"));

		assertThat(auditCount("PAY-CQ22", "MANUAL_REVIEW")).isEqualTo(1);
		assertThat(auditCount("PAY-CQ22")).isEqualTo(1);
	}

	@Test
	void referenciaInexistente_responde404YNoEscribeAuditoria() throws Exception {
		int auditBefore = totalAuditCount();

		reconcile("PAY-NO-EXISTE")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.externalRef").value("PAY-NO-EXISTE"))
				.andExpect(jsonPath("$.result").value("NO_ENCONTRADO"));

		assertThat(totalAuditCount()).isEqualTo(auditBefore);
	}

	@Test
	void payBx17_reversalApuntaAlDebitoMasRecienteSinReverso() throws Exception {
		Long expectedDebitId = jdbc.queryForObject("""
				SELECT l.id FROM LEDGER_ENTRY l JOIN PAYMENT p ON p.id = l.payment_id
				WHERE p.external_ref = ? AND l.entry_type = 'DEBIT'
				ORDER BY l.created_at DESC, l.id DESC FETCH FIRST 1 ROWS ONLY
				""", Long.class, "PAY-BX17");

		reconcile("PAY-BX17").andExpect(jsonPath("$.result").value("CORREGIDO"));

		Long relatedEntryId = jdbc.queryForObject("""
				SELECT l.related_entry_id FROM LEDGER_ENTRY l JOIN PAYMENT p ON p.id = l.payment_id
				WHERE p.external_ref = ? AND l.entry_type = 'REVERSAL'
				""", Long.class, "PAY-BX17");
		assertThat(relatedEntryId).isEqualTo(expectedDebitId);
	}

	// --- Utilidades ---

	private ResultActions reconcile(String externalRef) throws Exception {
		return mockMvc.perform(post("/support/payments/{externalRef}/reconcile", externalRef));
	}

	private String paymentStatus(String externalRef) {
		return jdbc.queryForObject("SELECT status FROM PAYMENT WHERE external_ref = ?", String.class, externalRef);
	}

	private BigDecimal paymentAmount(String externalRef) {
		return jdbc.queryForObject("SELECT amount FROM PAYMENT WHERE external_ref = ?", BigDecimal.class,
				externalRef);
	}

	private BigDecimal netDebit(String externalRef) {
		return jdbc.queryForObject("""
				SELECT COALESCE(SUM(CASE l.entry_type WHEN 'DEBIT' THEN l.amount ELSE -l.amount END), 0)
				FROM LEDGER_ENTRY l JOIN PAYMENT p ON p.id = l.payment_id
				WHERE p.external_ref = ?
				""", BigDecimal.class, externalRef);
	}

	private int ledgerCount(String externalRef) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM LEDGER_ENTRY l JOIN PAYMENT p ON p.id = l.payment_id
				WHERE p.external_ref = ?
				""", Integer.class, externalRef);
	}

	private int ledgerCount(String externalRef, String entryType) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM LEDGER_ENTRY l JOIN PAYMENT p ON p.id = l.payment_id
				WHERE p.external_ref = ? AND l.entry_type = ?
				""", Integer.class, externalRef, entryType);
	}

	private int auditCount(String externalRef) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM SUPPORT_AUDIT a JOIN PAYMENT p ON p.id = a.payment_id
				WHERE p.external_ref = ?
				""", Integer.class, externalRef);
	}

	private int auditCount(String externalRef, String action) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM SUPPORT_AUDIT a JOIN PAYMENT p ON p.id = a.payment_id
				WHERE p.external_ref = ? AND a.action = ?
				""", Integer.class, externalRef, action);
	}

	private int totalAuditCount() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM SUPPORT_AUDIT", Integer.class);
	}
}
