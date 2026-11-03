package com.greenfire.precios;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
import com.greenfire.comun.Cambios;
import com.greenfire.comun.NoEncontradoException;
import com.greenfire.comun.ReglaNegocioException;
import com.greenfire.precios.dto.HistorialPrecioResponse;
import com.greenfire.precios.dto.ListaPreciosItemResponse;
import com.greenfire.precios.dto.ListaPreciosRequest;
import com.greenfire.precios.dto.ListaPreciosResponse;
import com.greenfire.precios.dto.PaginaHistorial;
import com.greenfire.precios.dto.PrecioEmpleadoResponse;
import com.greenfire.precios.dto.PrecioRequest;
import com.greenfire.seguridad.Acceso;
import com.greenfire.seguridad.Perfil;
import com.greenfire.seguridad.Sesion;
import com.greenfire.usuarios.UsuarioRepository;

/**
 * Listas de precios de cada ámbito. El dueño de fábrica administra las de la fábrica (las listas para locales) y
 * cada dueño de local, las de su local. Nadie ve las listas de otro ámbito. El empleado solo consulta los precios
 * de venta de su local, sin costos ni márgenes.
 */
@Service
@Transactional
public class ListaPreciosService {

    static final String ENTIDAD = "lista_precios";
    private static final String NO_EXISTE = "No existe la lista de precios indicada.";
    private static final int TAMANO_MAXIMO = 200;

    private final ListaPreciosRepository listas;
    private final ListaPreciosItemRepository items;
    private final HistorialPrecioRepository historial;
    private final ArticuloRepository articulos;
    private final CostoArticuloRepository costos;
    private final AmbitoRepository ambitos;
    private final UsuarioRepository usuarios;
    private final Acceso acceso;
    private final Auditor auditor;

    public ListaPreciosService(ListaPreciosRepository listas, ListaPreciosItemRepository items,
            HistorialPrecioRepository historial, ArticuloRepository articulos, CostoArticuloRepository costos,
            AmbitoRepository ambitos, UsuarioRepository usuarios, Acceso acceso, Auditor auditor) {
        this.listas = listas;
        this.items = items;
        this.historial = historial;
        this.articulos = articulos;
        this.costos = costos;
        this.ambitos = ambitos;
        this.usuarios = usuarios;
        this.acceso = acceso;
        this.auditor = auditor;
    }

    // --- Listas ---

    @Transactional(readOnly = true)
    public List<ListaPreciosResponse> listar() {
        Ambito ambito = ambitoDe(exigirDueno());
        boolean discrimina = discriminaIva(ambito);
        return listas.findByAmbitoIdOrderByNombre(ambito.getId()).stream()
                .map(l -> ListaPreciosResponse.de(l, discrimina)).toList();
    }

    public ListaPreciosResponse crear(ListaPreciosRequest req) {
        Ambito ambito = ambitoDe(exigirDueno());
        validarIva(ambito, req.preciosConIva());
        String nombre = req.nombre().trim();
        if (listas.existsByAmbitoIdAndNombreIgnoreCase(ambito.getId(), nombre)) {
            throw new ReglaNegocioException("Ya existe una lista con ese nombre.");
        }
        if (req.esPredeterminada()) {
            quitarPredeterminada(ambito);
        }

        ListaPrecios l = new ListaPrecios();
        l.setAmbito(ambito);
        l.setNombre(nombre);
        l.setPreciosConIva(req.preciosConIva());
        l.setEsPredeterminada(req.esPredeterminada());
        listas.save(l);

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("nombre", l.getNombre());
        detalle.put("preciosConIva", l.isPreciosConIva());
        detalle.put("esPredeterminada", l.isEsPredeterminada());
        auditor.registrar("crear", ENTIDAD, l.getId(), List.of(ambito.getId()), detalle);
        return ListaPreciosResponse.de(l, discriminaIva(ambito));
    }

