package com.greenfire.stock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greenfire.ambitos.Ambito;
import com.greenfire.auditoria.Auditor;
import com.greenfire.catalogo.Articulo;
import com.greenfire.catalogo.ArticuloRepository;
import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.comun.Cambios;
import com.greenfire.comun.NoEncontradoException;
import com.greenfire.seguridad.Acceso;
import com.greenfire.seguridad.Perfil;
import com.greenfire.seguridad.Sesion;
import com.greenfire.stock.dto.AjusteRequest;
import com.greenfire.stock.dto.AjusteResponse;
import com.greenfire.stock.dto.LimitesRequest;
import com.greenfire.stock.dto.MovimientoResponse;
import com.greenfire.stock.dto.PaginaMovimientos;
import com.greenfire.stock.dto.StockResponse;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * Stock del ámbito de la sesión: consulta, mínimos y máximos, ajustes manuales y movimientos. Los dueños operan
 * el stock de su ámbito; el empleado de local consulta el stock y los movimientos de su local, sin costos (el stock
 * no tiene costos) y no hace ajustes. Nadie ve el stock de otro ámbito.
 */
@Service
@Transactional
public class StockService {

    public static final int TAMANO_MAXIMO = 200;
    private static final String NO_EXISTE_ARTICULO = "No existe el artículo indicado.";

    private final UbicacionService ubicaciones;
    private final StockArticuloRepository stock;
    private final ArticuloRepository articulos;
    private final RegistroStock registro;
    private final EntityManager em;
    private final Acceso acceso;
    private final Auditor auditor;
    private final ZoneId zona;

    public StockService(UbicacionService ubicaciones, StockArticuloRepository stock, ArticuloRepository articulos,
            RegistroStock registro, EntityManager em, Acceso acceso, Auditor auditor,
            @Value("${greenfire.zona-horaria}") String zona) {
        this.ubicaciones = ubicaciones;
        this.stock = stock;
        this.articulos = articulos;
        this.registro = registro;
        this.em = em;
        this.acceso = acceso;
        this.auditor = auditor;
        this.zona = ZoneId.of(zona);
    }

    // --- Consulta ---

    /** El empleado ve solo los artículos activos y las ubicaciones activas. */
    @Transactional(readOnly = true)
    public List<StockResponse> consultar(Long ubicacionId, Long articuloId, TipoArticulo tipo, boolean bajoMinimo) {
        Sesion sesion = acceso.exigirAmbito();
        boolean soloActivos = sesion.perfil() == Perfil.empleado_local;
        return buscarStock(sesion.ambitoId(), ubicacionId, articuloId, tipo, bajoMinimo, soloActivos);
    }

    /** Artículos activos por debajo del mínimo, en las ubicaciones activas del ámbito. */
    @Transactional(readOnly = true)
    public List<StockResponse> alertas() {
        Sesion sesion = acceso.exigirAmbito();
        return buscarStock(sesion.ambitoId(), null, null, null, true, true);
    }

    @Transactional(readOnly = true)
    public PaginaMovimientos movimientos(Long articuloId, Long ubicacionId, TipoMovimientoStock tipo,
            LocalDate desde, LocalDate hasta, int pagina, int tamano) {
        Sesion sesion = acceso.exigirAmbito();
        int tamanoReal = Math.clamp(tamano, 1, TAMANO_MAXIMO);
        int paginaReal = Math.max(pagina, 0);
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<MovimientoStock> consulta = cb.createQuery(MovimientoStock.class);
        Root<MovimientoStock> m = consulta.from(MovimientoStock.class);
        m.fetch("articulo");
        m.fetch("ubicacion");
        m.fetch("usuario");
        consulta.where(filtros(cb, m, sesion.ambitoId(), articuloId, ubicacionId, tipo, desde, hasta))
                .orderBy(cb.desc(m.get("fechaHora")), cb.desc(m.get("id")));
        List<MovimientoResponse> lista = em.createQuery(consulta)
                .setFirstResult(paginaReal * tamanoReal)
                .setMaxResults(tamanoReal)
                .getResultStream().map(MovimientoResponse::de).toList();

        CriteriaQuery<Long> conteo = cb.createQuery(Long.class);
        Root<MovimientoStock> c = conteo.from(MovimientoStock.class);
        conteo.select(cb.count(c)).where(filtros(cb, c, sesion.ambitoId(), articuloId, ubicacionId, tipo, desde, hasta));
        return new PaginaMovimientos(lista, paginaReal, tamanoReal, em.createQuery(conteo).getSingleResult());
    }

