package com.greenfire.entregas;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greenfire.ambitos.Ambito;
import com.greenfire.ambitos.AmbitoRepository;
import com.greenfire.ambitos.TipoAmbito;
import com.greenfire.auditoria.Auditor;
import com.greenfire.catalogo.Articulo;
import com.greenfire.catalogo.ArticuloRepository;
import com.greenfire.catalogo.CostoArticulo;
import com.greenfire.catalogo.CostoArticuloRepository;
import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.catalogo.UnidadMedida;
import com.greenfire.comun.NoEncontradoException;
import com.greenfire.comun.ReglaNegocioException;
import com.greenfire.entregas.dto.EntregaLocalResponse;
import com.greenfire.entregas.dto.EntregaResponse;
import com.greenfire.entregas.dto.EntregaVista;
import com.greenfire.entregas.dto.EnviarEntregaRequest;
import com.greenfire.entregas.dto.RecepcionEmpleadoResponse;
import com.greenfire.entregas.dto.RecepcionRequest;
import com.greenfire.entregas.dto.ResolverDiferenciaRequest;
import com.greenfire.precios.ListaPrecios;
import com.greenfire.precios.ListaPreciosItem;
import com.greenfire.precios.ListaPreciosItemRepository;
import com.greenfire.precios.ListaPreciosRepository;
import com.greenfire.seguridad.Acceso;
import com.greenfire.seguridad.Perfil;
import com.greenfire.seguridad.Sesion;
import com.greenfire.stock.MovimientoStock;
import com.greenfire.stock.RegistroStock;
import com.greenfire.stock.TipoMovimientoStock;
import com.greenfire.stock.UbicacionService;
import com.greenfire.stock.UbicacionStock;
import com.greenfire.usuarios.UsuarioRepository;

/**
 * Entregas de la fábrica a los locales: una venta, con precio guardado en cada renglón. Se construyen en la etapa
 * 2 pero no se usan en producción hasta la etapa 3, cuando generen la cuenta corriente.
 * <p>
 * El dueño de fábrica envía, anula y resuelve diferencias; el dueño y el empleado del local destino reciben. La
 * fábrica ve solo las que envió y nada del stock del local; el local ve solo las que recibe y nada del stock de la
 * fábrica; el empleado no ve precios. Una entrega de otro ámbito responde 404.
 * <p>
 * Cada operación bloquea la entrega primero (dos recepciones, o una recepción y una anulación, se ordenan) y
 * después las filas de stock, en el orden de {@link RegistroStock}. Todo pasa en una transacción.
 * <p>
 * Cada paso se audita con la fábrica y con el local como ámbitos afectados, en dos registros: el del ámbito que
 * actúa puede llevar datos internos suyos (sus ubicaciones, sus movimientos, sus costos), y el del otro solo lo que
 * los dos pueden ver, porque quien actúa también ve ese registro.
 */
@Service
@Transactional
public class EntregaService {

    static final String ENTIDAD = "entrega";
    private static final String NO_EXISTE = "No existe la entrega indicada.";

    private final EntregaRepository entregas;
    private final DetalleEntregaRepository detalles;
    private final AmbitoRepository ambitos;
    private final ArticuloRepository articulos;
    private final ListaPreciosRepository listas;
    private final ListaPreciosItemRepository precios;
    private final CostoArticuloRepository costos;
    private final UbicacionService ubicaciones;
    private final UsuarioRepository usuarios;
    private final RegistroStock registro;
    private final Acceso acceso;
    private final Auditor auditor;

    public EntregaService(EntregaRepository entregas, DetalleEntregaRepository detalles, AmbitoRepository ambitos,
            ArticuloRepository articulos, ListaPreciosRepository listas, ListaPreciosItemRepository precios,
            CostoArticuloRepository costos, UbicacionService ubicaciones, UsuarioRepository usuarios,
            RegistroStock registro, Acceso acceso, Auditor auditor) {
        this.entregas = entregas;
        this.detalles = detalles;
        this.ambitos = ambitos;
        this.articulos = articulos;
        this.listas = listas;
        this.precios = precios;
        this.costos = costos;
        this.ubicaciones = ubicaciones;
        this.usuarios = usuarios;
        this.registro = registro;
        this.acceso = acceso;
        this.auditor = auditor;
    }

    // --- Consulta ---

