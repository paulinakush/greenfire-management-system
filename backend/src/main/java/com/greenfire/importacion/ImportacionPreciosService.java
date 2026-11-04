package com.greenfire.importacion;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greenfire.ambitos.Ambito;
import com.greenfire.ambitos.AmbitoRepository;
import com.greenfire.ambitos.TipoAmbito;
import com.greenfire.auditoria.Auditor;
import com.greenfire.catalogo.Articulo;
import com.greenfire.catalogo.ArticuloRepository;
import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.comun.NoEncontradoException;
import com.greenfire.importacion.dto.FilaImportacion;
import com.greenfire.importacion.dto.FilaImportacion.AccionFila;
import com.greenfire.importacion.dto.ResultadoImportacion;
import com.greenfire.precios.ListaPrecios;
import com.greenfire.precios.ListaPreciosItem;
import com.greenfire.precios.ListaPreciosItemRepository;
import com.greenfire.precios.ListaPreciosService;
import com.greenfire.seguridad.Acceso;
import com.greenfire.seguridad.Perfil;
import com.greenfire.seguridad.Sesion;

/**
 * Importación de precios desde una planilla CSV a una lista del propio ámbito, con las columnas articulo y precio.
 * El artículo se busca por nombre, sin distinguir mayúsculas, entre los que el ámbito ve: los suyos y, en un local,
 * los productos terminados de la fábrica. Uno que no existe o que el ámbito no ve es el mismo error, así no se
 * revela qué hay en otro ámbito.
 * <p>
 * Cada precio que cambia queda en el historial de precios con el usuario; la importación se audita una vez. La
 * vista previa no guarda nada y la confirmación aplica todo o nada, como la de artículos.
 */
@Service
@Transactional
public class ImportacionPreciosService {

    static final Set<String> OBLIGATORIAS = Set.of("articulo", "precio");
    private static final BigDecimal PRECIO_MAXIMO = new BigDecimal("999999999999.99");

    private final ListaPreciosService listas;
    private final ListaPreciosItemRepository items;
    private final ArticuloRepository articulos;
    private final AmbitoRepository ambitos;
    private final Acceso acceso;
    private final Auditor auditor;

    public ImportacionPreciosService(ListaPreciosService listas, ListaPreciosItemRepository items,
            ArticuloRepository articulos, AmbitoRepository ambitos, Acceso acceso, Auditor auditor) {
        this.listas = listas;
        this.items = items;
        this.articulos = articulos;
        this.ambitos = ambitos;
        this.acceso = acceso;
        this.auditor = auditor;
    }

    private record Plan(LectorCsv.Fila fila, String nombre, AccionFila accion, String motivo, Articulo articulo,
            ListaPreciosItem item, BigDecimal anterior, BigDecimal nuevo) {

        FilaImportacion resultado() {
            return new FilaImportacion(fila.numero(), accion, nombre, motivo, List.of(), anterior, nuevo);
        }
    }

    @Transactional(readOnly = true)
    public ResultadoImportacion vistaPrevia(Long listaId, String archivo, byte[] contenido) {
        Ambito ambito = ambitoDe(exigirDueno());
        ListaPrecios lista = listas.buscarActiva(listaId, ambito);
        return ResultadoImportacion.de(archivo, false,
                planificar(ambito, lista, contenido).stream().map(Plan::resultado).toList());
    }

    public ResultadoImportacion aplicar(Long listaId, String archivo, byte[] contenido) {
        Sesion sesion = exigirDueno();
        Ambito ambito = ambitoDe(sesion);
        ListaPrecios lista = listas.buscarActiva(listaId, ambito);
        List<Plan> planes = planificar(ambito, lista, contenido);
        ResultadoImportacion resultado = ResultadoImportacion.de(archivo, false,
                planes.stream().map(Plan::resultado).toList());
        if (resultado.tieneErrores()) {
            throw new ImportacionConErroresException(resultado);
        }

        List<Map<String, Object>> cambios = new ArrayList<>();
        for (Plan p : planes) {
            if (p.accion() == AccionFila.sin_cambios) {
                continue;
            }
            ListaPreciosItem item = p.item() != null ? p.item() : new ListaPreciosItem(lista, p.articulo());
            item.setPrecio(p.nuevo());
            items.save(item);
            listas.guardarHistorial(sesion, lista, p.articulo(), p.anterior(), p.nuevo());
            Map<String, Object> cambio = new LinkedHashMap<>();
            cambio.put("fila", p.fila().numero());
            cambio.put("articuloId", p.articulo().getId());
            cambio.put("antes", p.anterior());
            cambio.put("despues", p.nuevo());
            cambios.add(cambio);
        }
        items.flush();

        ResultadoImportacion aplicado = ResultadoImportacion.de(archivo, true, resultado.detalle());
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("archivo", archivo);
        detalle.put("creadas", aplicado.crear());
        detalle.put("actualizadas", aplicado.actualizar());
        detalle.put("sinCambios", aplicado.sinCambios());
        detalle.put("precios", cambios);
        auditor.registrar("importar_precios", "lista_precios", lista.getId(), List.of(ambito.getId()), detalle);
        return aplicado;
    }