    // --- Operaciones de los dueños ---

    /** Crea la fila de stock si todavía no existe (con cantidad cero) y fija el mínimo y el máximo. */
    public StockResponse editarLimites(LimitesRequest req) {
        Ambito ambito = ubicaciones.ambitoDe(ubicaciones.exigirDueno());
        UbicacionStock u = ubicaciones.buscarActiva(req.ubicacionId(), ambito);
        Articulo a = articuloVisible(req.articuloId(), ambito);
        StockArticulo s = stock.findByArticuloIdAndUbicacionId(a.getId(), u.getId())
                .orElseGet(() -> stock.save(new StockArticulo(a, u)));

        Cambios c = new Cambios();
        s.setStockMinimo(c.registrar("stockMinimo", escala(s.getStockMinimo()), escala(req.stockMinimo())));
        s.setStockMaximo(c.registrar("stockMaximo", escala(s.getStockMaximo()), escala(req.stockMaximo())));
        auditor.registrarCambios("editar_limites", "stock_articulo", s.getId(), List.of(ambito.getId()), c);
        stock.flush();
        return respuesta(a, u, s.getStockMinimo(), s.getStockMaximo());
    }

    /**
     * Ajuste manual con motivo obligatorio. Todos los renglones se aplican o ninguno. Queda en auditoría, con el
     * ámbito afectado, un registro por ajuste con el motivo y cada movimiento.
     */
    public AjusteResponse ajustar(AjusteRequest req) {
        Sesion sesion = ubicaciones.exigirDueno();
        Ambito ambito = ubicaciones.ambitoDe(sesion);
        List<RegistroStock.Renglon> renglones = req.renglones().stream()
                .map(r -> new RegistroStock.Renglon(articuloVisible(r.articuloId(), ambito),
                        ubicaciones.buscarActiva(r.ubicacionId(), ambito), r.cantidad()))
                .toList();
        String motivo = req.motivo().trim();
        List<MovimientoStock> registrados = registro.registrar(TipoMovimientoStock.ajuste, renglones, motivo,
                req.observaciones(), sesion.usuarioId());

        List<AjusteResponse.Renglon> resultado = new ArrayList<>();
        List<Map<String, Object>> detalleRenglones = new ArrayList<>();
        for (MovimientoStock m : registrados) {
            Long articuloId = m.getArticulo().getId();
            Long ubicacionId = m.getUbicacion().getId();
            resultado.add(new AjusteResponse.Renglon(m.getId(), articuloId, ubicacionId, m.getCantidad(),
                    stock.cantidad(articuloId, ubicacionId).orElseThrow()));
            Map<String, Object> renglon = new LinkedHashMap<>();
            renglon.put("movimientoId", m.getId());
            renglon.put("articuloId", articuloId);
            renglon.put("ubicacionId", ubicacionId);
            renglon.put("cantidad", m.getCantidad());
            detalleRenglones.add(renglon);
        }
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("motivo", motivo);
        detalle.put("observaciones", req.observaciones());
        detalle.put("renglones", detalleRenglones);
        auditor.registrar("ajustar_stock", "movimiento_stock", registrados.get(0).getId(), List.of(ambito.getId()),
                detalle);
        return new AjusteResponse(resultado);
    }

    // --- Reglas ---

    /** Un artículo que el ámbito no ve (la reventa de otro local, los insumos de la fábrica) responde 404. */
    private Articulo articuloVisible(Long id, Ambito ambito) {
        return articulos.findById(id).filter(a -> a.visiblePara(ambito))
                .orElseThrow(() -> new NoEncontradoException(NO_EXISTE_ARTICULO));
    }

