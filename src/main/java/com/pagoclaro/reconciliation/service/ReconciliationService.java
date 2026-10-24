package com.pagoclaro.reconciliation.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pagoclaro.reconciliation.domain.LedgerEntry;
import com.pagoclaro.reconciliation.domain.LedgerEntryType;
import com.pagoclaro.reconciliation.domain.Payment;
import com.pagoclaro.reconciliation.domain.PaymentStatus;
import com.pagoclaro.reconciliation.domain.ProviderEvent;
import com.pagoclaro.reconciliation.domain.ProviderStatus;
import com.pagoclaro.reconciliation.domain.SupportAudit;
import com.pagoclaro.reconciliation.repository.LedgerEntryRepository;
import com.pagoclaro.reconciliation.repository.PaymentRepository;
import com.pagoclaro.reconciliation.repository.ProviderEventRepository;
import com.pagoclaro.reconciliation.repository.SupportAuditRepository;
import com.pagoclaro.reconciliation.service.ReconciliationResult.Outcome;

/**
 * Aplica la tabla de decisión ("tabla de desiciones .xlsx") a un pago.
 * Las reglas dependen solo de los datos del pago, nunca de su referencia.
 */
@Service
public class ReconciliationService {

	private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

	static final String ACTION_CONFIRM = "STATUS_CONFIRMED";
	static final String ACTION_REJECT = "STATUS_REJECTED";
	static final String ACTION_REVERSAL = "DUPLICATE_DEBIT_REVERSED";
	static final String ACTION_MANUAL_REVIEW = "MANUAL_REVIEW";

	private final PaymentRepository payments;
	private final ProviderEventRepository events;
	private final LedgerEntryRepository ledger;
	private final SupportAuditRepository audits;

	public ReconciliationService(PaymentRepository payments, ProviderEventRepository events,
			LedgerEntryRepository ledger, SupportAuditRepository audits) {
		this.payments = payments;
		this.events = events;
		this.ledger = ledger;
		this.audits = audits;
	}

	@Transactional
	public ReconciliationResult reconcile(String externalRef) {
		Optional<Payment> found = payments.findByExternalRefForUpdate(externalRef);
		if (found.isEmpty()) {
			log.info("reconcile ref={} decision={}", sanitize(externalRef), Outcome.NO_ENCONTRADO);
			return new ReconciliationResult(externalRef, Outcome.NO_ENCONTRADO, "Pago no encontrado");
		}
		Payment payment = found.get();

		List<ProviderEvent> paymentEvents = events.findByPaymentIdOrderByReceivedAtAsc(payment.getId());
		List<LedgerEntry> entries = ledger.findByPaymentIdOrderByCreatedAtAsc(payment.getId());

		long approved = count(paymentEvents, ProviderStatus.APPROVED);
		long declined = count(paymentEvents, ProviderStatus.DECLINED);
		BigDecimal netDebit = netDebit(entries);

		ReconciliationResult result = decide(payment, entries, approved, declined, netDebit);
		log.info("reconcile ref={} decision={} reason=\"{}\"", sanitize(externalRef), result.result(),
				result.reason());
		return result;
	}

	private ReconciliationResult decide(Payment payment, List<LedgerEntry> entries, long approved, long declined,
			BigDecimal netDebit) {
		PaymentStatus status = payment.getStatus();
		BigDecimal amount = payment.getAmount();
		boolean onlyApproved = approved > 0 && declined == 0;
		boolean onlyDeclined = declined > 0 && approved == 0;
		boolean noEvents = approved == 0 && declined == 0;
		boolean netIsZero = netDebit.signum() == 0;

		// Regla 4: APPROVED y DECLINED, cualquier débito y estado.
		if (approved > 0 && declined > 0) {
			return manualReview(payment, "Eventos APPROVED y DECLINED contradictorios");
		}
		if (onlyApproved && equal(netDebit, amount)) {
			// Regla 1
			if (status == PaymentStatus.CONFIRMED) {
				return result(payment, Outcome.CONSISTENTE, "Pago confirmado con débito neto igual al monto");
			}
			// Regla 2
			if (status == PaymentStatus.PENDING) {
				return changeStatus(payment, PaymentStatus.CONFIRMED, ACTION_CONFIRM,
						"Proveedor aprobó y existe el débito; estado actualizado a CONFIRMED");
			}
		}
		// Regla 3
		if (onlyApproved && status == PaymentStatus.CONFIRMED
				&& equal(netDebit, amount.multiply(BigDecimal.TWO))) {
			return reverseDuplicateDebit(payment, entries);
		}
		// Regla 5
		if (onlyApproved && netIsZero && status == PaymentStatus.PENDING) {
			return manualReview(payment, "Proveedor aprobó pero no hay débito en el ledger");
		}
		// Regla 6
		if (onlyDeclined && netIsZero && status == PaymentStatus.PENDING) {
			return changeStatus(payment, PaymentStatus.REJECTED, ACTION_REJECT,
					"Proveedor rechazó y no hay débito; estado actualizado a REJECTED");
		}
		// Regla 7: solo DECLINED, débito neto > 0, cualquier estado.
		if (onlyDeclined && netDebit.signum() > 0) {
			return manualReview(payment, "Proveedor rechazó pero existe débito neto");
		}
		// Regla 8: la tabla indica "Nada"; no se escribe nada.
		if (noEvents && netIsZero && status == PaymentStatus.PENDING) {
			return result(payment, Outcome.CONSISTENTE,
					"Pago pendiente sin eventos ni débitos; evidencia insuficiente, sin acción");
		}
		// Regla 9: cualquier otro caso.
		return manualReview(payment, "Caso no contemplado en la tabla de decisión");
	}