    /** {@code preciosConIva} solo cambia mientras la lista no tiene precios: cambiaría el sentido de todos. */
    public ListaPreciosResponse editar(Long id, ListaPreciosRequest req) {
        Ambito ambito = ambitoDe(exigirDueno());
        ListaPrecios l = buscar(id, ambito);
        String nombre = req.nombre().trim();
        if (listas.existsByAmbitoIdAndNombreIgnoreCaseAndIdNot(ambito.getId(), nombre, id)) {
            throw new ReglaNegocioException("Ya existe una lista con ese nombre.");
        }
        if (req.preciosConIva() != l.isPreciosConIva()) {
            validarIva(ambito, req.preciosConIva());
            if (items.existsByListaId(id)) {
                throw new ReglaNegocioException("La lista ya tiene precios: no se puede cambiar si son con o sin IVA.");
            }
        }
        if (req.esPredeterminada() && !l.isEsPredeterminada()) {
            if (!l.isActivo()) {
                throw new ReglaNegocioException("La lista está desactivada: no puede ser la predeterminada.");
            }
            quitarPredeterminada(ambito);
        }

        Cambios c = new Cambios();
        l.setNombre(c.registrar("nombre", l.getNombre(), nombre));
        l.setPreciosConIva(c.registrar("preciosConIva", l.isPreciosConIva(), req.preciosConIva()));
        l.setEsPredeterminada(c.registrar("esPredeterminada", l.isEsPredeterminada(), req.esPredeterminada()));
        auditor.registrarCambios("editar", ENTIDAD, id, List.of(ambito.getId()), c);
        return ListaPreciosResponse.de(l, discriminaIva(ambito));
    }

    public ListaPreciosResponse cambiarActivo(Long id, boolean activo) {
        Ambito ambito = ambitoDe(exigirDueno());
        ListaPrecios l = buscar(id, ambito);
        if (!activo && l.isEsPredeterminada()) {
            throw new ReglaNegocioException(
                    "La lista predeterminada no se puede desactivar: elegí antes otra como predeterminada.");
        }
        Cambios c = new Cambios();
        l.setActivo(c.registrar("activo", l.isActivo(), activo));
        auditor.registrarCambios(activo ? "activar" : "desactivar", ENTIDAD, id, List.of(ambito.getId()), c);
        return ListaPreciosResponse.de(l, discriminaIva(ambito));
    }

    // --- Precios de una lista ---

    /** Precio, precio neto, costo del ámbito de la lista y margen de cada artículo. */
    @Transactional(readOnly = true)
    public List<ListaPreciosItemResponse> items(Long id) {
        Ambito ambito = ambitoDe(exigirDueno());
        ListaPrecios l = buscar(id, ambito);
        List<ListaPreciosItem> lista = items.listar(l.getId());
        Map<Long, BigDecimal> costoPorArticulo = lista.isEmpty() ? Map.of()
                : costos.findByAmbitoIdAndArticuloIdIn(ambito.getId(),
                                lista.stream().map(i -> i.getArticulo().getId()).toList())
                        .stream().collect(Collectors.toMap(c -> c.getArticulo().getId(), CostoArticulo::getCostoActual));
        boolean discrimina = discriminaIva(ambito);
        return lista.stream().map(i -> respuesta(i, discrimina, costoPorArticulo.get(i.getArticulo().getId())))
                .toList();
    }

    /** Carga o cambia el precio de un artículo. Si el precio no cambia, no deja historial ni auditoría. */
    public ListaPreciosItemResponse ponerPrecio(Long id, Long articuloId, PrecioRequest req) {
        Sesion sesion = exigirDueno();
        Ambito ambito = ambitoDe(sesion);
        ListaPrecios l = buscarActiva(id, ambito);
        Articulo a = articuloVendible(articuloId, ambito);
        ListaPreciosItem item = items.findByListaIdAndArticuloId(id, articuloId)
                .orElseGet(() -> new ListaPreciosItem(l, a));
        BigDecimal anterior = item.getPrecio();
        BigDecimal nuevo = req.precio().setScale(2);
        if (anterior == null || anterior.compareTo(nuevo) != 0) {
            item.setPrecio(nuevo);
            items.save(item);
            registrarCambio(sesion, l, a, anterior, nuevo, "editar_precio");
        }
        BigDecimal costo = costos.findByArticuloIdAndAmbitoId(articuloId, ambito.getId())
                .map(CostoArticulo::getCostoActual).orElse(null);
        return respuesta(item, discriminaIva(ambito), costo);
    }

