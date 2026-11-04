package com.greenfire.entregas;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Sin borrado. */
public interface DetalleEntregaRepository extends Repository<DetalleEntrega, Long> {

    DetalleEntrega save(DetalleEntrega detalle);

    void flush();

    @Query("select d from DetalleEntrega d join fetch d.articulo where d.entrega.id = :entregaId order by d.id")
    List<DetalleEntrega> detalle(Long entregaId);

    @Query("select d from DetalleEntrega d join fetch d.articulo where d.entrega.id in :entregas order by d.id")
    List<DetalleEntrega> detalleDe(Collection<Long> entregas);
}
