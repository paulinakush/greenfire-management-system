package com.greenfire.caja;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MovimientoCajaRepository extends JpaRepository<MovimientoCaja, Long> {

    List<MovimientoCaja> findBySesionIdOrderById(Long sesionId);

    Optional<MovimientoCaja> findByIdAndSesionId(Long id, Long sesionId);

    @Query("select coalesce(sum(m.monto), 0) from MovimientoCaja m where m.corrige.id = :id")
    BigDecimal corregido(@Param("id") Long movimientoId);
}
