package com.greenfire.ambitos;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AmbitoRepository extends JpaRepository<Ambito, Long> {

    boolean existsByTipo(TipoAmbito tipo);

    List<Ambito> findAllByOrderByTipoAscNombreAsc();

    List<Ambito> findByTipoAndVisibleWebTrueAndActivoTrueOrderByNombre(TipoAmbito tipo);
}
