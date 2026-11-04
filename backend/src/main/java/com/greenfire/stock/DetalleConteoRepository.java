package com.greenfire.stock;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface DetalleConteoRepository extends Repository<DetalleConteo, Long> {

    DetalleConteo save(DetalleConteo detalle);

    void delete(DetalleConteo detalle);

    void flush();

    @Query("select d from DetalleConteo d join fetch d.articulo a where d.conteo.id = :conteoId order by a.nombre, a.id")
    List<DetalleConteo> detalle(Long conteoId);

    Optional<DetalleConteo> findByConteoIdAndArticuloId(Long conteoId, Long articuloId);
}
