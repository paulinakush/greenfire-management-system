package com.greenfire.importacion;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import com.greenfire.catalogo.Categoria;
import com.greenfire.catalogo.CategoriaRepository;
import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.catalogo.UnidadMedida;
import com.greenfire.catalogo.dto.ArticuloDatos;
import com.greenfire.comun.Cambios;
import com.greenfire.comun.NoEncontradoException;
import com.greenfire.importacion.dto.FilaImportacion;
import com.greenfire.importacion.dto.FilaImportacion.AccionFila;
import com.greenfire.importacion.dto.ResultadoImportacion;
import com.greenfire.seguridad.Acceso;
import com.greenfire.seguridad.Perfil;
import com.greenfire.seguridad.Sesion;

/**
 * Importación de artículos desde una planilla CSV. Cada dueño importa solo lo que administra: el dueño de fábrica,
 * insumos y productos terminados; el dueño de local, la reventa de su local. Un artículo se identifica por su nombre
 * dentro del ámbito, sin distinguir mayúsculas: si existe se actualiza, si no se crea.
 * <p>
 * En una actualización, una celda opcional vacía deja el valor como está. Las columnas obligatorias son nombre,
 * tipo, unidad y alicuota_iva; las opcionales, categoria, marca, descripcion, imagen y visible_web. La categoría
 * es obligatoria salvo en los insumos, y tiene que existir y estar activa.
 * <p>
 * La vista previa no guarda nada. La confirmación vuelve a validar el archivo y lo aplica todo o nada: con una fila
 * con error no se aplica ninguna. Los textos que empiezan con =, +, - o @ se guardan tal cual (ver CLAUDE.md).
 */
@Service
@Transactional
public class ImportacionArticulosService {

    static final Set<String> OBLIGATORIAS = Set.of("nombre", "tipo", "unidad", "alicuota_iva");
    static final Set<String> OPCIONALES = Set.of("categoria", "marca", "descripcion", "imagen", "visible_web");
    private static final String ENTIDAD = "importacion";

    private final ArticuloRepository articulos;
    private final CategoriaRepository categorias;
    private final AmbitoRepository ambitos;
    private final Acceso acceso;
    private final Auditor auditor;

    public ImportacionArticulosService(ArticuloRepository articulos, CategoriaRepository categorias,
            AmbitoRepository ambitos, Acceso acceso, Auditor auditor) {
        this.articulos = articulos;
        this.categorias = categorias;
        this.ambitos = ambitos;
        this.acceso = acceso;
        this.auditor = auditor;
    }

    /** Los datos de una fila ya validados. Los opcionales en null no cambian en una actualización. */
    private record Datos(String nombre, TipoArticulo tipo, Categoria categoria, UnidadMedida unidad,
            BigDecimal alicuota, String marca, String descripcion, String imagen, Boolean visibleWeb) {
    }

    private record Plan(LectorCsv.Fila fila, String nombre, AccionFila accion, String motivo, Articulo existente,
            Datos datos, List<String> campos) {

        FilaImportacion resultado() {
            return new FilaImportacion(fila.numero(), accion, nombre, motivo, campos, null, null);
        }
    }

    @Transactional(readOnly = true)
    public ResultadoImportacion vistaPrevia(String archivo, byte[] contenido) {
        Ambito ambito = ambitoDe(exigirDueno());
        return ResultadoImportacion.de(archivo, false, planificar(ambito, contenido).stream().map(Plan::resultado).toList());
    }

    public ResultadoImportacion aplicar(String archivo, byte[] contenido) {
        Ambito ambito = ambitoDe(exigirDueno());
        List<Plan> planes = planificar(ambito, contenido);
        ResultadoImportacion previo = ResultadoImportacion.de(archivo, false,
                planes.stream().map(Plan::resultado).toList());
        if (previo.tieneErrores()) {
            throw new ImportacionConErroresException(previo);
        }

        List<Map<String, Object>> filas = new ArrayList<>();
        for (Plan p : planes) {
            if (p.accion() == AccionFila.sin_cambios) {
                continue;
            }
            Map<String, Object> registro = new LinkedHashMap<>();
            registro.put("fila", p.fila().numero());
            registro.put("accion", p.accion());
            if (p.accion() == AccionFila.crear) {
                Articulo a = crear(ambito, p.datos());
                registro.put("articuloId", a.getId());
                registro.put("nombre", a.getNombre());
            } else {
                registro.put("articuloId", p.existente().getId());
                registro.put("cambios", actualizar(p.existente(), p.datos()).comoMapa());
            }
            filas.add(registro);
        }
        articulos.flush();

        ResultadoImportacion resultado = ResultadoImportacion.de(archivo, true,
                planes.stream().map(Plan::resultado).toList());
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("archivo", archivo);
        detalle.put("creadas", resultado.crear());
        detalle.put("actualizadas", resultado.actualizar());
        detalle.put("sinCambios", resultado.sinCambios());
        detalle.put("filas", filas);
        auditor.registrar("importar_articulos", ENTIDAD, null, List.of(ambito.getId()), detalle);
        return resultado;
    }

