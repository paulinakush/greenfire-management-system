package com.greenfire.ventas;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DetalleDevolucionVentaRepository extends JpaRepository<DetalleDevolucionVenta, Long> {

    @Query("select d from DetalleDevolucionVenta d where d.devolucion.venta.id in :ventaIds order by d.id")
    List<DetalleDevolucionVenta> deVentas(Collection<Long> ventaIds);
}
