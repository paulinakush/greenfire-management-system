package com.greenfire.ventas;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greenfire.ambitos.Ambito;
import com.greenfire.ambitos.AmbitoRepository;
import com.greenfire.ambitos.TipoAmbito;
import com.greenfire.auditoria.Auditor;
import com.greenfire.caja.CajaService;
import com.greenfire.caja.MedioPago;
import com.greenfire.caja.OrigenMovimientoCaja;
import com.greenfire.caja.SesionCaja;
import com.greenfire.catalogo.Articulo;
import com.greenfire.catalogo.ArticuloRepository;
import com.greenfire.catalogo.CostoArticulo;
import com.greenfire.catalogo.CostoArticuloRepository;
import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.catalogo.UnidadMedida;
import com.greenfire.clientes.Cliente;
import com.greenfire.clientes.ClienteRepository;
import com.greenfire.clientes.ClienteService;
import com.greenfire.comprobantes.CalculoComprobante;
import com.greenfire.comprobantes.Comprobante;
import com.greenfire.comprobantes.EmisorComprobantes;
import com.greenfire.comprobantes.EmisorComprobantes.Emision;
import com.greenfire.comun.AccesoDenegadoException;
import com.greenfire.comun.NoEncontradoException;
import com.greenfire.comun.ReglaNegocioException;
import com.greenfire.comun.SolicitudInvalidaException;
import com.greenfire.fiscal.TipoComprobante;
import com.greenfire.precios.ListaPrecios;
import com.greenfire.precios.ListaPreciosItem;
import com.greenfire.precios.ListaPreciosItemRepository;
import com.greenfire.precios.ListaPreciosRepository;
import com.greenfire.seguridad.Acceso;
import com.greenfire.seguridad.Perfil;
import com.greenfire.seguridad.Sesion;
import com.greenfire.stock.RegistroStock;
import com.greenfire.stock.TipoMovimientoStock;
import com.greenfire.stock.UbicacionStock;
import com.greenfire.stock.UbicacionStockRepository;
import com.greenfire.usuarios.UsuarioRepository;
import com.greenfire.ventas.dto.AnulacionVentaRequest;
import com.greenfire.ventas.dto.ComprobanteDeVenta;
import com.greenfire.ventas.dto.DevolucionVentaRequest;
import com.greenfire.ventas.dto.HistorialClienteResponse;
import com.greenfire.ventas.dto.PaginaVentas;
import com.greenfire.ventas.dto.VentaEmpleadoResponse;
import com.greenfire.ventas.dto.VentaRequest;
import com.greenfire.ventas.dto.VentaResponse;
import com.greenfire.ventas.dto.VentaVista;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * Ventas presenciales de los locales y ventas de la fábrica a sus mayoristas. La venta, su comprobante, sus
 * movimientos de stock y su cobro en la caja se registran en una sola transacción: si algo falla, no queda nada (ni
 * el número del comprobante).
 * <p>
 * Permisos: en un local venden el dueño y el empleado; en la fábrica, solo su dueño, siempre a un mayorista. Anulan
 * y devuelven solo los dueños. El empleado ve las ventas del día de su local, sin costos ni márgenes. Una venta de
 * otro ámbito responde 404.
 * <p>
 * Orden de los bloqueos, igual en todas las operaciones: la venta (al anular o devolver), la caja, las filas de stock
 * y, al final, el número del comprobante.
 */
@Service
@Transactional
public class VentaService {

    public static final int MAXIMO_RENGLONES = 100;
    public static final BigDecimal CANTIDAD_MAXIMA = new BigDecimal("10000");
    public static final int TAMANO_MAXIMO = 100;

    static final String NO_EXISTE = "No existe la venta indicada.";
    static final String SIN_CUENTA_CORRIENTE =
            "La venta a cuenta corriente todavía no está disponible: llega con las cuentas corrientes (módulo 7).";
    private static final String ENTIDAD = "venta";
    private static final String PRINCIPAL = "Principal";

    private final VentaRepository ventas;
    private final DetalleVentaRepository detalles;
    private final DevolucionVentaRepository devoluciones;
    private final DetalleDevolucionVentaRepository detallesDevueltos;
    private final AmbitoRepository ambitos;
    private final ClienteRepository clientes;
    private final ClienteService clienteService;
    private final ArticuloRepository articulos;
    private final UbicacionStockRepository ubicaciones;
    private final ListaPreciosRepository listas;
    private final ListaPreciosItemRepository precios;
    private final CostoArticuloRepository costos;
    private final RegistroStock stock;
    private final EmisorComprobantes emisor;
    private final CajaService caja;
    private final UsuarioRepository usuarios;
    private final EntityManager em;
    private final Acceso acceso;
    private final Auditor auditor;
    private final ZoneId zona;