    /** Saca el artículo de la lista. El historial conserva el último precio. */
    public void quitarPrecio(Long id, Long articuloId) {
        Sesion sesion = exigirDueno();
        Ambito ambito = ambitoDe(sesion);
        ListaPrecios l = buscarActiva(id, ambito);
        ListaPreciosItem item = items.findByListaIdAndArticuloId(id, articuloId)
                .orElseThrow(() -> new NoEncontradoException("El artículo no tiene precio en la lista."));
        items.delete(item);
        registrarCambio(sesion, l, item.getArticulo(), item.getPrecio(), null, "quitar_precio");
    }

    @Transactional(readOnly = true)
    public PaginaHistorial historial(Long id, Long articuloId, int pagina, int tamano) {
        Ambito ambito = ambitoDe(exigirDueno());
        ListaPrecios l = buscar(id, ambito);
        int tamanoReal = Math.clamp(tamano, 1, TAMANO_MAXIMO);
        int paginaReal = Math.max(pagina, 0);
        Page<HistorialPrecioResponse> registros = historial
                .consultar(l.getId(), articuloId, PageRequest.of(paginaReal, tamanoReal))
                .map(HistorialPrecioResponse::de);
        return new PaginaHistorial(registros.getContent(), paginaReal, tamanoReal, registros.getTotalElements());
    }

    // --- Empleado ---

    /**
     * Precios de venta del local: los de su lista predeterminada, solo de artículos activos. Es el precio que paga
     * el consumidor: si la entidad discrimina IVA y la lista es sin IVA, se le suma el IVA.
     */
    @Transactional(readOnly = true)
    public List<PrecioEmpleadoResponse> preciosDeVenta() {
        Ambito ambito = ambitoDe(acceso.exigirPerfil(Perfil.empleado_local, Perfil.dueno_local));
        boolean discrimina = discriminaIva(ambito);
        return listas.findByAmbitoIdAndEsPredeterminadaTrue(ambito.getId())
                .map(l -> items.listar(l.getId()).stream()
                        .filter(i -> i.getArticulo().isActivo())
                        .map(i -> new PrecioEmpleadoResponse(i.getArticulo().getId(), i.getArticulo().getNombre(),
                                CalculoPrecio.precioFinal(i.getPrecio(), discrimina, l.isPreciosConIva(),
                                        i.getArticulo().getAlicuotaIva())))
                        .toList())
                .orElse(List.of());
    }

    // --- Usados también por la actualización masiva ---

    Sesion exigirDueno() {
        return acceso.exigirPerfil(Perfil.dueno_fabrica, Perfil.dueno_local);
    }

    Ambito ambitoDe(Sesion sesion) {
        return ambitos.findById(sesion.ambitoId()).orElseThrow(() -> new NoEncontradoException("No existe el ámbito."));
    }

    /** Una lista de otro ámbito responde igual que una inexistente. */
    ListaPrecios buscar(Long id, Ambito ambito) {
        return listas.findById(id).filter(l -> l.getAmbito().getId().equals(ambito.getId()))
                .orElseThrow(() -> new NoEncontradoException(NO_EXISTE));
    }

    /** Los precios de una lista desactivada se consultan pero no se cambian. */
    ListaPrecios buscarActiva(Long id, Ambito ambito) {
        ListaPrecios l = buscar(id, ambito);
        if (!l.isActivo()) {
            throw new ReglaNegocioException("La lista está desactivada: sus precios no se pueden cambiar.");
        }
        return l;
    }

