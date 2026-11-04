package com.greenfire.precios;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ListaPreciosRepository extends JpaRepository<ListaPrecios, Long> {

    List<ListaPrecios> findByAmbitoIdOrderByNombre(Long ambitoId);

    Optional<ListaPrecios> findByAmbitoIdAndEsPredeterminadaTrue(Long ambitoId);

    boolean existsByAmbitoIdAndNombreIgnoreCase(Long ambitoId, String nombre);

    boolean existsByAmbitoIdAndNombreIgnoreCaseAndIdNot(Long ambitoId, String nombre, Long id);

    /** Las listas de todos los ámbitos de una entidad fiscal. */
    @Query("""
            select l from ListaPrecios l join fetch l.ambito a
            where a.entidadFiscal.id = :entidadFiscalId order by a.nombre, l.nombre
            """)
    List<ListaPrecios> deEntidadFiscal(Long entidadFiscalId);
}
