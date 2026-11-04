package com.greenfire.stock;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
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

    /** De los artículos indicados, los que suman más de cero entre las ubicaciones activas del ámbito. */
    @Query("""
            select s.articulo.id from StockArticulo s
            where s.ubicacion.ambito.id = :ambitoId and s.ubicacion.activo = true and s.articulo.id in :articulos
            group by s.articulo.id having sum(s.cantidadActual) > 0
            """)
    List<Long> conStock(Long ambitoId, Collection<Long> articulos);
}
