package com.greenfire.stock;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import com.greenfire.catalogo.UnidadMedida;
import com.greenfire.comun.NoEncontradoException;
import com.greenfire.comun.ReglaNegocioException;
import com.greenfire.seguridad.Sesion;
import com.greenfire.stock.dto.AbrirConteoRequest;
import com.greenfire.stock.dto.CargaConteoRequest;
import com.greenfire.stock.dto.ConteoResponse;
import com.greenfire.stock.dto.ConteoResumenResponse;
import com.greenfire.usuarios.UsuarioRepository;

/**
 * Conteos físicos. Los abren, cargan, cierran y anulan el dueño de fábrica (en la fábrica) y el dueño de local (en
 * su local); el empleado y el administrador técnico no participan. Nadie ve los conteos de otro ámbito.
 * <p>
 * Al cerrar se bloquean las filas de stock de los artículos contados (en el orden de {@link RegistroStock}), se
 * toma la cantidad del sistema en ese momento y cada diferencia se registra como movimiento de tipo conteo. Todo
 * pasa en una transacción: si algo falla, no se aplica nada. La carga inicial del stock es un conteo contra cero.
 */
@Service
@Transactional
public class ConteoService {

    static final String ENTIDAD = "conteo_stock";
    private static final String NO_EXISTE = "No existe el conteo indicado.";

    private final ConteoStockRepository conteos;
    private final DetalleConteoRepository detalles;
    private final UbicacionService ubicaciones;
    private final ArticuloRepository articulos;
    private final UsuarioRepository usuarios;
    private final RegistroStock registro;
    private final Auditor auditor;
    private final ZoneId zona;

    public ConteoService(ConteoStockRepository conteos, DetalleConteoRepository detalles,
            UbicacionService ubicaciones, ArticuloRepository articulos, UsuarioRepository usuarios,
            RegistroStock registro, Auditor auditor, @Value("${greenfire.zona-horaria}") String zona) {
        this.conteos = conteos;
        this.detalles = detalles;
        this.ubicaciones = ubicaciones;
        this.articulos = articulos;
        this.usuarios = usuarios;
        this.registro = registro;
        this.auditor = auditor;
        this.zona = ZoneId.of(zona);
    }

    // --- Consulta ---

    @Transactional(readOnly = true)
    public List<ConteoResumenResponse> listar(Long ubicacionId, EstadoConteo estado) {
        Ambito ambito = ubicaciones.ambitoDe(ubicaciones.exigirDueno());
        return conteos.listar(ambito.getId(), ubicacionId, estado).stream().map(ConteoResumenResponse::de).toList();
    }

    @Transactional(readOnly = true)
    public ConteoResponse obtener(Long id) {
        Ambito ambito = ubicaciones.ambitoDe(ubicaciones.exigirDueno());
        return respuesta(buscar(id, ambito));
    }

    // --- Operaciones ---

    public ConteoResponse abrir(AbrirConteoRequest req) {
        Sesion sesion = ubicaciones.exigirDueno();
        Ambito ambito = ubicaciones.ambitoDe(sesion);
        UbicacionStock u = ubicaciones.buscarActiva(req.ubicacionId(), ambito);
        if (conteos.existsByUbicacionIdAndEstado(u.getId(), EstadoConteo.abierto)) {
            throw new ReglaNegocioException("Ya hay un conteo abierto en la ubicación '" + u.getNombre() + "'.");
        }
        ConteoStock c = conteos.save(new ConteoStock(u, usuarios.getReferenceById(sesion.usuarioId()),
                LocalDate.now(zona), req.observaciones()));

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("ubicacionId", u.getId());
        detalle.put("observaciones", req.observaciones());
        auditor.registrar("abrir", ENTIDAD, c.getId(), List.of(ambito.getId()), detalle);
        return respuesta(c);
    }

    /** Agrega o reemplaza las cantidades que trae; una cantidad null saca el artículo del conteo. */
    public ConteoResponse cargar(Long id, CargaConteoRequest req) {
        Ambito ambito = ubicaciones.ambitoDe(ubicaciones.exigirDueno());
        ConteoStock c = bloquearAbierto(id, ambito);

        List<Map<String, Object>> cambios = new ArrayList<>();
        for (CargaConteoRequest.Item item : req.items()) {
            Articulo a = articuloVisible(item.articuloId(), ambito);
            BigDecimal nueva = item.cantidadContada() == null ? null : item.cantidadContada().setScale(3);
            if (nueva != null && esUnidad(a) && nueva.stripTrailingZeros().scale() > 0) {
                throw new ReglaNegocioException("'" + a.getNombre() + "' se mide en unidades: la cantidad debe ser entera.");
            }
            DetalleConteo d = detalles.findByConteoIdAndArticuloId(id, a.getId()).orElse(null);
            BigDecimal anterior = d == null ? null : d.getCantidadContada();
            if (anterior == null ? nueva == null : nueva != null && anterior.compareTo(nueva) == 0) {
                continue;
            }
            if (nueva == null) {
                detalles.delete(d);
            } else {
                if (d == null) {
                    d = new DetalleConteo(c, a);
                }
                d.setCantidadContada(nueva);
                detalles.save(d);
            }
            Map<String, Object> cambio = new LinkedHashMap<>();
            cambio.put("articuloId", a.getId());
            cambio.put("antes", anterior);
            cambio.put("despues", nueva);
            cambios.add(cambio);
        }
        if (!cambios.isEmpty()) {
            auditor.registrar("cargar", ENTIDAD, id, List.of(ambito.getId()), Map.of("items", cambios));
        }
        detalles.flush();
        return respuesta(c);
    }

