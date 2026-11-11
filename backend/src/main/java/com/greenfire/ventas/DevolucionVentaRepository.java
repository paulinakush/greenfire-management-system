package com.greenfire.ventas;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DevolucionVentaRepository extends JpaRepository<DevolucionVenta, Long> {

    boolean existsByVentaId(Long ventaId);

    @Query("""
            select d from DevolucionVenta d join fetch d.notaCredito join fetch d.usuario
            where d.venta.id in :ventaIds order by d.id
            """)
    List<DevolucionVenta> deVentas(Collection<Long> ventaIds);
}
