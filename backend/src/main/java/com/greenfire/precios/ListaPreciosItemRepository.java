package com.greenfire.precios;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ListaPreciosItemRepository extends JpaRepository<ListaPreciosItem, Long> {

    @Query("""
            select i from ListaPreciosItem i join fetch i.articulo a left join fetch a.categoria
            where i.lista.id = :listaId order by a.nombre, a.id
            """)
    List<ListaPreciosItem> listar(Long listaId);

    Optional<ListaPreciosItem> findByListaIdAndArticuloId(Long listaId, Long articuloId);

    boolean existsByListaId(Long listaId);
}
