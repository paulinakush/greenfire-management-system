package com.greenfire.caja;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface SesionCajaRepository extends JpaRepository<SesionCaja, Long> {

    Optional<SesionCaja> findByAmbitoIdAndEstado(Long ambitoId, EstadoCaja estado);

    Optional<SesionCaja> findByIdAndAmbitoId(Long id, Long ambitoId);

    /**
     * La sesión abierta del local, bloqueada hasta el final de la transacción (SELECT ... FOR UPDATE). Si otra
     * operación la tiene bloqueada, espera; si mientras tanto se cerró, no la encuentra.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SesionCaja s where s.ambito.id = :ambitoId and s.estado = com.greenfire.caja.EstadoCaja.abierta")
    Optional<SesionCaja> bloquearAbierta(@Param("ambitoId") Long ambitoId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SesionCaja s where s.id = :id and s.ambito.id = :ambitoId")
    Optional<SesionCaja> bloquear(@Param("id") Long id, @Param("ambitoId") Long ambitoId);
}
