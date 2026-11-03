package com.greenfire.catalogo;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ArticuloRepository extends JpaRepository<Articulo, Long>, JpaSpecificationExecutor<Articulo> {

    @Override
    @EntityGraph(attributePaths = {"categoria", "ambito"})
    List<Articulo> findAll(Specification<Articulo> spec, Sort sort);

    boolean existsByAmbitoIdAndNombreIgnoreCase(Long ambitoId, String nombre);

    boolean existsByAmbitoIdAndNombreIgnoreCaseAndIdNot(Long ambitoId, String nombre, Long id);
}
