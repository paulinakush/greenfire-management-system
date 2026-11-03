package com.greenfire.fiscal;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PuntoVentaRepository extends JpaRepository<PuntoVenta, Long> {

    boolean existsByEntidadFiscalIdAndNumero(Long entidadFiscalId, Integer numero);

    boolean existsByEntidadFiscalIdAndNumeroAndIdNot(Long entidadFiscalId, Integer numero, Long id);

    List<PuntoVenta> findAllByOrderByEntidadFiscalIdAscNumeroAsc();

    List<PuntoVenta> findByAmbitoIdOrderByNumero(Long ambitoId);

    List<PuntoVenta> findByEntidadFiscalId(Long entidadFiscalId);
}
