package com.greenfire.fiscal;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EntidadFiscalRepository extends JpaRepository<EntidadFiscal, Long> {

    boolean existsByCuit(String cuit);

    boolean existsByCuitAndIdNot(String cuit, Long id);

    List<EntidadFiscal> findAllByOrderByRazonSocial();
}
