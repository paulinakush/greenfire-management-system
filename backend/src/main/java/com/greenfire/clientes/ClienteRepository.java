package com.greenfire.clientes;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Sin borrado: los clientes se desactivan. */
public interface ClienteRepository extends JpaRepository<Cliente, Long> {

    Optional<Cliente> findByIdAndAmbitoId(Long id, Long ambitoId);

    /** Para no repetir el documento dentro del ámbito; no cuenta los clientes que representan a un local. */
    @Query("""
            select count(c) > 0 from Cliente c
            where c.ambito.id = ?1 and c.documentoTipo = ?2 and c.documentoNumero = ?3
              and c.ambitoRepresentado is null and (?4 is null or c.id <> ?4)
            """)
    boolean existeDocumento(Long ambitoId, TipoDocumento tipo, String numero, Long excepto);

    Optional<Cliente> findByAmbitoRepresentadoId(Long ambitoId);
}
