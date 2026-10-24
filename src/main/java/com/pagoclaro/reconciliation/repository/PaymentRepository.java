package com.pagoclaro.reconciliation.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pagoclaro.reconciliation.domain.Payment;

import jakarta.persistence.LockModeType;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

	// SELECT ... FOR UPDATE: debe llamarse dentro de una transacción.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from Payment p where p.externalRef = :externalRef")
	Optional<Payment> findByExternalRefForUpdate(@Param("externalRef") String externalRef);
}
