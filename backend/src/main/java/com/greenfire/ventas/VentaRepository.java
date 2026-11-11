package com.greenfire.ventas;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface VentaRepository extends JpaRepository<Venta, Long> {

    Optional<Venta> findByIdAndAmbitoId(Long id, Long ambitoId);

    /** Bloquea la venta hasta el final de la transacción: la anulación y las devoluciones se ordenan por este bloqueo. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Venta v where v.id = :id and v.ambito.id = :ambitoId")
    Optional<Venta> bloquear(Long id, Long ambitoId);
}
