package com.greenfire.caja;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.greenfire.ambitos.Ambito;
import com.greenfire.ambitos.AmbitoRepository;
import com.greenfire.ambitos.TipoAmbito;
import com.greenfire.auditoria.Auditor;
import com.greenfire.caja.dto.AperturaRequest;
import com.greenfire.caja.dto.CierreRequest;
import com.greenfire.caja.dto.CorreccionRequest;
import com.greenfire.caja.dto.MovimientoCajaResponse;
import com.greenfire.caja.dto.MovimientoRequest;
import com.greenfire.caja.dto.PaginaSesionesCaja;
import com.greenfire.caja.dto.SesionCajaEmpleadoResponse;
import com.greenfire.caja.dto.SesionCajaResponse;
import com.greenfire.caja.dto.SesionCajaResumen;
import com.greenfire.caja.dto.SesionCajaVista;
import com.greenfire.comun.AccesoDenegadoException;
import com.greenfire.comun.Cambios;
import com.greenfire.comun.NoEncontradoException;
import com.greenfire.comun.ReglaNegocioException;
import com.greenfire.comun.SolicitudInvalidaException;
import com.greenfire.seguridad.Acceso;
import com.greenfire.seguridad.Perfil;
import com.greenfire.seguridad.Sesion;
import com.greenfire.usuarios.UsuarioRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * Caja de cada local: una sola sesión abierta por local, que funciona como turno.
 * <ul>
 * <li><b>Empleado:</b> abre, registra ingresos y gastos y cierra a ciegas (informa lo contado sin ver el esperado,
 * y recién después ve la diferencia). Ve la sesión abierta y las del día. No registra retiros ni pagos a la fábrica
 * y no corrige cierres.</li>
 * <li><b>Dueño de local:</b> todo, con el historial, los retiros y la corrección autorizada de un cierre.</li>
 * <li>La fábrica no tiene caja: el dueño de fábrica recibe 403, salvo que entre a un local donde también es dueño
 * (el Local La Rioja). El administrador técnico, 403. Una sesión de otro local, 404.</li>
 * </ul>
 * Toda operación que toca la sesión abierta la bloquea primero (SELECT ... FOR UPDATE): así un cierre y un
 * movimiento simultáneos se ordenan, y el cierre incluye todo lo que entró antes.
 */
@Service
@Transactional
public class CajaService {

    public static final int TAMANO_MAXIMO = 100;

    private static final String SESION = "sesion_caja";
    private static final String MOVIMIENTO = "movimiento_caja";
    private static final String YA_ABIERTA = "Ya hay una caja abierta en este local.";
    private static final String SIN_CAJA = "No hay una caja abierta en este local.";
    private static final String NO_EXISTE = "No existe la sesión de caja indicada.";

    private final SesionCajaRepository sesiones;
    private final MovimientoCajaRepository movimientos;
    private final AmbitoRepository ambitos;
    private final UsuarioRepository usuarios;
    private final EntityManager em;
    private final Acceso acceso;
    private final Auditor auditor;
    private final ZoneId zona;

    public CajaService(SesionCajaRepository sesiones, MovimientoCajaRepository movimientos, AmbitoRepository ambitos,
            UsuarioRepository usuarios, EntityManager em, Acceso acceso, Auditor auditor,
            @Value("${greenfire.zona-horaria}") String zona) {
        this.sesiones = sesiones;
        this.movimientos = movimientos;
        this.ambitos = ambitos;
        this.usuarios = usuarios;
        this.em = em;
        this.acceso = acceso;
        this.auditor = auditor;
        this.zona = ZoneId.of(zona);
    }

    // --- Apertura, movimientos y cierre ---

    /** Abre la caja del local. Si ya hay una abierta, 409 (lo garantiza el índice uq_sesiones_caja_abierta). */
    public SesionCajaVista abrir(AperturaRequest req) {
        Sesion sesion = exigirCaja();
        Ambito local = local(sesion);
        if (sesiones.findByAmbitoIdAndEstado(local.getId(), EstadoCaja.abierta).isPresent()) {
            throw new ReglaNegocioException(YA_ABIERTA);
        }
        SesionCaja c = sesiones.saveAndFlush(
                new SesionCaja(local, usuarios.getReferenceById(sesion.usuarioId()), centavos(req.saldoInicial())));

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("saldoInicial", c.getSaldoInicial());
        auditor.registrar("abrir", SESION, c.getId(), List.of(local.getId()), detalle);
        return vista(c, sesion, List.of());
    }

    /** La sesión abierta del local, si hay una. */
    @Transactional(readOnly = true)
    public Optional<SesionCajaVista> actual() {
        Sesion sesion = exigirCaja();
        return sesiones.findByAmbitoIdAndEstado(sesion.ambitoId(), EstadoCaja.abierta)
                .map(c -> vista(c, sesion, movimientos.findBySesionIdOrderById(c.getId())));
    }