    // --- Validación ---

    private List<Plan> planificar(Ambito ambito, byte[] contenido) {
        LectorCsv.Archivo csv = LectorCsv.leer(contenido, OBLIGATORIAS, OPCIONALES);
        Map<String, Articulo> propios = new HashMap<>();
        articulos.findByAmbitoId(ambito.getId()).forEach(a -> propios.put(clave(a.getNombre()), a));
        Map<String, Categoria> porNombre = new HashMap<>();
        categorias.findAllByOrderByNombre().forEach(c -> porNombre.put(clave(c.getNombre()), c));

        Map<String, Integer> nombresVistos = new HashMap<>();
        List<Plan> planes = new ArrayList<>();
        for (LectorCsv.Fila fila : csv.filas()) {
            String nombre = fila.valor("nombre");
            try {
                Datos datos = leer(fila, ambito, porNombre);
                Integer repetida = nombresVistos.putIfAbsent(clave(datos.nombre()), fila.numero());
                if (repetida != null) {
                    throw new IllegalArgumentException("El artículo '" + datos.nombre()
                            + "' ya está en la fila " + repetida + ".");
                }
                Articulo existente = propios.get(clave(datos.nombre()));
                if (existente == null) {
                    exigirCategoria(datos);
                    planes.add(new Plan(fila, datos.nombre(), AccionFila.crear, null, null, datos, List.of()));
                } else {
                    if (existente.getTipo() != datos.tipo()) {
                        throw new IllegalArgumentException("'" + existente.getNombre() + "' ya existe como "
                                + existente.getTipo() + ": el tipo no se cambia.");
                    }
                    List<String> campos = camposQueCambian(existente, datos);
                    planes.add(new Plan(fila, datos.nombre(), campos.isEmpty() ? AccionFila.sin_cambios
                            : AccionFila.actualizar, null, existente, datos, campos));
                }
            } catch (IllegalArgumentException e) {
                planes.add(new Plan(fila, nombre, AccionFila.error, e.getMessage(), null, null, List.of()));
            }
        }
        return planes;
    }

    private static Datos leer(LectorCsv.Fila fila, Ambito ambito, Map<String, Categoria> categorias) {
        if (fila.columnasDeMas()) {
            throw new IllegalArgumentException("La fila tiene más columnas que el encabezado.");
        }
        String nombre = texto(fila, "nombre", 200);
        if (nombre == null) {
            throw new IllegalArgumentException("Falta el nombre.");
        }
        TipoArticulo tipo = tipo(fila.valor("tipo"), ambito);
        UnidadMedida unidad = unidad(fila.valor("unidad"));
        BigDecimal alicuota = alicuota(fila.valor("alicuota_iva"));

        String nombreCategoria = fila.valor("categoria");
        Categoria categoria = null;
        if (nombreCategoria != null) {
            if (tipo == TipoArticulo.insumo) {
                throw new IllegalArgumentException("Los insumos no llevan categoría.");
            }
            categoria = categorias.get(clave(nombreCategoria));
            if (categoria == null) {
                throw new IllegalArgumentException("No existe la categoría '" + nombreCategoria + "'.");
            }
            if (!categoria.isActivo()) {
                throw new IllegalArgumentException("La categoría '" + categoria.getNombre() + "' está desactivada.");
            }
        }
        Boolean visibleWeb = siNo(fila.valor("visible_web"));
        if (tipo == TipoArticulo.insumo && Boolean.TRUE.equals(visibleWeb)) {
            throw new IllegalArgumentException("Los insumos no se muestran en la web.");
        }
        String imagen = texto(fila, "imagen", 255);
        if (imagen != null && !imagen.matches(ArticuloDatos.RUTA_IMAGEN)) {
            throw new IllegalArgumentException("La imagen tiene que ser una ruta dentro de assets/productos/.");
        }
        return new Datos(nombre, tipo, categoria, unidad, alicuota, texto(fila, "marca", 120),
                texto(fila, "descripcion", 2000), imagen, visibleWeb);
    }

    /** El dueño de fábrica importa insumos y productos terminados; el dueño de local, reventa. */
    private static TipoArticulo tipo(String valor, Ambito ambito) {
        if (valor == null) {
            throw new IllegalArgumentException("Falta el tipo.");
        }
        TipoArticulo tipo;
        try {
            tipo = TipoArticulo.valueOf(LectorCsv.normalizar(valor));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("El tipo '" + valor + "' no existe: es insumo, producto_terminado o reventa.");
        }
        boolean local = ambito.getTipo() == TipoAmbito.local;
        if (local && tipo != TipoArticulo.reventa) {
            throw new IllegalArgumentException("El local solo importa reventa: los insumos y los productos terminados "
                    + "los administra la fábrica.");
        }
        if (!local && tipo == TipoArticulo.reventa) {
            throw new IllegalArgumentException("La reventa la importa cada local.");
        }
        return tipo;
    }

