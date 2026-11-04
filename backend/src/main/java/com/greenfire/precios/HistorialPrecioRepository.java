package com.greenfire.precios;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Solo inserción y consulta: el historial no se edita ni se borra. */
public interface HistorialPrecioRepository extends Repository<HistorialPrecio, Long> {

    HistorialPrecio save(HistorialPrecio registro);

    @EntityGraph(attributePaths = {"articulo", "usuario"})
    @Query("""
            select h from HistorialPrecio h
            where h.lista.id = :listaId and (:articuloId is null or h.articulo.id = :articuloId)
            order by h.fechaHora desc, h.id desc
            """)
    Page<HistorialPrecio> consultar(Long listaId, Long articuloId, Pageable pagina);
}