    public VentaService(VentaRepository ventas, DetalleVentaRepository detalles, DevolucionVentaRepository devoluciones,
            DetalleDevolucionVentaRepository detallesDevueltos, AmbitoRepository ambitos, ClienteRepository clientes,
            ClienteService clienteService, ArticuloRepository articulos, UbicacionStockRepository ubicaciones,
            ListaPreciosRepository listas, ListaPreciosItemRepository precios, CostoArticuloRepository costos,
            RegistroStock stock, EmisorComprobantes emisor, CajaService caja, UsuarioRepository usuarios,
            EntityManager em, Acceso acceso, Auditor auditor, @Value("${greenfire.zona-horaria}") String zona) {
        this.ventas = ventas;
        this.detalles = detalles;
        this.devoluciones = devoluciones;
        this.detallesDevueltos = detallesDevueltos;
        this.ambitos = ambitos;
        this.clientes = clientes;
        this.clienteService = clienteService;
        this.articulos = articulos;
        this.ubicaciones = ubicaciones;
        this.listas = listas;
        this.precios = precios;
        this.costos = costos;
        this.stock = stock;
        this.emisor = emisor;
        this.caja = caja;
        this.usuarios = usuarios;
        this.em = em;
        this.acceso = acceso;
        this.auditor = auditor;
        this.zona = ZoneId.of(zona);
    }

    // --- Registrar ---