    private List<Plan> planificar(Ambito ambito, ListaPrecios lista, byte[] contenido) {
        LectorCsv.Archivo csv = LectorCsv.leer(contenido, OBLIGATORIAS, Set.of());
        Map<String, List<Articulo>> visibles = new HashMap<>();
        List<Articulo> candidatos = new ArrayList<>(articulos.findByAmbitoId(ambito.getId()));
        if (ambito.getTipo() == TipoAmbito.local) {
            candidatos.addAll(articulos.findByTipo(TipoArticulo.producto_terminado));
        }
        candidatos.forEach(a -> visibles.computeIfAbsent(clave(a.getNombre()), k -> new ArrayList<>()).add(a));
        Map<Long, ListaPreciosItem> actuales = new HashMap<>();
        items.listar(lista.getId()).forEach(i -> actuales.put(i.getArticulo().getId(), i));

        Map<Long, Integer> vistos = new HashMap<>();
        List<Plan> planes = new ArrayList<>();
        for (LectorCsv.Fila fila : csv.filas()) {
            String nombre = fila.valor("articulo");
            try {
                if (fila.columnasDeMas()) {
                    throw new IllegalArgumentException("La fila tiene más columnas que el encabezado.");
                }
                Articulo a = articulo(nombre, visibles);
                BigDecimal precio = precio(fila.valor("precio"));
                Integer repetida = vistos.putIfAbsent(a.getId(), fila.numero());
                if (repetida != null) {
                    throw new IllegalArgumentException("El artículo '" + a.getNombre() + "' ya está en la fila "
                            + repetida + ".");
                }
                ListaPreciosItem item = actuales.get(a.getId());
                BigDecimal anterior = item == null ? null : item.getPrecio();
                AccionFila accion = anterior == null ? AccionFila.crear
                        : anterior.compareTo(precio) == 0 ? AccionFila.sin_cambios : AccionFila.actualizar;
                planes.add(new Plan(fila, a.getNombre(), accion, null, a, item, anterior, precio));
            } catch (IllegalArgumentException e) {
                planes.add(new Plan(fila, nombre, AccionFila.error, e.getMessage(), null, null, null, null));
            }
        }
        return planes;
    }

    /** Uno que no existe y uno de otro ámbito responden lo mismo. */
    private static Articulo articulo(String nombre, Map<String, List<Articulo>> visibles) {
        if (nombre == null) {
            throw new IllegalArgumentException("Falta el artículo.");
        }
        List<Articulo> encontrados = visibles.getOrDefault(clave(nombre), List.of());
        if (encontrados.isEmpty()) {
            throw new IllegalArgumentException("No existe el artículo '" + nombre + "'.");
        }
        if (encontrados.size() > 1) {
            throw new IllegalArgumentException("Hay más de un artículo llamado '" + nombre
                    + "' (uno del local y uno de la fábrica): cambiá el nombre de la reventa para distinguirlos.");
        }
        Articulo a = encontrados.get(0);
        if (a.getTipo() == TipoArticulo.insumo) {
            throw new IllegalArgumentException("'" + a.getNombre() + "' es un insumo: los insumos no llevan precio.");
        }
        if (!a.isActivo()) {
            throw new IllegalArgumentException("'" + a.getNombre() + "' está desactivado.");
        }
        return a;
    }

    private static BigDecimal precio(String valor) {
        if (valor == null) {
            throw new IllegalArgumentException("Falta el precio.");
        }
        BigDecimal precio = Numero.leer(valor, "precio");
        if (precio.signum() < 0) {
            throw new IllegalArgumentException("El precio no puede ser negativo.");
        }
        // con la escala escrita, no la normalizada: "1.000" es un separador de miles, no un precio de 1
        if (precio.scale() > 2) {
            throw new IllegalArgumentException("El precio '" + valor
                    + "' tiene más de dos decimales: los decimales van con coma o punto, sin separador de miles.");
        }
        if (precio.compareTo(PRECIO_MAXIMO) > 0) {
            throw new IllegalArgumentException("El precio es demasiado grande.");
        }
        return precio.setScale(2);
    }

    private Sesion exigirDueno() {
        return acceso.exigirPerfil(Perfil.dueno_fabrica, Perfil.dueno_local);
    }

    private Ambito ambitoDe(Sesion sesion) {
        return ambitos.findById(sesion.ambitoId()).orElseThrow(() -> new NoEncontradoException("No existe el ámbito."));
    }

    private static String clave(String nombre) {
        return nombre.strip().toLowerCase(Locale.ROOT);
    }
}
