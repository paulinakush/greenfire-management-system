package com.greenfire.ambitos;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AmbitoRepository extends JpaRepository<Ambito, Long> {

    boolean existsByTipo(TipoAmbito tipo);

    List<Ambito> findAllByOrderByTipoAscNombreAsc();

    List<Ambito> findByTipoAndVisibleWebTrueAndActivoTrueOrderByNombre(TipoAmbito tipo);

    /** Un local que se muestra en el sitio público. */
    Optional<Ambito> findByIdAndTipoAndVisibleWebTrueAndActivoTrue(Long id, TipoAmbito tipo);

    Optional<Ambito> findByEsCentralTrue();

    @Query("select a.id from Ambito a where a.entidadFiscal.id = ?1 order by a.id")
    List<Long> idsDeEntidadFiscal(Long entidadFiscalId);
}