	private ReconciliationResult changeStatus(Payment payment, PaymentStatus newStatus, String action, String reason) {
		LocalDateTime now = LocalDateTime.now();
		payment.setStatus(newStatus);
		payment.setUpdatedAt(now);
		audits.save(new SupportAudit(payment.getId(), action, reason, now));
		return result(payment, Outcome.CORREGIDO, reason);
	}

	private ReconciliationResult reverseDuplicateDebit(Payment payment, List<LedgerEntry> entries) {
		Optional<LedgerEntry> target = latestUnreversedDebit(entries);
		if (target.isEmpty()) {
			return manualReview(payment, "Débito duplicado sin DEBIT disponible para revertir");
		}
		LocalDateTime now = LocalDateTime.now();
		ledger.save(new LedgerEntry(payment.getId(), LedgerEntryType.REVERSAL, payment.getAmount(),
				target.get().getId(), now));
		String reason = "Débito duplicado; se registró un REVERSAL por el monto del pago";
		audits.save(new SupportAudit(payment.getId(), ACTION_REVERSAL, reason, now));
		return result(payment, Outcome.CORREGIDO, reason);
	}

	private ReconciliationResult manualReview(Payment payment, String reason) {
		if (!audits.existsByPaymentIdAndAction(payment.getId(), ACTION_MANUAL_REVIEW)) {
			audits.save(new SupportAudit(payment.getId(), ACTION_MANUAL_REVIEW, reason, LocalDateTime.now()));
		}
		return result(payment, Outcome.REVISION_MANUAL, reason);
	}

	private static ReconciliationResult result(Payment payment, Outcome outcome, String reason) {
		return new ReconciliationResult(payment.getExternalRef(), outcome, reason);
	}

	private static Optional<LedgerEntry> latestUnreversedDebit(List<LedgerEntry> entries) {
		Set<Long> reversed = entries.stream()
				.filter(e -> e.getEntryType() == LedgerEntryType.REVERSAL && e.getRelatedEntryId() != null)
				.map(LedgerEntry::getRelatedEntryId)
				.collect(Collectors.toSet());
		return entries.stream()
				.filter(e -> e.getEntryType() == LedgerEntryType.DEBIT && !reversed.contains(e.getId()))
				.max(Comparator.comparing(LedgerEntry::getCreatedAt).thenComparing(LedgerEntry::getId));
	}

	private static long count(List<ProviderEvent> events, ProviderStatus status) {
		return events.stream().filter(e -> e.getProviderStatus() == status).count();
	}

	private static BigDecimal netDebit(List<LedgerEntry> entries) {
		BigDecimal net = BigDecimal.ZERO;
		for (LedgerEntry entry : entries) {
			net = entry.getEntryType() == LedgerEntryType.DEBIT ? net.add(entry.getAmount())
					: net.subtract(entry.getAmount());
		}
		return net;
	}

	private static boolean equal(BigDecimal a, BigDecimal b) {
		return a.compareTo(b) == 0;
	}

	// Evita inyección de líneas en el log con referencias manipuladas.
	private static String sanitize(String value) {
		return value == null ? null : value.replaceAll("[\\r\\n\\t]", "_");
	}
}