    private static UnidadMedida unidad(String valor) {
        if (valor == null) {
            throw new IllegalArgumentException("Falta la unidad.");
        }
        try {
            return UnidadMedida.valueOf(LectorCsv.normalizar(valor));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("La unidad '" + valor + "' no existe: es unidad, kg, litro, m3 o dm3.");
        }
    }

    private static BigDecimal alicuota(String valor) {
        if (valor == null) {
            throw new IllegalArgumentException("Falta la alícuota de IVA.");
        }
        BigDecimal alicuota = Numero.leer(valor, "alícuota de IVA");
        if (!ArticuloDatos.alicuotaValida(alicuota)) {
            throw new IllegalArgumentException("La alícuota de IVA debe ser " + ArticuloDatos.ALICUOTAS + ".");
        }
        return alicuota.setScale(2);
    }

    private static Boolean siNo(String valor) {
        if (valor == null) {
            return null;
        }
        return switch (LectorCsv.normalizar(valor)) {
            case "si", "true", "1", "x" -> true;
            case "no", "false", "0" -> false;
            default -> throw new IllegalArgumentException("visible_web debe ser sí o no, no '" + valor + "'.");
        };
    }

    private static String texto(LectorCsv.Fila fila, String columna, int largoMaximo) {
        String valor = fila.valor(columna);
        if (valor != null && valor.length() > largoMaximo) {
            throw new IllegalArgumentException("La columna " + columna + " admite hasta " + largoMaximo
                    + " caracteres.");
        }
        return valor;
    }

    private static void exigirCategoria(Datos d) {
        if (d.tipo() != TipoArticulo.insumo && d.categoria() == null) {
            throw new IllegalArgumentException("Los productos terminados y la reventa llevan categoría.");
        }
    }

    private static List<String> camposQueCambian(Articulo a, Datos d) {
        Set<String> campos = new LinkedHashSet<>();
        if (!a.getNombre().equals(d.nombre())) {
            campos.add("nombre");
        }
        if (d.categoria() != null && (a.getCategoria() == null || !a.getCategoria().getId().equals(d.categoria().getId()))) {
            campos.add("categoria");
        }
        if (a.getUnidadMedida() != d.unidad()) {
            campos.add("unidad");
        }
        if (a.getAlicuotaIva().compareTo(d.alicuota()) != 0) {
            campos.add("alicuota_iva");
        }
        if (d.marca() != null && !d.marca().equals(a.getMarca())) {
            campos.add("marca");
        }
        if (d.descripcion() != null && !d.descripcion().equals(a.getDescripcion())) {
            campos.add("descripcion");
        }
        if (d.imagen() != null && !d.imagen().equals(a.getImagen())) {
            campos.add("imagen");
        }
        if (d.visibleWeb() != null && d.visibleWeb() != a.isVisibleWeb()) {
            campos.add("visible_web");
        }
        return List.copyOf(campos);
    }

    // --- Aplicación ---

    private Articulo crear(Ambito ambito, Datos d) {
        Articulo a = new Articulo();
        a.setAmbito(ambito);
        a.setTipo(d.tipo());
        a.setNombre(d.nombre());
        a.setCategoria(d.categoria());
        a.setUnidadMedida(d.unidad());
        a.setAlicuotaIva(d.alicuota());
        a.setMarca(d.marca());
        a.setDescripcion(d.descripcion());
        a.setImagen(d.imagen());
        a.setVisibleWeb(Boolean.TRUE.equals(d.visibleWeb()));
        return articulos.save(a);
    }

    private static Cambios actualizar(Articulo a, Datos d) {
        Cambios c = new Cambios();
        a.setNombre(c.registrar("nombre", a.getNombre(), d.nombre()));
        if (d.categoria() != null) {
            Long antes = a.getCategoria() == null ? null : a.getCategoria().getId();
            c.registrar("categoriaId", antes, d.categoria().getId());
            a.setCategoria(d.categoria());
        }
        a.setUnidadMedida(c.registrar("unidadMedida", a.getUnidadMedida(), d.unidad()));
        if (a.getAlicuotaIva().compareTo(d.alicuota()) != 0) {
            a.setAlicuotaIva(c.registrar("alicuotaIva", a.getAlicuotaIva(), d.alicuota()));
        }
        if (d.marca() != null) {
            a.setMarca(c.registrar("marca", a.getMarca(), d.marca()));
        }
        if (d.descripcion() != null) {
            a.setDescripcion(c.registrar("descripcion", a.getDescripcion(), d.descripcion()));
        }
        if (d.imagen() != null) {
            a.setImagen(c.registrar("imagen", a.getImagen(), d.imagen()));
        }
        if (d.visibleWeb() != null) {
            a.setVisibleWeb(c.registrar("visibleWeb", a.isVisibleWeb(), d.visibleWeb()));
        }
        return c;
    }

    // --- Acceso ---

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