    /**
     * Toma la cantidad del sistema de cada artículo contado, con su fila de stock bloqueada, y registra cada
     * diferencia como movimiento de tipo conteo. Un artículo sin fila de stock cuenta contra cero (carga inicial).
     */
    public ConteoResponse cerrar(Long id) {
        Sesion sesion = ubicaciones.exigirDueno();
        Ambito ambito = ubicaciones.ambitoDe(sesion);
        ConteoStock c = bloquearAbierto(id, ambito);
        List<DetalleConteo> items = detalles.detalle(id);
        if (items.isEmpty()) {
            throw new ReglaNegocioException("El conteo no tiene artículos contados.");
        }

        Map<Long, BigDecimal> sistema = registro.bloquearCantidades(c.getUbicacion(),
                items.stream().map(DetalleConteo::getArticulo).toList());
        List<RegistroStock.Renglon> renglones = new ArrayList<>();
        for (DetalleConteo d : items) {
            BigDecimal diferencia = d.getCantidadContada().subtract(sistema.get(d.getArticulo().getId()));
            if (diferencia.signum() != 0) {
                renglones.add(new RegistroStock.Renglon(d.getArticulo(), c.getUbicacion(), diferencia));
            }
        }
        Map<Long, Long> movimientoPorArticulo = new LinkedHashMap<>();
        if (!renglones.isEmpty()) {
            registro.registrarConteo(c, renglones, "Conteo " + c.getId(), sesion.usuarioId())
                    .forEach(m -> movimientoPorArticulo.put(m.getArticulo().getId(), m.getId()));
        }
        // primero el detalle y después el estado: la base no deja tocar el detalle de un conteo cerrado
        items.forEach(d -> d.setCantidadSistema(sistema.get(d.getArticulo().getId())));
        detalles.flush();
        c.cerrar();
        conteos.flush();

        List<Map<String, Object>> diferencias = new ArrayList<>();
        for (DetalleConteo d : items) {
            if (d.diferencia().signum() != 0) {
                Map<String, Object> dif = new LinkedHashMap<>();
                dif.put("articuloId", d.getArticulo().getId());
                dif.put("cantidadSistema", d.getCantidadSistema());
                dif.put("cantidadContada", d.getCantidadContada());
                dif.put("diferencia", d.diferencia());
                dif.put("movimientoId", movimientoPorArticulo.get(d.getArticulo().getId()));
                diferencias.add(dif);
            }
        }
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("articulosContados", items.size());
        detalle.put("diferencias", diferencias);
        auditor.registrar("cerrar", ENTIDAD, id, List.of(ambito.getId()), detalle);
        return respuesta(c);
    }

    /** Solo se anula un conteo abierto. No genera movimientos. */
    public ConteoResponse anular(Long id) {
        Ambito ambito = ubicaciones.ambitoDe(ubicaciones.exigirDueno());
        ConteoStock c = bloquearAbierto(id, ambito);
        c.anular();
        conteos.flush();
        auditor.registrar("anular", ENTIDAD, id, List.of(ambito.getId()),
                Map.of("estado", Map.of("antes", EstadoConteo.abierto, "despues", EstadoConteo.anulado)));
        return respuesta(c);
    }

    // --- Reglas ---

    /** Un conteo de otro ámbito responde igual que uno inexistente. */
    private ConteoStock buscar(Long id, Ambito ambito) {
        return conteos.findById(id).filter(c -> c.getUbicacion().esDe(ambito))
                .orElseThrow(() -> new NoEncontradoException(NO_EXISTE));
    }

    /** Bloquea el conteo y exige que siga abierto: un cerrado o anulado no se modifica. */
    private ConteoStock bloquearAbierto(Long id, Ambito ambito) {
        ConteoStock c = conteos.bloquear(id).filter(x -> x.getUbicacion().esDe(ambito))
                .orElseThrow(() -> new NoEncontradoException(NO_EXISTE));
        if (!c.abierto()) {
            throw new ReglaNegocioException("El conteo está " + c.getEstado() + ": no se puede modificar.");
        }
        return c;
    }

    /** Solo se cuentan los artículos que el ámbito ve; los demás responden 404. */
    private Articulo articuloVisible(Long id, Ambito ambito) {
        return articulos.findById(id).filter(a -> a.visiblePara(ambito))
                .orElseThrow(() -> new NoEncontradoException("No existe el artículo indicado."));
    }

    private static boolean esUnidad(Articulo a) {
        return a.getUnidadMedida() == UnidadMedida.unidad;
    }

    private ConteoResponse respuesta(ConteoStock c) {
        return new ConteoResponse(ConteoResumenResponse.de(c),
                detalles.detalle(c.getId()).stream().map(ConteoResponse.Item::de).toList());
    }
}
