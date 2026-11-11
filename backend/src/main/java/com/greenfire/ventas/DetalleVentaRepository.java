package com.greenfire.ventas;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DetalleVentaRepository extends JpaRepository<DetalleVenta, Long> {

    @Query("""
            select d from DetalleVenta d join fetch d.articulo join fetch d.ubicacion
            where d.venta.id in :ventaIds order by d.id
            """)
    List<DetalleVenta> deVentas(Collection<Long> ventaIds);
}
