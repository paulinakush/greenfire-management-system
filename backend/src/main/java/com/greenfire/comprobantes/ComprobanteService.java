package com.greenfire.comprobantes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greenfire.ambitos.AmbitoRepository;
import com.greenfire.comprobantes.dto.ComprobanteResponse;
import com.greenfire.comprobantes.dto.ComprobanteResumen;
import com.greenfire.comprobantes.dto.PaginaComprobantes;
import com.greenfire.comun.NoEncontradoException;
import com.greenfire.fiscal.TipoComprobante;
import com.greenfire.seguridad.Acceso;
import com.greenfire.seguridad.Perfil;
import com.greenfire.seguridad.Sesion;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * Consulta de comprobantes. No hay alta directa: los emite {@link EmisorComprobantes}.
 * <ul>
 * <li>Cada dueño ve los comprobantes de la entidad fiscal de su ámbito activo.</li>
 * <li>El empleado ve e imprime solo los de los puntos de venta de su local.</li>
 * <li>El administrador técnico no ve datos comerciales: 403.</li>
 * </ul>
 * Nadie ve los de otra entidad: pedirlos por id responde 404.
 */
@Service
@Transactional(readOnly = true)
public class ComprobanteService {

    public static final int TAMANO_MAXIMO = 100;
    private static final String NO_EXISTE = "No existe el comprobante indicado.";

    private final ComprobanteRepository comprobantes;
    private final AmbitoRepository ambitos;
    private final VistaImprimible vista;
    private final EntityManager em;
    private final Acceso acceso;
    private final ZoneId zona;

    public ComprobanteService(ComprobanteRepository comprobantes, AmbitoRepository ambitos, VistaImprimible vista,
            EntityManager em, Acceso acceso, @Value("${greenfire.zona-horaria}") String zona) {
        this.comprobantes = comprobantes;
        this.ambitos = ambitos;
        this.vista = vista;
        this.em = em;
        this.acceso = acceso;
        this.zona = ZoneId.of(zona);
    }

    public PaginaComprobantes listar(TipoComprobante tipo, LocalDate desde, LocalDate hasta, Long clienteId,
            int pagina, int tamano) {
        Alcance alcance = alcance(acceso.exigirAmbito());
        int tamanoReal = Math.clamp(tamano, 1, TAMANO_MAXIMO);
        int paginaReal = Math.max(pagina, 0);
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<Comprobante> consulta = cb.createQuery(Comprobante.class);
        Root<Comprobante> c = consulta.from(Comprobante.class);
        c.fetch("puntoVenta");
        consulta.where(filtros(cb, c, alcance, tipo, desde, hasta, clienteId))
                .orderBy(cb.desc(c.get("fecha")), cb.desc(c.get("id")));
        List<ComprobanteResumen> lista = em.createQuery(consulta)
                .setFirstResult(paginaReal * tamanoReal)
                .setMaxResults(tamanoReal)
                .getResultStream().map(ComprobanteResumen::de).toList();

        CriteriaQuery<Long> conteo = cb.createQuery(Long.class);
        Root<Comprobante> k = conteo.from(Comprobante.class);
        conteo.select(cb.count(k)).where(filtros(cb, k, alcance, tipo, desde, hasta, clienteId));
        return new PaginaComprobantes(lista, paginaReal, tamanoReal, em.createQuery(conteo).getSingleResult());
    }

    public ComprobanteResponse obtener(Long id) {
        return ComprobanteResponse.de(buscar(id));
    }

    public String imprimible(Long id) {
        return vista.html(buscar(id));
    }

    // --- Alcance de cada perfil ---

    /** Lo que ve la sesión: los de la entidad fiscal (dueños) o los del ámbito (empleado). */
    private record Alcance(Long entidadFiscalId, Long ambitoId) {
    }

    private Alcance alcance(Sesion sesion) {
        if (sesion.perfil() == Perfil.empleado_local) {
            return new Alcance(null, sesion.ambitoId());
        }
        Long entidad = ambitos.findById(sesion.ambitoId()).orElseThrow(() -> new NoEncontradoException(NO_EXISTE))
                .getEntidadFiscal().getId();
        return new Alcance(entidad, null);
    }

    private Comprobante buscar(Long id) {
        Alcance alcance = alcance(acceso.exigirAmbito());
        return comprobantes.findById(id)
                .filter(c -> alcance.ambitoId() != null
                        ? c.getPuntoVenta().getAmbito().getId().equals(alcance.ambitoId())
                        : c.getPuntoVenta().getEntidadFiscal().getId().equals(alcance.entidadFiscalId()))
                .orElseThrow(() -> new NoEncontradoException(NO_EXISTE));
    }

    private Predicate[] filtros(CriteriaBuilder cb, Root<Comprobante> c, Alcance alcance, TipoComprobante tipo,
            LocalDate desde, LocalDate hasta, Long clienteId) {
        List<Predicate> p = new ArrayList<>();
        if (alcance.ambitoId() != null) {
            p.add(cb.equal(c.get("puntoVenta").get("ambito").get("id"), alcance.ambitoId()));
        } else {
            p.add(cb.equal(c.get("puntoVenta").get("entidadFiscal").get("id"), alcance.entidadFiscalId()));
        }
        if (tipo != null) {
            p.add(cb.equal(c.get("tipo"), tipo));
        }
        if (desde != null) {
            p.add(cb.greaterThanOrEqualTo(c.get("fecha"), inicioDelDia(desde)));
        }
        if (hasta != null) {
            p.add(cb.lessThan(c.get("fecha"), inicioDelDia(hasta.plusDays(1))));
        }
        if (clienteId != null) {
            p.add(cb.equal(c.get("cliente").get("id"), clienteId));
        }
        return p.toArray(Predicate[]::new);
    }

    private OffsetDateTime inicioDelDia(LocalDate dia) {
        return dia.atStartOfDay(zona).toOffsetDateTime();
    }
}