    /** Un ingreso o egreso manual, o la corrección de uno con un movimiento contrario. */
    public MovimientoCajaResponse registrarMovimiento(MovimientoRequest req) {
        Sesion sesion = exigirCaja();
        if (esEmpleado(sesion) && req.rubro() != null && !RubroCaja.DEL_EMPLEADO.contains(req.rubro())) {
            throw new AccesoDenegadoException();
        }
        SesionCaja caja = exigirAbierta(sesion.ambitoId());

        MovimientoCaja original = null;
        TipoMovimientoCaja tipo;
        RubroCaja rubro;
        MedioPago medio;
        if (req.corrigeMovimientoId() != null) {
            original = movimientos.findByIdAndSesionId(req.corrigeMovimientoId(), caja.getId())
                    .orElseThrow(() -> new NoEncontradoException("No existe el movimiento a corregir en la caja abierta."));
            if (original.getOrigen() != OrigenMovimientoCaja.manual) {
                throw new ReglaNegocioException("Solo se corrigen movimientos manuales: una venta o una cobranza se "
                        + "corrige desde su operación.");
            }
            if (original.getCorrige() != null) {
                throw new ReglaNegocioException("Una corrección no se corrige: registrá un movimiento nuevo.");
            }
            tipo = original.getTipo().contrario();
            rubro = original.getRubro();
            medio = original.getMedioPago();
            if ((req.tipo() != null && req.tipo() != tipo) || (req.rubro() != null && req.rubro() != rubro)
                    || (req.medioPago() != null && req.medioPago() != medio)) {
                throw new SolicitudInvalidaException("La corrección es el movimiento contrario al original: "
                        + tipo + ", con su rubro y su medio.");
            }
        } else {
            if (req.rubro() == null || req.medioPago() == null) {
                throw new SolicitudInvalidaException("Indicá el rubro y el medio de pago.");
            }
            rubro = req.rubro();
            medio = req.medioPago();
            tipo = rubro.tipoNatural() != null ? rubro.tipoNatural() : req.tipo();
            if (tipo == null) {
                throw new SolicitudInvalidaException("Indicá si el movimiento es un ingreso o un egreso.");
            }
            if (req.tipo() != null && req.tipo() != tipo) {
                throw new SolicitudInvalidaException("Un movimiento de rubro " + rubro + " es un " + tipo + ".");
            }
        }
        if (!medio.pasaPorCaja()) {
            throw new SolicitudInvalidaException("La cuenta corriente no pasa por la caja.");
        }
        if (esEmpleado(sesion) && !RubroCaja.DEL_EMPLEADO.contains(rubro)) {
            throw new AccesoDenegadoException();
        }
        BigDecimal monto = centavos(req.monto());
        if (original != null && monto.compareTo(original.getMonto().subtract(movimientos.corregido(original.getId()))) > 0) {
            throw new ReglaNegocioException("La corrección supera lo que queda por corregir del movimiento.");
        }
        return MovimientoCajaResponse.de(guardar(caja, OrigenMovimientoCaja.manual, tipo, rubro, medio, monto,
                req.observacion().trim(), original, sesion.usuarioId()));
    }

    /**
     * Cierra la caja abierta con el efectivo contado. Guarda los totales por medio, el esperado, lo contado y la
     * diferencia, todo o nada. El empleado recién ve el esperado y la diferencia en esta respuesta.
     */
    public SesionCajaVista cerrar(CierreRequest req) {
        Sesion sesion = exigirCaja();
        SesionCaja caja = exigirAbierta(sesion.ambitoId());
        List<MovimientoCaja> lista = movimientos.findBySesionIdOrderById(caja.getId());
        CierreCaja.Totales totales = CierreCaja.calcular(caja.getSaldoInicial(), lista);
        caja.cerrar(usuarios.getReferenceById(sesion.usuarioId()), totales, centavos(req.efectivoContado()),
                texto(req.observaciones()));
        sesiones.flush();

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("totalEfectivo", caja.getTotalEfectivo());
        detalle.put("totalTransferencia", caja.getTotalTransferencia());
        detalle.put("totalBilletera", caja.getTotalBilletera());
        detalle.put("efectivoEsperado", caja.getEfectivoEsperado());
        detalle.put("efectivoContado", caja.getEfectivoContado());
        detalle.put("diferencia", caja.getDiferencia());
        auditor.registrar("cerrar", SESION, caja.getId(), List.of(caja.getAmbito().getId()), detalle);
        return vista(caja, sesion, lista);
    }