    @SuppressWarnings("unchecked")
    private List<StockResponse> buscarStock(Long ambitoId, Long ubicacionId, Long articuloId, TipoArticulo tipo,
            boolean bajoMinimo, boolean soloActivos) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<StockArticulo> consulta = cb.createQuery(StockArticulo.class);
        Root<StockArticulo> s = consulta.from(StockArticulo.class);
        // fetch y join a la vez: trae el artículo y la ubicación en la misma consulta y permite filtrar por ellos
        Join<StockArticulo, Articulo> a = (Join<StockArticulo, Articulo>) s.<StockArticulo, Articulo>fetch("articulo");
        Join<StockArticulo, UbicacionStock> u =
                (Join<StockArticulo, UbicacionStock>) s.<StockArticulo, UbicacionStock>fetch("ubicacion");
        List<Predicate> p = new ArrayList<>();
        p.add(cb.equal(u.get("ambito").get("id"), ambitoId));
        if (ubicacionId != null) {
            p.add(cb.equal(u.get("id"), ubicacionId));
        }
        if (articuloId != null) {
            p.add(cb.equal(a.get("id"), articuloId));
        }
        if (tipo != null) {
            p.add(cb.equal(a.get("tipo"), tipo));
        }
        if (bajoMinimo) {
            p.add(cb.isNotNull(s.get("stockMinimo")));
            p.add(cb.lessThan(s.<BigDecimal>get("cantidadActual"), s.<BigDecimal>get("stockMinimo")));
        }
        if (soloActivos) {
            p.add(cb.isTrue(a.get("activo")));
            p.add(cb.isTrue(u.get("activo")));
        }
        consulta.where(p.toArray(Predicate[]::new)).orderBy(cb.asc(a.get("nombre")), cb.asc(u.get("nombre")));
        return em.createQuery(consulta).getResultStream()
                .map(x -> new StockResponse(x.getArticulo().getId(), x.getArticulo().getNombre(),
                        x.getArticulo().getTipo(), x.getArticulo().getUnidadMedida(), x.getUbicacion().getId(),
                        x.getUbicacion().getNombre(), x.getCantidadActual(), x.getStockMinimo(), x.getStockMaximo(),
                        StockResponse.bajoMinimo(x.getCantidadActual(), x.getStockMinimo())))
                .toList();
    }

    private Predicate[] filtros(CriteriaBuilder cb, Root<MovimientoStock> m, Long ambitoId, Long articuloId,
            Long ubicacionId, TipoMovimientoStock tipo, LocalDate desde, LocalDate hasta) {
        List<Predicate> p = new ArrayList<>();
        p.add(cb.equal(m.get("ubicacion").get("ambito").get("id"), ambitoId));
        if (articuloId != null) {
            p.add(cb.equal(m.get("articulo").get("id"), articuloId));
        }
        if (ubicacionId != null) {
            p.add(cb.equal(m.get("ubicacion").get("id"), ubicacionId));
        }
        if (tipo != null) {
            p.add(cb.equal(m.get("tipo"), tipo));
        }
        if (desde != null) {
            p.add(cb.greaterThanOrEqualTo(m.get("fechaHora"), inicioDelDia(desde)));
        }
        if (hasta != null) {
            p.add(cb.lessThan(m.get("fechaHora"), inicioDelDia(hasta.plusDays(1))));
        }
        return p.toArray(Predicate[]::new);
    }

    /** Lee la cantidad de la base: la entidad recién creada no la tiene, y la mantiene el trigger. */
    private StockResponse respuesta(Articulo a, UbicacionStock u, BigDecimal minimo, BigDecimal maximo) {
        BigDecimal cantidad = stock.cantidad(a.getId(), u.getId()).orElse(BigDecimal.ZERO);
        return new StockResponse(a.getId(), a.getNombre(), a.getTipo(), a.getUnidadMedida(), u.getId(), u.getNombre(),
                cantidad, minimo, maximo, StockResponse.bajoMinimo(cantidad, minimo));
    }

    private static BigDecimal escala(BigDecimal valor) {
        return valor == null ? null : valor.setScale(3);
    }

    private OffsetDateTime inicioDelDia(LocalDate dia) {
        return dia.atStartOfDay(zona).toOffsetDateTime();
    }
}
