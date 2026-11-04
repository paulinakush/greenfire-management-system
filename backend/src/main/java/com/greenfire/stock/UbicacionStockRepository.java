package com.greenfire.stock;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UbicacionStockRepository extends JpaRepository<UbicacionStock, Long> {

    List<UbicacionStock> findByAmbitoIdOrderByNombre(Long ambitoId);

    List<UbicacionStock> findByAmbitoIdAndActivoTrueOrderByNombre(Long ambitoId);

    boolean existsByAmbitoIdAndNombreIgnoreCase(Long ambitoId, String nombre);

    boolean existsByAmbitoIdAndNombreIgnoreCaseAndIdNot(Long ambitoId, String nombre, Long id);
}