    /** Corrección autorizada de un cierre: solo el dueño, solo el efectivo contado (y con él la diferencia). */
    public SesionCajaResponse corregir(Long id, CorreccionRequest req) {
        Sesion sesion = acceso.exigirPerfil(Perfil.dueno_local);
        SesionCaja caja = sesiones.bloquear(id, sesion.ambitoId())
                .orElseThrow(() -> new NoEncontradoException(NO_EXISTE));
        if (caja.estaAbierta()) {
            throw new ReglaNegocioException("La caja está abierta: se corrige después del cierre.");
        }
        BigDecimal contado = centavos(req.efectivoContado());
        if (contado.compareTo(caja.getEfectivoContado()) == 0) {
            throw new ReglaNegocioException("El efectivo contado es el mismo: no hay nada que corregir.");
        }
        Cambios cambios = new Cambios();
        cambios.registrar("efectivoContado", caja.getEfectivoContado(), contado);
        cambios.registrar("diferencia", caja.getDiferencia(), contado.subtract(caja.getEfectivoEsperado()));
        caja.corregir(usuarios.getReferenceById(sesion.usuarioId()), contado, req.motivo().trim());
        sesiones.flush();

        Map<String, Object> detalle = new LinkedHashMap<>(cambios.comoMapa());
        detalle.put("motivo", caja.getMotivoCorreccion());
        auditor.registrar("corregir_cierre", SESION, id, List.of(caja.getAmbito().getId()), detalle);
        return SesionCajaResponse.de(caja, movimientos.findBySesionIdOrderById(id), OffsetDateTime.now());
    }

    // --- Consulta ---

    /**
     * El dueño ve el historial completo, con filtros por día de apertura. El empleado, la sesión abierta y las del
     * día, sin filtros.
     */
    @Transactional(readOnly = true)
    public PaginaSesionesCaja listar(LocalDate desde, LocalDate hasta, int pagina, int tamano) {
        Sesion sesion = exigirCaja();
        if (esEmpleado(sesion)) {
            List<SesionCajaVista> delDia = em.createQuery("""
                            select s from SesionCaja s where s.ambito.id = :ambito
                              and (s.estado = com.greenfire.caja.EstadoCaja.abierta
                                   or s.abiertaAt >= :inicio or s.cerradaAt >= :inicio)
                            order by s.abiertaAt desc, s.id desc
                            """, SesionCaja.class)
                    .setParameter("ambito", sesion.ambitoId())
                    .setParameter("inicio", inicioDelDia(LocalDate.now(zona)))
                    .getResultStream()
                    .map(c -> vista(c, sesion, movimientos.findBySesionIdOrderById(c.getId())))
                    .toList();
            return new PaginaSesionesCaja(delDia, 0, delDia.size(), delDia.size());
        }
        int tamanoReal = Math.clamp(tamano, 1, TAMANO_MAXIMO);
        int paginaReal = Math.max(pagina, 0);
        OffsetDateTime ahora = OffsetDateTime.now();
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SesionCaja> consulta = cb.createQuery(SesionCaja.class);
        Root<SesionCaja> s = consulta.from(SesionCaja.class);
        consulta.where(filtros(cb, s, sesion.ambitoId(), desde, hasta))
                .orderBy(cb.desc(s.get("abiertaAt")), cb.desc(s.get("id")));
        List<SesionCajaResumen> lista = em.createQuery(consulta)
                .setFirstResult(paginaReal * tamanoReal).setMaxResults(tamanoReal)
                .getResultStream().map(c -> SesionCajaResumen.de(c, ahora)).toList();
        CriteriaQuery<Long> conteo = cb.createQuery(Long.class);
        Root<SesionCaja> k = conteo.from(SesionCaja.class);
        conteo.select(cb.count(k)).where(filtros(cb, k, sesion.ambitoId(), desde, hasta));
        return new PaginaSesionesCaja(lista, paginaReal, tamanoReal, em.createQuery(conteo).getSingleResult());
    }

    /** Una sesión del local. Para el empleado, solo la abierta y las del día; las demás no existen para él. */
    @Transactional(readOnly = true)
    public SesionCajaVista obtener(Long id) {
        Sesion sesion = exigirCaja();
        SesionCaja caja = sesiones.findByIdAndAmbitoId(id, sesion.ambitoId())
                .filter(c -> !esEmpleado(sesion) || delDia(c))
                .orElseThrow(() -> new NoEncontradoException(NO_EXISTE));
        return vista(caja, sesion, movimientos.findBySesionIdOrderById(id));
    }

    // --- Puntos de enganche para las ventas (módulo 5) y las cobranzas (módulo 7) ---

