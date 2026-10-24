package com.pagoclaro.reconciliation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pagoclaro.reconciliation.domain.ProviderEvent;

public interface ProviderEventRepository extends JpaRepository<ProviderEvent, Long> {

	List<ProviderEvent> findByPaymentIdOrderByReceivedAtAsc(Long paymentId);
}
