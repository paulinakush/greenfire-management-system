package com.greenfire.auditoria;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.greenfire.auditoria.dto.AuditoriaResponse;
import com.greenfire.auditoria.dto.PaginaAuditoria;
import com.greenfire.seguridad.Acceso;
import com.greenfire.seguridad.Perfil;
import com.greenfire.seguridad.Sesion;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * Consulta de auditoría. El administrador técnico ve todo, incluidos los logins fallidos; cada dueño ve solo
 * lo ocurrido en su ámbito activo; el empleado no ve nada.
 */
@Service
@Transactional(readOnly = true)
public class AuditoriaService {

    public static final int TAMANO_MAXIMO = 100;

    private final EntityManager em;
    private final Acceso acceso;
    private final ZoneId zona;

    public AuditoriaService(EntityManager em, Acceso acceso, @Value("${greenfire.zona-horaria}") String zona) {
        this.em = em;
        this.acceso = acceso;
        this.zona = ZoneId.of(zona);
    }

    /** {@code desde} y {@code hasta} son días completos, ambos incluidos, en la zona horaria de la empresa. */
    public PaginaAuditoria consultar(LocalDate desde, LocalDate hasta, Long usuarioId, String accion, int pagina,
            int tamano) {
        Long ambitoId = ambitoVisible();
        int tamanoReal = Math.clamp(tamano, 1, TAMANO_MAXIMO);
        int paginaReal = Math.max(pagina, 0);
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<Auditoria> consulta = cb.createQuery(Auditoria.class);
        Root<Auditoria> a = consulta.from(Auditoria.class);
        a.fetch("usuario", JoinType.LEFT);
        a.fetch("ambito", JoinType.LEFT);
        consulta.where(filtros(cb, a, ambitoId, desde, hasta, usuarioId, accion))
                .orderBy(cb.desc(a.get("fechaHora")), cb.desc(a.get("id")));
        List<AuditoriaResponse> registros = em.createQuery(consulta)
                .setFirstResult(paginaReal * tamanoReal)
                .setMaxResults(tamanoReal)
                .getResultStream().map(AuditoriaResponse::de).toList();

        CriteriaQuery<Long> conteo = cb.createQuery(Long.class);
        Root<Auditoria> c = conteo.from(Auditoria.class);
        conteo.select(cb.count(c)).where(filtros(cb, c, ambitoId, desde, hasta, usuarioId, accion));
        long total = em.createQuery(conteo).getSingleResult();

        return new PaginaAuditoria(registros, paginaReal, tamanoReal, total);
    }

    /** null = todos los ámbitos (solo el administrador técnico). */
    private Long ambitoVisible() {
        Sesion sesion = acceso.sesion();
        if (sesion.adminTecnico()) {
            return null;
        }
        return acceso.exigirPerfil(Perfil.dueno_local, Perfil.dueno_fabrica).ambitoId();
    }

    private Predicate[] filtros(CriteriaBuilder cb, Root<Auditoria> a, Long ambitoId, LocalDate desde,
            LocalDate hasta, Long usuarioId, String accion) {
        List<Predicate> p = new ArrayList<>();
        if (ambitoId != null) {
            p.add(cb.equal(a.get("ambito").get("id"), ambitoId));
        }
        if (desde != null) {
            p.add(cb.greaterThanOrEqualTo(a.get("fechaHora"), inicioDelDia(desde)));
        }
        if (hasta != null) {
            p.add(cb.lessThan(a.get("fechaHora"), inicioDelDia(hasta.plusDays(1))));
        }
        if (usuarioId != null) {
            p.add(cb.equal(a.get("usuario").get("id"), usuarioId));
        }
        if (StringUtils.hasText(accion)) {
            p.add(cb.equal(a.get("accion"), accion.trim()));
        }
        return p.toArray(Predicate[]::new);
    }

    private OffsetDateTime inicioDelDia(LocalDate dia) {
        return dia.atStartOfDay(zona).toOffsetDateTime();
    }
}
