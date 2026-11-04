package com.greenfire.stock;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import jakarta.persistence.LockModeType;

/** Sin borrado: los conteos se anulan, no se borran (la base también lo rechaza). */
public interface ConteoStockRepository extends Repository<ConteoStock, Long> {

    ConteoStock save(ConteoStock conteo);

    void flush();

    Optional<ConteoStock> findById(Long id);

    /** Bloquea el conteo: dos operaciones sobre el mismo conteo (cerrar y anular, por ejemplo) se ordenan. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ConteoStock c where c.id = :id")
    Optional<ConteoStock> bloquear(Long id);

    boolean existsByUbicacionIdAndEstado(Long ubicacionId, EstadoConteo estado);

    @EntityGraph(attributePaths = {"ubicacion", "usuario"})
    @Query("""
            select c from ConteoStock c
            where c.ubicacion.ambito.id = :ambitoId
              and (:ubicacionId is null or c.ubicacion.id = :ubicacionId)
              and (:estado is null or c.estado = :estado)
            order by c.id desc
            """)
    List<ConteoStock> listar(Long ambitoId, Long ubicacionId, EstadoConteo estado);
}
