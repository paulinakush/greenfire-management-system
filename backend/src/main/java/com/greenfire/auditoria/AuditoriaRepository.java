package com.greenfire.auditoria;

import org.springframework.data.repository.Repository;

/** Solo alta: a propósito no extiende JpaRepository, para no exponer métodos de borrado. */
public interface AuditoriaRepository extends Repository<Auditoria, Long> {

    Auditoria save(Auditoria auditoria);
}