    @Transactional(readOnly = true)
    public List<EntregaVista> listar(EstadoEntrega estado) {
        Sesion sesion = exigirParticipante();
        boolean fabrica = sesion.perfil() == Perfil.dueno_fabrica;
        List<Entrega> lista = fabrica ? entregas.listar(sesion.ambitoId(), null, estado)
                : entregas.listar(null, sesion.ambitoId(), estado);
        Map<Long, List<DetalleEntrega>> porEntrega = lista.isEmpty() ? Map.of()
                : detalles.detalleDe(lista.stream().map(Entrega::getId).toList()).stream()
                        .collect(Collectors.groupingBy(d -> d.getEntrega().getId()));
        return lista.stream().map(e -> vista(sesion, e, porEntrega.getOrDefault(e.getId(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public EntregaVista obtener(Long id) {
        Sesion sesion = exigirParticipante();
        Entrega e = entregas.findById(id).filter(x -> participa(sesion, x))
                .orElseThrow(() -> new NoEncontradoException(NO_EXISTE));
        return vista(sesion, e, detalles.detalle(id));
    }

    // --- Fábrica ---

    /**
     * Envía productos terminados de la fábrica a un local, con el precio de la lista indicada. El stock sale de la
     * ubicación de origen: si en un renglón no alcanza, no se registra nada.
     */
    public EntregaResponse enviar(EnviarEntregaRequest req) {
        Sesion sesion = acceso.exigirPerfil(Perfil.dueno_fabrica);
        Ambito fabrica = ubicaciones.ambitoDe(sesion);
        Ambito local = ambitos.findById(req.localId()).filter(a -> a.getTipo() == TipoAmbito.local)
                .orElseThrow(() -> new NoEncontradoException("No existe el local indicado."));
        if (!local.isActivo()) {
            throw new ReglaNegocioException("El local '" + local.getNombre() + "' está desactivado.");
        }
        UbicacionStock origen = ubicaciones.buscarActiva(req.ubicacionOrigenId(), fabrica);
        ListaPrecios lista = listas.findById(req.listaPreciosId())
                .filter(l -> l.getAmbito().getId().equals(fabrica.getId()))
                .orElseThrow(() -> new NoEncontradoException("No existe la lista de precios indicada."));
        if (!lista.isActivo()) {
            throw new ReglaNegocioException("La lista '" + lista.getNombre() + "' está desactivada.");
        }

        record Linea(Articulo articulo, EnviarEntregaRequest.Renglon pedido, BigDecimal precio, BigDecimal subtotal) {
        }
        List<Linea> lineas = new ArrayList<>();
        Set<List<Object>> vistos = new HashSet<>();
        for (EnviarEntregaRequest.Renglon r : req.renglones()) {
            Articulo a = articulos.findById(r.articuloId()).filter(x -> x.visiblePara(fabrica))
                    .orElseThrow(() -> new NoEncontradoException("No existe el artículo indicado."));
            if (a.getTipo() != TipoArticulo.producto_terminado || !a.isActivo()) {
                throw new ReglaNegocioException("'" + a.getNombre()
                        + "' no se puede entregar: solo se entregan productos terminados activos de la fábrica.");
            }
            if (!vistos.add(Arrays.asList(a.getId(), r.loteId()))) {
                throw new ReglaNegocioException("'" + a.getNombre() + "' está repetido en la entrega.");
            }
            BigDecimal precio = precios.findByListaIdAndArticuloId(lista.getId(), a.getId())
                    .map(ListaPreciosItem::getPrecio)
                    .orElseThrow(() -> new ReglaNegocioException("'" + a.getNombre() + "' no tiene precio en la lista '"
                            + lista.getNombre() + "'."));
            BigDecimal cantidad = r.cantidad().setScale(3);
            validarCantidad(a, cantidad);
            lineas.add(new Linea(a, r, precio, cantidad.multiply(precio).setScale(2, RoundingMode.HALF_UP)));
        }
        BigDecimal total = lineas.stream().map(Linea::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);

        Entrega e = entregas.save(new Entrega(fabrica, local, origen, lista,
                usuarios.getReferenceById(sesion.usuarioId()), total, req.observaciones()));
        List<DetalleEntrega> renglones = new ArrayList<>();
        for (Linea l : lineas) {
            renglones.add(detalles.save(new DetalleEntrega(e, l.articulo(), l.pedido().loteId(),
                    l.pedido().cantidad().setScale(3), l.precio(), l.subtotal())));
        }
        detalles.flush();
        List<MovimientoStock> salidas = registro.registrarEntrega(TipoMovimientoStock.entrega_salida, e.getId(),
                renglones.stream().map(d -> new RegistroStock.Renglon(d.getArticulo(), origen,
                        d.getCantidadEnviada().negate(), d.getLoteId())).toList(),
                "Entrega " + e.getId(), sesion.usuarioId());

        Map<String, Object> comun = new LinkedHashMap<>();
        comun.put("localId", local.getId());
        comun.put("total", total);
        comun.put("renglones", renglones.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("articuloId", d.getArticulo().getId());
            m.put("loteId", d.getLoteId());
            m.put("cantidad", d.getCantidadEnviada());
            m.put("precioUnitario", d.getPrecioUnitario());
            m.put("subtotal", d.getSubtotal());
            return m;
        }).toList());
        Map<String, Object> deLaFabrica = new LinkedHashMap<>(comun);
        deLaFabrica.put("ubicacionOrigenId", origen.getId());
        deLaFabrica.put("listaPreciosId", lista.getId());
        deLaFabrica.put("movimientos", ids(salidas));
        auditar("enviar", e, deLaFabrica, comun);
        return EntregaResponse.de(e, renglones);
    }

    /** Solo una entrega enviada, antes de la recepción. El stock vuelve a la ubicación de origen. */
    public EntregaResponse anular(Long id) {
        Sesion sesion = acceso.exigirPerfil(Perfil.dueno_fabrica);
        Entrega e = bloquear(id, sesion);
        exigirEnviada(e);
        List<DetalleEntrega> renglones = detalles.detalle(id);
        e.anular(usuarios.getReferenceById(sesion.usuarioId()));
        entregas.flush();
        List<MovimientoStock> devueltos = registro.registrarEntrega(TipoMovimientoStock.anulacion, id,
                renglones.stream().map(d -> new RegistroStock.Renglon(d.getArticulo(), e.getUbicacionOrigen(),
                        d.getCantidadEnviada(), d.getLoteId())).toList(),
                "Anulación de la entrega " + id, sesion.usuarioId());

        Map<String, Object> comun = new LinkedHashMap<>();
        comun.put("estado", Map.of("antes", EstadoEntrega.enviada, "despues", EstadoEntrega.anulada));
        Map<String, Object> deLaFabrica = new LinkedHashMap<>(comun);
        deLaFabrica.put("movimientos", ids(devueltos));
        auditar("anular", e, deLaFabrica, comun);
        return EntregaResponse.de(e, renglones);
    }

    /**
     * Resuelve el faltante de una entrega con diferencia informada, una sola vez: lo reingresa al stock de la
     * fábrica, en la ubicación de origen, o lo da por perdido. El motivo es obligatorio en los dos casos.
     */
    public EntregaResponse resolverDiferencia(Long id, ResolverDiferenciaRequest req) {
        Sesion sesion = acceso.exigirPerfil(Perfil.dueno_fabrica);
        Entrega e = bloquear(id, sesion);
        if (!e.diferenciaPendiente()) {
            throw new ReglaNegocioException(e.getEstado() == EstadoEntrega.diferencia_informada
                    ? "La diferencia de la entrega ya está resuelta."
                    : "La entrega está " + e.getEstado() + ": no tiene una diferencia para resolver.");
        }
        List<DetalleEntrega> renglones = detalles.detalle(id);
        String motivo = req.motivo().trim();
        e.resolverDiferencia(req.forma(), motivo, usuarios.getReferenceById(sesion.usuarioId()));
        entregas.flush();
        List<DetalleEntrega> conFaltante = renglones.stream().filter(d -> d.faltante().signum() > 0).toList();
        List<MovimientoStock> reingresos = req.forma() == ResolucionDiferencia.reingreso
                ? registro.registrarEntrega(TipoMovimientoStock.devolucion, id,
                        conFaltante.stream().map(d -> new RegistroStock.Renglon(d.getArticulo(),
                                e.getUbicacionOrigen(), d.faltante(), d.getLoteId())).toList(),
                        motivo, sesion.usuarioId())
                : List.of();

        Map<String, Object> comun = new LinkedHashMap<>();
        comun.put("forma", req.forma());
        comun.put("motivo", motivo);
        comun.put("faltantes", conFaltante.stream().map(d -> Map.of("articuloId", d.getArticulo().getId(),
                "cantidad", d.faltante())).toList());
        Map<String, Object> deLaFabrica = new LinkedHashMap<>(comun);
        deLaFabrica.put("movimientos", ids(reingresos));
        auditar("resolver_diferencia", e, deLaFabrica, comun);
        return EntregaResponse.de(e, renglones);
    }

    // --- Local ---

    /**
     * Registra la recepción en el local destino, una sola vez: la ubicación de destino y la cantidad recibida de
     * cada renglón, que nunca supera la enviada. Entra el stock recibido; si falta algo, la entrega queda con
     * diferencia informada. El costo del artículo en el local pasa a ser el precio de la entrega.
     */
    public EntregaVista recibir(Long id, RecepcionRequest req) {
        Sesion sesion = acceso.exigirPerfil(Perfil.dueno_local, Perfil.empleado_local);
        Entrega e = bloquear(id, sesion);
        exigirEnviada(e);
        Ambito local = e.getDestino();
        UbicacionStock destino = ubicaciones.buscarActiva(req.ubicacionId(), local);

        List<DetalleEntrega> renglones = detalles.detalle(id);
        Map<Long, DetalleEntrega> porId = renglones.stream()
                .collect(Collectors.toMap(DetalleEntrega::getId, Function.identity()));
        Map<Long, BigDecimal> recibido = new HashMap<>();
        for (RecepcionRequest.Renglon r : req.renglones()) {
            DetalleEntrega d = porId.get(r.renglonId());
            if (d == null) {
                throw new ReglaNegocioException("El renglón " + r.renglonId() + " no es de esta entrega.");
            }
            if (recibido.put(d.getId(), r.cantidadRecibida().setScale(3)) != null) {
                throw new ReglaNegocioException("El renglón " + r.renglonId() + " está repetido.");
            }
        }
        for (DetalleEntrega d : renglones) {
            BigDecimal cantidad = recibido.get(d.getId());
            if (cantidad == null) {
                throw new ReglaNegocioException("Falta la cantidad recibida de '" + d.getArticulo().getNombre() + "'.");
            }
            if (cantidad.compareTo(d.getCantidadEnviada()) > 0) {
                throw new ReglaNegocioException("De '" + d.getArticulo().getNombre() + "' se enviaron "
                        + texto(d.getCantidadEnviada()) + ": no se pueden recibir " + texto(cantidad) + ".");
            }
            if (cantidad.signum() > 0) {
                validarCantidad(d.getArticulo(), cantidad);
            }
        }

        // primero el detalle y después el estado: la base solo deja cargar el detalle de una entrega enviada
        renglones.forEach(d -> d.setCantidadRecibida(recibido.get(d.getId())));
        detalles.flush();
        boolean conFaltante = renglones.stream().anyMatch(d -> d.faltante().signum() > 0);
        e.recibir(destino, usuarios.getReferenceById(sesion.usuarioId()), conFaltante, req.observaciones());
        entregas.flush();
        List<DetalleEntrega> conRecibido = renglones.stream().filter(d -> d.getCantidadRecibida().signum() > 0)
                .toList();
        List<MovimientoStock> entradas = conRecibido.isEmpty() ? List.of()
                : registro.registrarEntrega(TipoMovimientoStock.entrega_recepcion, id,
                        conRecibido.stream().map(d -> new RegistroStock.Renglon(d.getArticulo(), destino,
                                d.getCantidadRecibida(), d.getLoteId())).toList(),
                        "Recepción de la entrega " + id, sesion.usuarioId());
        List<Map<String, Object>> cambiosDeCosto = actualizarCostos(local, conRecibido);

        Map<String, Object> comun = new LinkedHashMap<>();
        comun.put("estado", Map.of("antes", EstadoEntrega.enviada, "despues", e.getEstado()));
        comun.put("renglones", renglones.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("articuloId", d.getArticulo().getId());
            m.put("cantidadEnviada", d.getCantidadEnviada());
            m.put("cantidadRecibida", d.getCantidadRecibida());
            return m;
        }).toList());
        comun.put("observaciones", req.observaciones());
        Map<String, Object> delLocal = new LinkedHashMap<>(comun);
        delLocal.put("ubicacionDestinoId", destino.getId());
        delLocal.put("movimientos", ids(entradas));
        delLocal.put("costos", cambiosDeCosto);
        auditar("recibir", e, comun, delLocal);
        return vista(sesion, e, renglones);
    }

    /**
     * El costo del producto en el local pasa a ser el precio unitario de la entrega, tal cual. Pendiente para la
     * etapa 3: el tratamiento del IVA en ese costo depende de la condición fiscal de la fábrica y del local, y lo
     * tiene que confirmar el contador (ver "Para revisar en la etapa 3" en docs/plan-etapa-2.md).
     */
    private List<Map<String, Object>> actualizarCostos(Ambito local, List<DetalleEntrega> recibidos) {
        List<Map<String, Object>> cambios = new ArrayList<>();
        for (DetalleEntrega d : recibidos) {
            Articulo a = d.getArticulo();
            CostoArticulo costo = costos.findByArticuloIdAndAmbitoId(a.getId(), local.getId())
                    .orElseGet(() -> new CostoArticulo(a, local));
            BigDecimal antes = costo.getCostoActual();
            if (antes != null && antes.compareTo(d.getPrecioUnitario()) == 0) {
                continue;
            }
            costo.setCostoActual(d.getPrecioUnitario());
            costos.save(costo);
            Map<String, Object> cambio = new LinkedHashMap<>();
            cambio.put("articuloId", a.getId());
            cambio.put("antes", antes);
            cambio.put("despues", d.getPrecioUnitario());
            cambios.add(cambio);
        }
        return cambios;
    }

    // --- Reglas ---

    private Sesion exigirParticipante() {
        return acceso.exigirPerfil(Perfil.dueno_fabrica, Perfil.dueno_local, Perfil.empleado_local);
    }

    /** La fábrica participa de las que envió; un local, de las que recibe. */
    private static boolean participa(Sesion sesion, Entrega e) {
        Ambito lado = sesion.perfil() == Perfil.dueno_fabrica ? e.getOrigen() : e.getDestino();
        return lado.getId().equals(sesion.ambitoId());
    }

    /** Bloquea la entrega; una de otro ámbito responde igual que una inexistente. */
    private Entrega bloquear(Long id, Sesion sesion) {
        return entregas.bloquear(id).filter(e -> participa(sesion, e))
                .orElseThrow(() -> new NoEncontradoException(NO_EXISTE));
    }

    private static void exigirEnviada(Entrega e) {
        if (!e.enviada()) {
            throw new ReglaNegocioException("La entrega está " + e.getEstado() + ": ya no se puede recibir ni anular.");
        }
    }

    private static void validarCantidad(Articulo a, BigDecimal cantidad) {
        if (a.getUnidadMedida() == UnidadMedida.unidad && cantidad.stripTrailingZeros().scale() > 0) {
            throw new ReglaNegocioException("'" + a.getNombre() + "' se mide en unidades: la cantidad debe ser entera.");
        }
    }

    private static EntregaVista vista(Sesion sesion, Entrega e, List<DetalleEntrega> detalle) {
        return switch (sesion.perfil()) {
            case dueno_fabrica -> EntregaResponse.de(e, detalle);
            case dueno_local -> EntregaLocalResponse.de(e, detalle);
            case empleado_local -> RecepcionEmpleadoResponse.de(e, detalle);
        };
    }

    /**
     * Un registro por ámbito afectado. Quien actúa ve los dos registros (el suyo, y el del otro porque es el ámbito
     * actuante); el otro ámbito ve solo el suyo. Por eso el detalle con datos internos va en el registro del ámbito
     * que actúa, y el del otro lleva solo lo que los dos pueden ver.
     */
    private void auditar(String accion, Entrega e, Map<String, Object> deLaFabrica, Map<String, Object> delLocal) {
        auditor.registrar(accion, ENTIDAD, e.getId(), List.of(e.getOrigen().getId()), deLaFabrica);
        auditor.registrar(accion, ENTIDAD, e.getId(), List.of(e.getDestino().getId()), delLocal);
    }

    private static List<Long> ids(List<MovimientoStock> movimientos) {
        return movimientos.stream().map(MovimientoStock::getId).filter(Objects::nonNull).toList();
    }

    private static String texto(BigDecimal cantidad) {
        return cantidad.stripTrailingZeros().toPlainString();
    }
}
