package com.greenfire.stock;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StockArticuloRepository extends JpaRepository<StockArticulo, Long> {

    Optional<StockArticulo> findByArticuloIdAndUbicacionId(Long articuloId, Long ubicacionId);

    /** La cantidad leída de la base, no del contexto de persistencia: el trigger de movimientos la cambia. */
    @Query("select s.cantidadActual from StockArticulo s where s.articulo.id = :articuloId and s.ubicacion.id = :ubicacionId")
    Optional<BigDecimal> cantidad(Long articuloId, Long ubicacionId);

    @Query("select count(s) > 0 from StockArticulo s where s.ubicacion.id = :ubicacionId and s.cantidadActual > 0")
    boolean tieneStock(Long ubicacionId);
}
