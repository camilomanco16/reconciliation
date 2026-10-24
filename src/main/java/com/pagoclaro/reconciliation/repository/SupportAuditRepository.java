package com.pagoclaro.reconciliation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pagoclaro.reconciliation.domain.SupportAudit;

public interface SupportAuditRepository extends JpaRepository<SupportAudit, Long> {

	boolean existsByPaymentIdAndAction(Long paymentId, String action);
}
