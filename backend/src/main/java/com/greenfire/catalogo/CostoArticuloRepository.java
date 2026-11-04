package com.greenfire.catalogo;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CostoArticuloRepository extends JpaRepository<CostoArticulo, Long> {

    Optional<CostoArticulo> findByArticuloIdAndAmbitoId(Long articuloId, Long ambitoId);

    List<CostoArticulo> findByAmbitoIdAndArticuloIdIn(Long ambitoId, Collection<Long> articuloIds);
}