    /**
     * La sesión abierta del local, bloqueada hasta el final de la transacción. Sin caja abierta no se registran
     * ventas ni cobranzas del local: falla con 409. No valida permisos: lo hace el servicio que la llama.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SesionCaja exigirAbierta(Long ambitoId) {
        return sesiones.bloquearAbierta(ambitoId).orElseThrow(() -> new ReglaNegocioException(SIN_CAJA));
    }

    /**
     * El movimiento de caja de una venta, una cobranza o una devolución (el tipo sale del origen), en la sesión que
     * devolvió {@link #exigirAbierta}. Una venta a cuenta corriente no pasa por la caja: no se llama.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public MovimientoCaja registrarCobro(SesionCaja caja, OrigenMovimientoCaja origen, MedioPago medio,
            BigDecimal monto, Long usuarioId, String observacion) {
        if (origen == OrigenMovimientoCaja.manual) {
            throw new IllegalArgumentException("Los movimientos manuales se registran con registrarMovimiento.");
        }
        if (!medio.pasaPorCaja()) {
            throw new IllegalArgumentException("La cuenta corriente no pasa por la caja.");
        }
        if (monto == null || monto.signum() <= 0) {
            throw new IllegalArgumentException("El monto de un movimiento de caja es mayor que cero.");
        }
        if (!caja.estaAbierta()) {
            throw new ReglaNegocioException(SIN_CAJA);
        }
        return guardar(caja, origen, origen.tipo(), null, medio, centavos(monto), texto(observacion), null, usuarioId);
    }

    // --- Ayudas ---

    private MovimientoCaja guardar(SesionCaja caja, OrigenMovimientoCaja origen, TipoMovimientoCaja tipo,
            RubroCaja rubro, MedioPago medio, BigDecimal monto, String observacion, MovimientoCaja original,
            Long usuarioId) {
        MovimientoCaja m = movimientos.saveAndFlush(new MovimientoCaja(caja, usuarios.getReferenceById(usuarioId),
                origen, tipo, rubro, medio, monto, observacion, original));
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("sesionCajaId", caja.getId());
        detalle.put("origen", origen);
        detalle.put("tipo", tipo);
        detalle.put("rubro", rubro);
        detalle.put("medioPago", medio);
        detalle.put("monto", monto);
        detalle.put("observacion", observacion);
        detalle.put("corrigeMovimientoId", original == null ? null : original.getId());
        auditor.registrar("registrar_movimiento", MOVIMIENTO, m.getId(), List.of(caja.getAmbito().getId()), detalle);
        return m;
    }

    /** Solo los perfiles de un local; el ámbito sale de la sesión. */
    private Sesion exigirCaja() {
        return acceso.exigirPerfil(Perfil.dueno_local, Perfil.empleado_local);
    }

    private Ambito local(Sesion sesion) {
        Ambito a = ambitos.findById(sesion.ambitoId()).orElseThrow(() -> new NoEncontradoException("No existe el ámbito."));
        if (a.getTipo() != TipoAmbito.local) {
            throw new AccesoDenegadoException();
        }
        return a;
    }

    private SesionCajaVista vista(SesionCaja c, Sesion sesion, List<MovimientoCaja> lista) {
        return esEmpleado(sesion) ? SesionCajaEmpleadoResponse.de(c, lista)
                : SesionCajaResponse.de(c, lista, OffsetDateTime.now());
    }

    private boolean delDia(SesionCaja c) {
        OffsetDateTime inicio = inicioDelDia(LocalDate.now(zona));
        return c.estaAbierta() || !c.getAbiertaAt().isBefore(inicio)
                || (c.getCerradaAt() != null && !c.getCerradaAt().isBefore(inicio));
    }

    private Predicate[] filtros(CriteriaBuilder cb, Root<SesionCaja> s, Long ambitoId, LocalDate desde,
            LocalDate hasta) {
        List<Predicate> p = new ArrayList<>();
        p.add(cb.equal(s.get("ambito").get("id"), ambitoId));
        if (desde != null) {
            p.add(cb.greaterThanOrEqualTo(s.get("abiertaAt"), inicioDelDia(desde)));
        }
        if (hasta != null) {
            p.add(cb.lessThan(s.get("abiertaAt"), inicioDelDia(hasta.plusDays(1))));
        }
        return p.toArray(Predicate[]::new);
    }

    private OffsetDateTime inicioDelDia(LocalDate dia) {
        return dia.atStartOfDay(zona).toOffsetDateTime();
    }

    private static boolean esEmpleado(Sesion sesion) {
        return sesion.perfil() == Perfil.empleado_local;
    }

    private static BigDecimal centavos(BigDecimal valor) {
        return valor.setScale(2, RoundingMode.HALF_UP);
    }

    private static String texto(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
