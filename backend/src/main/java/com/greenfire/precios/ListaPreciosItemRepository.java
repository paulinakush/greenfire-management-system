package com.greenfire.precios;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.greenfire.catalogo.TipoArticulo;

public interface ListaPreciosItemRepository extends JpaRepository<ListaPreciosItem, Long> {

    @Query("""
            select i from ListaPreciosItem i join fetch i.articulo a left join fetch a.categoria
            where i.lista.id = :listaId order by a.nombre, a.id
            """)
    List<ListaPreciosItem> listar(Long listaId);

    Optional<ListaPreciosItem> findByListaIdAndArticuloId(Long listaId, Long articuloId);

    boolean existsByListaId(Long listaId);

    /** Condiciones del catálogo público, comunes al listado y al detalle (ver CatalogoPublicoService). */
    String CATALOGO_PUBLICO = """
            from ListaPreciosItem i join i.lista l join i.articulo a join a.categoria c
            where l.ambito.id = :localId and l.esPredeterminada = true and l.activo = true
              and a.activo = true and a.visibleWeb = true and c.activo = true
              and a.tipo in :tipos and (a.tipo = com.greenfire.catalogo.TipoArticulo.producto_terminado
                                        or a.ambito.id = :localId)
            """;

    @EntityGraph(attributePaths = {"lista", "articulo", "articulo.categoria"})
    @Query(value = "select i " + CATALOGO_PUBLICO + """
              and (:categoriaId is null or c.id = :categoriaId)
              and (lower(a.nombre) like :patron escape '!' or lower(coalesce(a.marca, '')) like :patron escape '!')
            order by a.nombre, a.id
            """, countQuery = "select count(i) " + CATALOGO_PUBLICO + """
              and (:categoriaId is null or c.id = :categoriaId)
              and (lower(a.nombre) like :patron escape '!' or lower(coalesce(a.marca, '')) like :patron escape '!')
            """)
    Page<ListaPreciosItem> catalogoPublico(Long localId, Collection<TipoArticulo> tipos, Long categoriaId,
            String patron, Pageable pagina);

    @EntityGraph(attributePaths = {"lista", "articulo", "articulo.categoria"})
    @Query("select i " + CATALOGO_PUBLICO + " and a.id = :articuloId")
    Optional<ListaPreciosItem> articuloPublico(Long localId, Collection<TipoArticulo> tipos, Long articuloId);
}