    public VentaVista registrar(VentaRequest req) {
        Sesion sesion = acceso.exigirAmbito();
        Ambito ambito = ambitos.findById(sesion.ambitoId())
                .orElseThrow(() -> new NoEncontradoException("No existe el ámbito."));
        boolean fabrica = ambito.getTipo() == TipoAmbito.fabrica;
        validarRenglones(req.renglones().stream().map(r -> r.cantidad()).toList());
        if (req.medioPago() == MedioPago.cuenta_corriente) {
            throw new ReglaNegocioException(SIN_CUENTA_CORRIENTE);
        }
        BigDecimal porcentaje = porcentaje(req.porcentajeDescuento());
        if (sesion.perfil() == Perfil.empleado_local && porcentaje.compareTo(ambito.getDescuentoMaximoEmpleado()) > 0) {
            throw new AccesoDenegadoException("El descuento máximo que puede aplicar el empleado en este local es "
                    + texto(ambito.getDescuentoMaximoEmpleado()) + " %.");
        }
        Cliente cliente = req.clienteId() == null ? null : cliente(req.clienteId(), ambito);
        if (fabrica && cliente == null) {
            throw new SolicitudInvalidaException("Una venta de la fábrica es siempre a un cliente mayorista: indicá el cliente.");
        }
        LocalDate fechaCobro = fechaCobro(req.fechaCobro(), fabrica);
        ListaPrecios lista = lista(ambito, cliente, fabrica);
        boolean discrimina = ambito.getEntidadFiscal().getCondicionIva().discriminaIva();

        // artículos, ubicaciones, precios y costos
        Map<Long, Articulo> porId = articulos.findAllById(
                req.renglones().stream().map(VentaRequest.Renglon::articuloId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Articulo::getId, Function.identity()));
        Map<Long, BigDecimal> costoDe = costos.findByAmbitoIdAndArticuloIdIn(ambito.getId(), porId.keySet()).stream()
                .collect(Collectors.toMap(c -> c.getArticulo().getId(), CostoArticulo::getCostoActual));
        UbicacionStock principal = null;
        record Linea(Articulo articulo, UbicacionStock ubicacion, BigDecimal cantidad, CalculoVenta.Linea calculo) {
        }
        List<Linea> lineas = new ArrayList<>();
        Set<List<Long>> vistos = new HashSet<>();
        for (VentaRequest.Renglon r : req.renglones()) {
            Articulo a = porId.get(r.articuloId());
            if (a == null || !a.visiblePara(ambito)) {
                throw new NoEncontradoException("No existe el artículo indicado (" + r.articuloId() + ").");
            }
            validarArticulo(a, r.cantidad());
            UbicacionStock u;
            if (r.ubicacionId() != null) {
                u = ubicacion(r.ubicacionId(), ambito);
            } else {
                principal = principal != null ? principal : principal(ambito);
                u = principal;
            }
            if (!vistos.add(List.of(a.getId(), u.getId()))) {
                throw new SolicitudInvalidaException("'" + a.getNombre() + "' está repetido en la misma ubicación: "
                        + "sumá las cantidades en un solo renglón.");
            }
            BigDecimal precio = precios.findByListaIdAndArticuloId(lista.getId(), a.getId())
                    .map(ListaPreciosItem::getPrecio)
                    .orElseThrow(() -> new ReglaNegocioException("'" + a.getNombre() + "' no tiene precio en la lista '"
                            + lista.getNombre() + "'."));
            lineas.add(new Linea(a, u, r.cantidad(), CalculoVenta.linea(r.cantidad(), precio, lista.isPreciosConIva(),
                    discrimina, a.getAlicuotaIva(), porcentaje)));
        }
        BigDecimal subtotal = lineas.stream().map(l -> l.calculo().subtotalLista()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = lineas.stream().map(l -> l.calculo().subtotal()).reduce(BigDecimal.ZERO, BigDecimal::add);

        // caja, stock y número del comprobante, en ese orden
        SesionCaja sesionCaja = fabrica ? null : caja.exigirAbierta(ambito.getId());
        stock.verificarSalidas(lineas.stream()
                .map(l -> new RegistroStock.Renglon(l.articulo(), l.ubicacion(), l.cantidad())).toList());
        Comprobante comprobante = emisor.emitir(new Emision(ambito, TipoComprobante.comprobante_venta, cliente, true,
                lineas.stream().map(l -> new EmisorComprobantes.Renglon(l.articulo(), null, l.cantidad(),
                        l.calculo().precioUnitario(), l.articulo().getAlicuotaIva())).toList(), sesion.usuarioId()));

        Venta v = ventas.save(new Venta(ambito, usuarios.getReferenceById(sesion.usuarioId()), cliente, sesionCaja,
                req.medioPago(), fechaCobro, subtotal, porcentaje, total, comprobante));
        List<DetalleVenta> renglones = detalles.saveAll(lineas.stream().map(l -> new DetalleVenta(v, l.articulo(),
                l.ubicacion(), l.cantidad(), l.calculo(), l.articulo().getAlicuotaIva(),
                costoDe.get(l.articulo().getId()))).toList());
        em.flush();
        stock.registrarVenta(TipoMovimientoStock.venta, v.getId(), renglones.stream()
                .map(d -> new RegistroStock.Renglon(d.getArticulo(), d.getUbicacion(), d.getCantidad().negate()))
                .toList(), null, sesion.usuarioId());
        if (sesionCaja != null && total.signum() > 0) {
            caja.registrarCobro(sesionCaja, OrigenMovimientoCaja.venta, req.medioPago(), total, v.getId(),
                    sesion.usuarioId(), "Venta " + comprobante.numeroCompleto());
        }

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("comprobante", comprobante.numeroCompleto());
        detalle.put("clienteId", cliente == null ? null : cliente.getId());
        detalle.put("medioPago", v.getMedioPago());
        detalle.put("fechaCobro", fechaCobro);
        detalle.put("subtotal", v.getSubtotal());
        detalle.put("porcentajeDescuento", v.getPorcentajeDescuento());
        detalle.put("total", v.getTotal());
        detalle.put("renglones", renglones.size());
        auditor.registrar("registrar", ENTIDAD, v.getId(), List.of(ambito.getId()), detalle);
        return vista(sesion, v, renglones, List.of(), List.of());
    }

    // --- Anular y devolver ---

    /** Anula una venta confirmada sin devoluciones: vuelve todo el stock, se anula el comprobante y, si se cobró en la caja, sale el dinero de la caja abierta. */
    public VentaResponse anular(Long id, AnulacionVentaRequest req) {
        Sesion sesion = acceso.exigirPerfil(Perfil.dueno_local, Perfil.dueno_fabrica);
        Venta v = ventas.bloquear(id, sesion.ambitoId()).orElseThrow(() -> new NoEncontradoException(NO_EXISTE));
        if (v.getEstado() == EstadoVenta.anulada) {
            throw new ReglaNegocioException("La venta ya está anulada.");
        }
        if (devoluciones.existsByVentaId(id)) {
            throw new ReglaNegocioException(
                    "La venta tiene devoluciones: no se anula. Devolvé lo que queda con otra devolución.");
        }
        String motivo = req.motivo().trim();
        List<DetalleVenta> renglones = detalles.deVentas(List.of(id));
        SesionCaja sesionCaja = v.pasoPorCaja() && v.getTotal().signum() > 0 ? cajaParaDevolver(v, "la anulación") : null;

        v.anular(usuarios.getReferenceById(sesion.usuarioId()), motivo);
        ventas.flush();
        stock.registrarVenta(TipoMovimientoStock.anulacion, id, renglones.stream()
                .map(d -> new RegistroStock.Renglon(d.getArticulo(), d.getUbicacion(), d.getCantidad())).toList(),
                motivo, sesion.usuarioId());
        emisor.anular(v.getComprobante(), motivo, sesion.usuarioId());
        if (sesionCaja != null) {
            caja.registrarCobro(sesionCaja, OrigenMovimientoCaja.devolucion, v.getMedioPago(), v.getTotal(), id,
                    sesion.usuarioId(), "Anulación de la venta " + v.getComprobante().numeroCompleto());
        }

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("comprobante", v.getComprobante().numeroCompleto());
        detalle.put("total", v.getTotal());
        detalle.put("medioPago", v.getMedioPago());
        detalle.put("sesionCajaId", sesionCaja == null ? null : sesionCaja.getId());
        detalle.put("motivo", motivo);
        auditor.registrar("anular", ENTIDAD, id, List.of(v.getAmbito().getId()), detalle);
        return (VentaResponse) vista(sesion, v, renglones, List.of(), List.of());
    }

    /**
     * Devolución parcial, por renglones: vuelve el stock de lo devuelto, se emite una nota de crédito X que referencia
     * al comprobante de la venta y, si se cobró en la caja, sale el dinero de la caja abierta.
     */
    public VentaResponse devolver(Long id, DevolucionVentaRequest req) {
        Sesion sesion = acceso.exigirPerfil(Perfil.dueno_local, Perfil.dueno_fabrica);
        Venta v = ventas.bloquear(id, sesion.ambitoId()).orElseThrow(() -> new NoEncontradoException(NO_EXISTE));
        if (v.getEstado() == EstadoVenta.anulada) {
            throw new ReglaNegocioException("La venta está anulada: no admite devoluciones.");
        }
        validarRenglones(req.renglones().stream().map(r -> r.cantidad()).toList());
        String motivo = req.motivo().trim();
        Map<Long, DetalleVenta> vendidos = detalles.deVentas(List.of(id)).stream()
                .collect(Collectors.toMap(DetalleVenta::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<Long, BigDecimal> yaDevuelto = devuelto(detallesDevueltos.deVentas(List.of(id)));

        record Linea(DetalleVenta vendido, BigDecimal cantidad, BigDecimal subtotal) {
        }
        List<Linea> lineas = new ArrayList<>();
        Set<Long> vistos = new HashSet<>();
        for (DevolucionVentaRequest.Renglon r : req.renglones()) {
            DetalleVenta d = vendidos.get(r.detalleVentaId());
            if (d == null) {
                throw new SolicitudInvalidaException("El renglón " + r.detalleVentaId() + " no es de esta venta.");
            }
            if (!vistos.add(d.getId())) {
                throw new SolicitudInvalidaException("El renglón de '" + d.getArticulo().getNombre() + "' está repetido.");
            }
            validarCantidad(d.getArticulo(), r.cantidad());
            BigDecimal antes = yaDevuelto.getOrDefault(d.getId(), BigDecimal.ZERO);
            BigDecimal queda = d.getCantidad().subtract(antes);
            if (r.cantidad().compareTo(queda) > 0) {
                throw new ReglaNegocioException("De '%s' se vendieron %s y ya se devolvieron %s: se pueden devolver hasta %s."
                        .formatted(d.getArticulo().getNombre(), texto(d.getCantidad()), texto(antes), texto(queda)));
            }
            lineas.add(new Linea(d, r.cantidad(), CalculoComprobante.subtotal(r.cantidad(), d.getPrecioUnitario())));
        }
        BigDecimal total = lineas.stream().map(Linea::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        SesionCaja sesionCaja = v.pasoPorCaja() && total.signum() > 0 ? cajaParaDevolver(v, "la devolución") : null;

        stock.registrarVenta(TipoMovimientoStock.devolucion, id, lineas.stream()
                .map(l -> new RegistroStock.Renglon(l.vendido().getArticulo(), l.vendido().getUbicacion(), l.cantidad()))
                .toList(), motivo, sesion.usuarioId());
        Comprobante notaCredito = emisor.emitir(new Emision(v.getAmbito(), TipoComprobante.nota_credito, v.getCliente(),
                true, lineas.stream().map(l -> new EmisorComprobantes.Renglon(l.vendido().getArticulo(), null,
                        l.cantidad(), l.vendido().getPrecioUnitario(), l.vendido().getAlicuotaIva())).toList(),
                v.getComprobante(), null, motivo, sesion.usuarioId()));
        DevolucionVenta devolucion = devoluciones.save(new DevolucionVenta(v, usuarios.getReferenceById(sesion.usuarioId()),
                motivo, notaCredito, sesionCaja));
        detallesDevueltos.saveAll(lineas.stream()
                .map(l -> new DetalleDevolucionVenta(devolucion, l.vendido(), l.cantidad(), l.subtotal())).toList());
        em.flush();
        if (sesionCaja != null) {
            caja.registrarCobro(sesionCaja, OrigenMovimientoCaja.devolucion, v.getMedioPago(), notaCredito.getTotal(),
                    id, sesion.usuarioId(), "Devolución de la venta " + v.getComprobante().numeroCompleto());
        }

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("devolucionId", devolucion.getId());
        detalle.put("notaCredito", notaCredito.numeroCompleto());
        detalle.put("total", notaCredito.getTotal());
        detalle.put("sesionCajaId", sesionCaja == null ? null : sesionCaja.getId());
        detalle.put("renglones", lineas.stream().map(l -> Map.of("detalleVentaId", l.vendido().getId(),
                "cantidad", l.cantidad())).toList());
        detalle.put("motivo", motivo);
        auditor.registrar("devolver", ENTIDAD, id, List.of(v.getAmbito().getId()), detalle);
        return (VentaResponse) vistas(sesion, List.of(v)).get(0);
    }

    // --- Consultar ---

    /**
     * Las ventas del ámbito, de la más nueva a la más vieja. El empleado ve solo las del día (los filtros de fecha no
     * cuentan para él).
     */
    @Transactional(readOnly = true)
    public PaginaVentas listar(LocalDate desde, LocalDate hasta, Long clienteId, MedioPago medio, EstadoVenta estado,
            int pagina, int tamano) {
        Sesion sesion = acceso.exigirAmbito();
        boolean empleado = sesion.perfil() == Perfil.empleado_local;
        OffsetDateTime inicio = empleado ? inicioDelDia(LocalDate.now(zona)) : desde == null ? null : inicioDelDia(desde);
        OffsetDateTime fin = empleado || hasta == null ? null : inicioDelDia(hasta.plusDays(1));
        int tamanoReal = Math.clamp(tamano, 1, TAMANO_MAXIMO);
        int paginaReal = Math.max(pagina, 0);
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<Venta> consulta = cb.createQuery(Venta.class);
        Root<Venta> v = consulta.from(Venta.class);
        v.fetch("cliente", JoinType.LEFT);
        v.fetch("comprobante");
        v.fetch("usuario");
        consulta.where(filtros(cb, v, sesion.ambitoId(), inicio, fin, clienteId, medio, estado))
                .orderBy(cb.desc(v.get("fechaHora")), cb.desc(v.get("id")));
        List<Venta> lista = em.createQuery(consulta).setFirstResult(paginaReal * tamanoReal).setMaxResults(tamanoReal)
                .getResultList();

        CriteriaQuery<Long> conteo = cb.createQuery(Long.class);
        Root<Venta> k = conteo.from(Venta.class);
        conteo.select(cb.count(k)).where(filtros(cb, k, sesion.ambitoId(), inicio, fin, clienteId, medio, estado));
        return new PaginaVentas(vistas(sesion, lista), paginaReal, tamanoReal, em.createQuery(conteo).getSingleResult());
    }

    @Transactional(readOnly = true)
    public VentaVista obtener(Long id) {
        Sesion sesion = acceso.exigirAmbito();
        Venta v = ventas.findByIdAndAmbitoId(id, sesion.ambitoId())
                .orElseThrow(() -> new NoEncontradoException(NO_EXISTE));
        if (sesion.perfil() == Perfil.empleado_local && v.getFechaHora().isBefore(inicioDelDia(LocalDate.now(zona)))) {
            throw new NoEncontradoException(NO_EXISTE);
        }
        return vistas(sesion, List.of(v)).get(0);
    }

    /** Las ventas de un cliente, con los mismos permisos que el cliente (los pedidos se suman con el módulo 9). */
    @Transactional(readOnly = true)
    public HistorialClienteResponse historialCliente(Long clienteId, int pagina, int tamano) {
        clienteService.obtener(clienteId);
        Sesion sesion = acceso.exigirAmbito();
        int tamanoReal = Math.clamp(tamano, 1, TAMANO_MAXIMO);
        int paginaReal = Math.max(pagina, 0);
        List<Venta> lista = em.createQuery("""
                        select v from Venta v join fetch v.comprobante
                        where v.cliente.id = :cliente and v.ambito.id = :ambito order by v.fechaHora desc, v.id desc
                        """, Venta.class)
                .setParameter("cliente", clienteId).setParameter("ambito", sesion.ambitoId())
                .setFirstResult(paginaReal * tamanoReal).setMaxResults(tamanoReal).getResultList();
        long total = em.createQuery("select count(v) from Venta v where v.cliente.id = :cliente and v.ambito.id = :ambito",
                        Long.class)
                .setParameter("cliente", clienteId).setParameter("ambito", sesion.ambitoId()).getSingleResult();
        Map<Long, BigDecimal> devuelto = lista.isEmpty() ? Map.of()
                : devoluciones.deVentas(lista.stream().map(Venta::getId).toList()).stream()
                        .collect(Collectors.toMap(d -> d.getVenta().getId(), DevolucionVenta::getTotal, BigDecimal::add));
        return new HistorialClienteResponse(clienteId, lista.stream()
                .map(v -> new HistorialClienteResponse.VentaDelCliente(v.getId(), v.getFechaHora(),
                        ComprobanteDeVenta.de(v.getComprobante()), v.getMedioPago(), v.getTotal(),
                        devuelto.getOrDefault(v.getId(), centavos(BigDecimal.ZERO)), v.getEstado()))
                .toList(), paginaReal, tamanoReal, total);
    }

    // --- Validaciones ---

    private static void validarRenglones(List<BigDecimal> cantidades) {
        if (cantidades.isEmpty()) {
            throw new SolicitudInvalidaException("La operación no tiene renglones.");
        }
        if (cantidades.size() > MAXIMO_RENGLONES) {
            throw new SolicitudInvalidaException("Una venta admite hasta " + MAXIMO_RENGLONES + " renglones.");
        }
        for (BigDecimal c : cantidades) {
            if (c.signum() <= 0) {
                throw new SolicitudInvalidaException("Cada cantidad tiene que ser mayor que cero.");
            }
            if (c.compareTo(CANTIDAD_MAXIMA) > 0) {
                throw new SolicitudInvalidaException("La cantidad de un renglón admite hasta " + texto(CANTIDAD_MAXIMA) + ".");
            }
            if (c.stripTrailingZeros().scale() > 3) {
                throw new SolicitudInvalidaException("Las cantidades admiten hasta tres decimales.");
            }
        }
    }

    private static void validarCantidad(Articulo a, BigDecimal cantidad) {
        if (a.getUnidadMedida() == UnidadMedida.unidad && cantidad.stripTrailingZeros().scale() > 0) {
            throw new SolicitudInvalidaException("'" + a.getNombre() + "' se mide en unidades: la cantidad debe ser entera.");
        }
    }

    private static void validarArticulo(Articulo a, BigDecimal cantidad) {
        if (a.getTipo() == TipoArticulo.insumo) {
            throw new ReglaNegocioException("'" + a.getNombre() + "' es un insumo: los insumos no se venden.");
        }
        if (!a.isActivo()) {
            throw new ReglaNegocioException("'" + a.getNombre() + "' está desactivado.");
        }
        validarCantidad(a, cantidad);
    }

    private static BigDecimal porcentaje(BigDecimal p) {
        if (p == null) {
            return centavos(BigDecimal.ZERO);
        }
        if (p.signum() < 0 || p.compareTo(new BigDecimal("100")) > 0 || p.stripTrailingZeros().scale() > 2) {
            throw new SolicitudInvalidaException("El descuento es un porcentaje de 0 a 100, con hasta dos decimales.");
        }
        return centavos(p);
    }

    private Cliente cliente(Long id, Ambito ambito) {
        Cliente c = clientes.findByIdAndAmbitoId(id, ambito.getId())
                .orElseThrow(() -> new NoEncontradoException("No existe el cliente indicado."));
        if (c.representaUnLocal()) {
            throw new ReglaNegocioException("'" + c.nombreVisible() + "' es un local: a los locales se les vende con "
                    + "entregas, no con ventas.");
        }
        if (!c.isActivo()) {
            throw new ReglaNegocioException("El cliente '" + c.nombreVisible() + "' está desactivado.");
        }
        return c;
    }

    private LocalDate fechaCobro(LocalDate fecha, boolean fabrica) {
        if (!fabrica) {
            if (fecha != null) {
                throw new SolicitudInvalidaException("La fecha de cobro es de las ventas de la fábrica: en un local, el "
                        + "cobro queda en la caja.");
            }
            return null;
        }
        LocalDate hoy = LocalDate.now(zona);
        if (fecha == null) {
            return hoy;
        }
        if (fecha.isAfter(hoy)) {
            throw new SolicitudInvalidaException("La fecha de cobro no puede ser posterior a hoy.");
        }
        return fecha;
    }

    /** En un local, la lista del cliente o la predeterminada; en la fábrica, la lista mayorista del cliente. */
    private ListaPrecios lista(Ambito ambito, Cliente cliente, boolean fabrica) {
        ListaPrecios lista;
        if (cliente != null && cliente.getListaPrecios() != null) {
            lista = cliente.getListaPrecios();
        } else if (fabrica) {
            throw new ReglaNegocioException("El mayorista '" + cliente.nombreVisible() + "' no tiene una lista de precios "
                    + "asignada: asignale la lista mayorista antes de venderle.");
        } else {
            lista = listas.findByAmbitoIdAndEsPredeterminadaTrue(ambito.getId())
                    .orElseThrow(() -> new ReglaNegocioException("El local no tiene una lista de precios predeterminada."));
        }
        if (!lista.isActivo()) {
            throw new ReglaNegocioException("La lista de precios '" + lista.getNombre() + "' está desactivada.");
        }
        return lista;
    }

    private UbicacionStock ubicacion(Long id, Ambito ambito) {
        UbicacionStock u = ubicaciones.findById(id).filter(x -> x.esDe(ambito))
                .orElseThrow(() -> new NoEncontradoException("No existe la ubicación indicada."));
        if (!u.isActivo()) {
            throw new ReglaNegocioException("La ubicación '" + u.getNombre() + "' está desactivada.");
        }
        return u;
    }

    private UbicacionStock principal(Ambito ambito) {
        return ubicaciones.findByAmbitoIdAndActivoTrueOrderByNombre(ambito.getId()).stream()
                .filter(u -> u.getNombre().equalsIgnoreCase(PRINCIPAL)).findFirst()
                .orElseThrow(() -> new ReglaNegocioException("No hay una ubicación \"Principal\" activa: indicá de qué "
                        + "ubicación sale cada artículo."));
    }

    /** La caja abierta del local de la venta, para devolver el dinero; sin caja abierta, un 409 que lo explica. */
    private SesionCaja cajaParaDevolver(Venta v, String operacion) {
        try {
            return caja.exigirAbierta(v.getAmbito().getId());
        } catch (ReglaNegocioException e) {
            throw new ReglaNegocioException("No hay una caja abierta en este local: abrila para devolver el dinero de "
                    + operacion + ".");
        }
    }

    // --- Vistas ---

    private List<VentaVista> vistas(Sesion sesion, List<Venta> lista) {
        if (lista.isEmpty()) {
            return List.of();
        }
        List<Long> ids = lista.stream().map(Venta::getId).toList();
        Map<Long, List<DetalleVenta>> renglones = detalles.deVentas(ids).stream()
                .collect(Collectors.groupingBy(d -> d.getVenta().getId()));
        Map<Long, List<DevolucionVenta>> devueltas = devoluciones.deVentas(ids).stream()
                .collect(Collectors.groupingBy(d -> d.getVenta().getId()));
        Map<Long, List<DetalleDevolucionVenta>> renglonesDevueltos = detallesDevueltos.deVentas(ids).stream()
                .collect(Collectors.groupingBy(d -> d.getDevolucion().getId()));
        List<VentaVista> resultado = new ArrayList<>();
        for (Venta v : lista) {
            List<DevolucionVenta> deVenta = devueltas.getOrDefault(v.getId(), List.of());
            List<DetalleDevolucionVenta> detalleDevuelto = deVenta.stream()
                    .flatMap(d -> renglonesDevueltos.getOrDefault(d.getId(), List.of()).stream()).toList();
            resultado.add(vista(sesion, v, renglones.getOrDefault(v.getId(), List.of()), deVenta, detalleDevuelto));
        }
        return resultado;
    }

    private static VentaVista vista(Sesion sesion, Venta v, List<DetalleVenta> renglones, List<DevolucionVenta> devs,
            List<DetalleDevolucionVenta> detalleDevuelto) {
        Map<Long, BigDecimal> devuelto = devuelto(detalleDevuelto);
        Map<Long, List<DetalleDevolucionVenta>> porDevolucion = detalleDevuelto.stream()
                .collect(Collectors.groupingBy(d -> d.getDevolucion().getId()));
        Map<Long, String> nombres = new HashMap<>();
        renglones.forEach(d -> nombres.put(d.getId(), d.getArticulo().getNombre()));
        List<VentaResponse.Devolucion> devolucionesVista = devs.stream().map(d -> new VentaResponse.Devolucion(d.getId(),
                d.getFechaHora(), d.getUsuario().getNombre(), d.getMotivo(), d.getTotal(),
                ComprobanteDeVenta.de(d.getNotaCredito()), porDevolucion.getOrDefault(d.getId(), List.of()).stream()
                        .map(r -> new VentaResponse.RenglonDevuelto(r.getDetalleVenta().getId(),
                                nombres.get(r.getDetalleVenta().getId()), r.getCantidad(), r.getSubtotal()))
                        .toList())).toList();
        BigDecimal totalDevuelto = centavos(devs.stream().map(DevolucionVenta::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add));
        VentaResponse.ClienteDeVenta cliente = v.getCliente() == null ? null
                : new VentaResponse.ClienteDeVenta(v.getCliente().getId(), v.getCliente().nombreVisible());
        VentaResponse.Anulacion anulacion = v.getEstado() != EstadoVenta.anulada ? null
                : new VentaResponse.Anulacion(v.getAnuladaPor().getNombre(), v.getAnuladaAt(), v.getMotivoAnulacion());
        Comprobante c = v.getComprobante();

        if (sesion.perfil() == Perfil.empleado_local) {
            return new VentaEmpleadoResponse(v.getId(), v.getFechaHora(), v.getUsuario().getNombre(), cliente,
                    v.getMedioPago(), v.getSubtotal(), v.getPorcentajeDescuento(), v.getDescuento(), v.getTotal(),
                    totalDevuelto, v.getEstado(), ComprobanteDeVenta.de(c), anulacion, renglones.stream()
                            .map(d -> new VentaEmpleadoResponse.Renglon(d.getId(), d.getArticulo().getId(),
                                    d.getArticulo().getNombre(), d.getUbicacion().getNombre(), d.getCantidad(),
                                    devuelto.getOrDefault(d.getId(), BigDecimal.ZERO), d.getPrecioLista(),
                                    d.getPrecioUnitario(), d.getSubtotal()))
                            .toList(), devolucionesVista);
        }

        boolean discrimina = c.discriminaIva();
        boolean conTodosLosCostos = renglones.stream().allMatch(d -> d.getCostoUnitario() != null);
        BigDecimal costoTotal = conTodosLosCostos ? centavos(renglones.stream()
                .map(d -> d.getCostoUnitario().multiply(d.getCantidad())).reduce(BigDecimal.ZERO, BigDecimal::add)) : null;
        return new VentaResponse(v.getId(), v.getAmbito().getId(), v.getFechaHora(), v.getUsuario().getNombre(), cliente,
                v.getMedioPago(), v.getFechaCobro(), v.getSesionCaja() == null ? null : v.getSesionCaja().getId(),
                v.getSubtotal(), v.getPorcentajeDescuento(), v.getDescuento(), v.getTotal(), totalDevuelto, v.getEstado(),
                ComprobanteDeVenta.de(c), anulacion, costoTotal, CalculoVenta.margen(c.getNeto(), costoTotal),
                renglones.stream().map(d -> new VentaResponse.Renglon(d.getId(), d.getArticulo().getId(),
                        d.getArticulo().getNombre(), d.getUbicacion().getId(), d.getUbicacion().getNombre(),
                        d.getCantidad(), devuelto.getOrDefault(d.getId(), BigDecimal.ZERO), d.getPrecioLista(),
                        d.getPrecioUnitario(), d.getAlicuotaIva(), d.getSubtotal(), d.getCostoUnitario(),
                        CalculoVenta.margen(CalculoVenta.neto(d.getPrecioUnitario(), discrimina, d.getAlicuotaIva()),
                                d.getCostoUnitario())))
                        .toList(), devolucionesVista);
    }

    // --- Ayudas ---

    private static Map<Long, BigDecimal> devuelto(List<DetalleDevolucionVenta> renglones) {
        return renglones.stream().collect(Collectors.toMap(d -> d.getDetalleVenta().getId(),
                DetalleDevolucionVenta::getCantidad, BigDecimal::add));
    }

    private static Predicate[] filtros(CriteriaBuilder cb, Root<Venta> v, Long ambitoId, OffsetDateTime desde,
            OffsetDateTime hasta, Long clienteId, MedioPago medio, EstadoVenta estado) {
        List<Predicate> p = new ArrayList<>();
        p.add(cb.equal(v.get("ambito").get("id"), ambitoId));
        if (desde != null) {
            p.add(cb.greaterThanOrEqualTo(v.get("fechaHora"), desde));
        }
        if (hasta != null) {
            p.add(cb.lessThan(v.get("fechaHora"), hasta));
        }
        if (clienteId != null) {
            p.add(cb.equal(v.get("cliente").get("id"), clienteId));
        }
        if (medio != null) {
            p.add(cb.equal(v.get("medioPago"), medio));
        }
        if (estado != null) {
            p.add(cb.equal(v.get("estado"), estado));
        }
        return p.toArray(Predicate[]::new);
    }

    private OffsetDateTime inicioDelDia(LocalDate dia) {
        return dia.atStartOfDay(zona).toOffsetDateTime();
    }

    private static BigDecimal centavos(BigDecimal valor) {
        return valor.setScale(2, RoundingMode.HALF_UP);
    }

    private static String texto(BigDecimal valor) {
        return valor.stripTrailingZeros().toPlainString();
    }
}
