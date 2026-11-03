package com.greenfire.ambitos;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AmbitoRepository extends JpaRepository<Ambito, Long> {

    boolean existsByTipo(TipoAmbito tipo);

    List<Ambito> findAllByOrderByTipoAscNombreAsc();

    List<Ambito> findByTipoAndVisibleWebTrueAndActivoTrueOrderByNombre(TipoAmbito tipo);

    @Query("select a.id from Ambito a where a.entidadFiscal.id = ?1 order by a.id")
    List<Long> idsDeEntidadFiscal(Long entidadFiscalId);
}
