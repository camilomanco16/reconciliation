package com.pagoclaro.reconciliation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.pagoclaro.reconciliation.domain.LedgerEntry;

// Extiende Repository (no JpaRepository) para exponer solo inserción y lectura:
// el ledger no admite borrados ni modificaciones.
public interface LedgerEntryRepository extends Repository<LedgerEntry, Long> {

	LedgerEntry save(LedgerEntry entry);

	Optional<LedgerEntry> findById(Long id);

	List<LedgerEntry> findByPaymentIdOrderByCreatedAtAsc(Long paymentId);
}