    /** Guarda el cambio en el historial de precios y en la auditoría, con el ámbito de la lista como afectado. */
    private void registrarCambio(Sesion sesion, ListaPrecios lista, Articulo articulo, BigDecimal anterior,
            BigDecimal nuevo, String accion) {
        guardarHistorial(sesion, lista, articulo, anterior, nuevo);
        Map<String, Object> precio = new LinkedHashMap<>();
        precio.put("antes", anterior);
        precio.put("despues", nuevo);
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("articuloId", articulo.getId());
        detalle.put("precio", precio);
        auditor.registrar(accion, ENTIDAD, lista.getId(), List.of(lista.getAmbito().getId()), detalle);
    }

    /** Solo el historial: la actualización masiva audita la operación una sola vez. */
    void guardarHistorial(Sesion sesion, ListaPrecios lista, Articulo articulo, BigDecimal anterior,
            BigDecimal nuevo) {
        historial.save(new HistorialPrecio(lista, articulo, anterior, nuevo,
                usuarios.getReferenceById(sesion.usuarioId())));
    }

    static boolean discriminaIva(Ambito ambito) {
        return ambito.getEntidadFiscal().getCondicionIva().discriminaIva();
    }

    // --- Reglas ---

    /** Si la entidad no discrimina IVA, el precio de la lista es el precio final: la lista va con IVA. */
    private static void validarIva(Ambito ambito, boolean preciosConIva) {
        if (!preciosConIva && !discriminaIva(ambito)) {
            throw new ReglaNegocioException(
                    "La entidad fiscal del ámbito no discrimina IVA: los precios de la lista son finales, con IVA.");
        }
    }

    /** Hay una sola predeterminada por ámbito (índice único parcial): la anterior deja de serlo. */
    private void quitarPredeterminada(Ambito ambito) {
        listas.findByAmbitoIdAndEsPredeterminadaTrue(ambito.getId()).ifPresent(anterior -> {
            Cambios c = new Cambios();
            anterior.setEsPredeterminada(c.registrar("esPredeterminada", true, false));
            listas.flush();
            auditor.registrarCambios("editar", ENTIDAD, anterior.getId(), List.of(ambito.getId()), c);
        });
    }

    /**
     * Un artículo que el ámbito vende: los productos terminados de la fábrica y la reventa propia. Lo que el ámbito
     * no ve responde 404; los insumos y los artículos desactivados no llevan precio.
     */
    private Articulo articuloVendible(Long articuloId, Ambito ambito) {
        Articulo a = articulos.findById(articuloId)
                .filter(x -> x.getAmbito().getId().equals(ambito.getId())
                        || (ambito.getTipo() == TipoAmbito.local && x.getTipo() == TipoArticulo.producto_terminado))
                .orElseThrow(() -> new NoEncontradoException("No existe el artículo indicado."));
        if (a.getTipo() == TipoArticulo.insumo) {
            throw new ReglaNegocioException("Los insumos no se venden: no llevan precio.");
        }
        if (!a.isActivo()) {
            throw new ReglaNegocioException("El artículo está desactivado.");
        }
        return a;
    }

    private static ListaPreciosItemResponse respuesta(ListaPreciosItem i, boolean discriminaIva, BigDecimal costo) {
        Articulo a = i.getArticulo();
        boolean conIva = i.getLista().isPreciosConIva();
        return new ListaPreciosItemResponse(a.getId(), a.getNombre(), a.getTipo(),
                a.getCategoria() == null ? null : a.getCategoria().getId(), a.getAlicuotaIva(), a.isActivo(),
                i.getPrecio(), CalculoPrecio.precioNeto(i.getPrecio(), discriminaIva, conIva, a.getAlicuotaIva()),
                costo, CalculoPrecio.margen(i.getPrecio(), costo, discriminaIva, conIva, a.getAlicuotaIva()));
    }
}
