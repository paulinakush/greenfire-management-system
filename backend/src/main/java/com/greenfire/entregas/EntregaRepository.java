package com.greenfire.entregas;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import jakarta.persistence.LockModeType;

/** Sin borrado: las entregas se anulan. */
public interface EntregaRepository extends Repository<Entrega, Long> {

    Entrega save(Entrega entrega);

    void flush();

    Optional<Entrega> findById(Long id);

    /** Bloquea la entrega: dos operaciones sobre ella (recibir y anular, por ejemplo) se ordenan. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Entrega e where e.id = :id")
    Optional<Entrega> bloquear(Long id);

    /** Las que envió la fábrica o las que recibe un local, según el lado. */
    @EntityGraph(attributePaths = {"origen", "destino"})
    @Query("""
            select e from Entrega e
            where (:origenId is null or e.origen.id = :origenId)
              and (:destinoId is null or e.destino.id = :destinoId)
              and (:estado is null or e.estado = :estado)
            order by e.id desc
            """)
    List<Entrega> listar(Long origenId, Long destinoId, EstadoEntrega estado);
}
