package com.greenfire.catalogo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.greenfire.ambitos.Ambito;
import com.greenfire.ambitos.AmbitoRepository;
import com.greenfire.ambitos.TipoAmbito;
import com.greenfire.auditoria.Auditor;
import com.greenfire.catalogo.dto.ArticuloDatos;
import com.greenfire.catalogo.dto.ArticuloEdicionRequest;
import com.greenfire.catalogo.dto.ArticuloEmpleadoResponse;
import com.greenfire.catalogo.dto.ArticuloRequest;
import com.greenfire.catalogo.dto.ArticuloResponse;
import com.greenfire.catalogo.dto.ArticuloVista;
import com.greenfire.catalogo.dto.CostoRequest;
import com.greenfire.comun.AccesoDenegadoException;
import com.greenfire.comun.Cambios;
import com.greenfire.comun.NoEncontradoException;
import com.greenfire.comun.ReglaNegocioException;
import com.greenfire.seguridad.Acceso;
import com.greenfire.seguridad.Perfil;
import com.greenfire.seguridad.Sesion;

import jakarta.persistence.criteria.Predicate;

/**
 * Catálogo de artículos. La fábrica ve sus insumos y productos terminados; cada local ve los productos
 * terminados de la fábrica y su propia reventa, nunca la de otro local. Cada ámbito edita solo sus artículos
 * y ve solo su costo. El empleado no recibe costos.
 */
@Service
@Transactional
public class ArticuloService {

    private static final String ENTIDAD = "articulo";
    private static final String NO_EXISTE = "No existe el artículo indicado.";

    private final ArticuloRepository articulos;
    private final CategoriaRepository categorias;
    private final CostoArticuloRepository costos;
    private final AmbitoRepository ambitos;
    private final Acceso acceso;
    private final Auditor auditor;

    public ArticuloService(ArticuloRepository articulos, CategoriaRepository categorias,
            CostoArticuloRepository costos, AmbitoRepository ambitos, Acceso acceso, Auditor auditor) {
        this.articulos = articulos;
        this.categorias = categorias;
        this.costos = costos;
        this.ambitos = ambitos;
        this.acceso = acceso;
        this.auditor = auditor;
    }

    // --- Consulta ---

    /** El empleado ve solo los artículos activos, sin importar el filtro {@code activo}. */
    @Transactional(readOnly = true)
    public List<ArticuloVista> listar(TipoArticulo tipo, Long categoriaId, String texto, Boolean activo) {
        Sesion sesion = acceso.exigirAmbito();
        Ambito ambito = ambitoDe(sesion);
        boolean empleado = sesion.perfil() == Perfil.empleado_local;

        Specification<Articulo> filtro = (a, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(visiblesPara(ambito).toPredicate(a, q, cb));
            if (tipo != null) {
                p.add(cb.equal(a.get("tipo"), tipo));
            }
            if (categoriaId != null) {
                p.add(cb.equal(a.get("categoria").get("id"), categoriaId));
            }
            if (StringUtils.hasText(texto)) {
                String patron = "%" + texto.trim().toLowerCase() + "%";
                p.add(cb.or(cb.like(cb.lower(a.get("nombre")), patron), cb.like(cb.lower(a.get("marca")), patron)));
            }
            Boolean soloActivo = empleado ? Boolean.TRUE : activo;
            if (soloActivo != null) {
                p.add(cb.equal(a.get("activo"), soloActivo));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        List<Articulo> lista = articulos.findAll(filtro, Sort.by("nombre", "id"));

        if (empleado) {
            return lista.stream().<ArticuloVista>map(ArticuloEmpleadoResponse::de).toList();
        }
        Map<Long, BigDecimal> costoPorArticulo = lista.isEmpty() ? Map.of()
                : costos.findByAmbitoIdAndArticuloIdIn(ambito.getId(), lista.stream().map(Articulo::getId).toList())
                        .stream().collect(Collectors.toMap(c -> c.getArticulo().getId(), CostoArticulo::getCostoActual));
        return lista.stream()
                .<ArticuloVista>map(a -> ArticuloResponse.de(a, ambito.getId(), costoPorArticulo.get(a.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ArticuloVista obtener(Long id) {
        Sesion sesion = acceso.exigirAmbito();
        Ambito ambito = ambitoDe(sesion);
        Articulo a = buscarVisible(id, ambito);
        if (sesion.perfil() == Perfil.empleado_local) {
            if (!a.isActivo()) {
                throw new NoEncontradoException(NO_EXISTE);
            }
            return ArticuloEmpleadoResponse.de(a);
        }
        return respuesta(a, ambito);
    }

    // --- Alta y edición, solo de los artículos del propio ámbito ---

    /** La fábrica da de alta insumos y productos terminados; un local, solo reventa. */
    public ArticuloResponse crear(ArticuloRequest req) {
        Sesion sesion = acceso.exigirPerfil(Perfil.dueno_fabrica, Perfil.dueno_local);
        Ambito ambito = ambitoDe(sesion);
        if (!puedeTener(ambito, req.tipo())) {
            throw new AccesoDenegadoException();
        }
        validar(req.tipo(), req);
        String nombre = req.nombre().trim();
        if (articulos.existsByAmbitoIdAndNombreIgnoreCase(ambito.getId(), nombre)) {
            throw new ReglaNegocioException("Ya existe un artículo con ese nombre.");
        }

        Articulo a = new Articulo();
        a.setAmbito(ambito);
        a.setTipo(req.tipo());
        a.setNombre(nombre);
        a.setDescripcion(req.descripcion());
        a.setCategoria(categoria(req.categoriaId()));
        a.setMarca(req.marca());
        a.setUnidadMedida(req.unidadMedida());
        a.setImagen(req.imagen());
        a.setAlicuotaIva(req.alicuotaIva());
        a.setVisibleWeb(req.visibleWeb());
        articulos.save(a);

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("tipo", a.getTipo());
        detalle.put("nombre", a.getNombre());
        detalle.put("categoriaId", req.categoriaId());
        detalle.put("unidadMedida", a.getUnidadMedida());
        detalle.put("alicuotaIva", a.getAlicuotaIva());
        detalle.put("visibleWeb", a.isVisibleWeb());
        auditor.registrar("crear", ENTIDAD, a.getId(), List.of(ambito.getId()), detalle);
        return ArticuloResponse.de(a, ambito.getId(), null);
    }

    public ArticuloResponse editar(Long id, ArticuloEdicionRequest req) {
        Sesion sesion = acceso.exigirPerfil(Perfil.dueno_fabrica, Perfil.dueno_local);
        Ambito ambito = ambitoDe(sesion);
        Articulo a = buscarPropio(id, ambito);
        validar(a.getTipo(), req);
        String nombre = req.nombre().trim();
        if (articulos.existsByAmbitoIdAndNombreIgnoreCaseAndIdNot(ambito.getId(), nombre, id)) {
            throw new ReglaNegocioException("Ya existe un artículo con ese nombre.");
        }
        Long categoriaAntes = a.getCategoria() == null ? null : a.getCategoria().getId();
        if (req.categoriaId() != null && !req.categoriaId().equals(categoriaAntes)) {
            a.setCategoria(categoria(req.categoriaId()));
        }

        Cambios c = new Cambios();
        c.registrar("categoriaId", categoriaAntes, req.categoriaId());
        a.setNombre(c.registrar("nombre", a.getNombre(), nombre));
        a.setDescripcion(c.registrar("descripcion", a.getDescripcion(), req.descripcion()));
        a.setMarca(c.registrar("marca", a.getMarca(), req.marca()));
        a.setUnidadMedida(c.registrar("unidadMedida", a.getUnidadMedida(), req.unidadMedida()));
        a.setImagen(c.registrar("imagen", a.getImagen(), req.imagen()));
        if (a.getAlicuotaIva().compareTo(req.alicuotaIva()) != 0) {
            a.setAlicuotaIva(c.registrar("alicuotaIva", a.getAlicuotaIva(), req.alicuotaIva()));
        }
        a.setVisibleWeb(c.registrar("visibleWeb", a.isVisibleWeb(), req.visibleWeb()));
        auditor.registrarCambios("editar", ENTIDAD, id, List.of(ambito.getId()), c);
        return respuesta(a, ambito);
    }

    public ArticuloResponse cambiarActivo(Long id, boolean activo) {
        Sesion sesion = acceso.exigirPerfil(Perfil.dueno_fabrica, Perfil.dueno_local);
        Ambito ambito = ambitoDe(sesion);
        Articulo a = buscarPropio(id, ambito);
        Cambios c = new Cambios();
        a.setActivo(c.registrar("activo", a.isActivo(), activo));
        auditor.registrarCambios(activo ? "activar" : "desactivar", ENTIDAD, id, List.of(ambito.getId()), c);
        return respuesta(a, ambito);
    }

    /**
     * Carga a mano el costo del artículo en el ámbito de la sesión, hasta que lo calculen las compras y la
     * producción (etapa 4). El costo de un producto de la fábrica en un local es el precio de la última entrega
     * recibida: no se carga a mano.
     */
    public ArticuloResponse editarCosto(Long id, CostoRequest req) {
        Sesion sesion = acceso.exigirPerfil(Perfil.dueno_fabrica, Perfil.dueno_local);
        Ambito ambito = ambitoDe(sesion);
        Articulo a = buscarVisible(id, ambito);
        if (!esDe(a, ambito)) {
            throw new ReglaNegocioException("El costo de un producto de la fábrica en el local es el precio de la "
                    + "última entrega recibida: no se carga a mano.");
        }
        CostoArticulo costo = costos.findByArticuloIdAndAmbitoId(id, ambito.getId())
                .orElseGet(() -> new CostoArticulo(a, ambito));
        BigDecimal nuevo = req.costo().setScale(2, RoundingMode.UNNECESSARY);
        Cambios c = new Cambios();
        costo.setCostoActual(c.registrar("costo", costo.getCostoActual(), nuevo));
        costos.save(costo);
        auditor.registrarCambios("editar_costo", ENTIDAD, id, List.of(ambito.getId()), c);
        return ArticuloResponse.de(a, ambito.getId(), costo.getCostoActual());
    }

    // --- Reglas ---

    /** Lo que ve un ámbito: sus propios artículos y, si es un local, los productos terminados de la fábrica. */
    static Specification<Articulo> visiblesPara(Ambito ambito) {
        return (a, q, cb) -> ambito.getTipo() == TipoAmbito.local
                ? cb.or(cb.equal(a.get("ambito").get("id"), ambito.getId()),
                        cb.equal(a.get("tipo"), TipoArticulo.producto_terminado))
                : cb.equal(a.get("ambito").get("id"), ambito.getId());
    }

    private static boolean esVisible(Articulo a, Ambito ambito) {
        return esDe(a, ambito)
                || (ambito.getTipo() == TipoAmbito.local && a.getTipo() == TipoArticulo.producto_terminado);
    }

    private static boolean esDe(Articulo a, Ambito ambito) {
        return a.getAmbito().getId().equals(ambito.getId());
    }

    private static boolean puedeTener(Ambito ambito, TipoArticulo tipo) {
        return (tipo == TipoArticulo.reventa) == (ambito.getTipo() == TipoAmbito.local);
    }

    private void validar(TipoArticulo tipo, ArticuloDatos datos) {
        if (tipo == TipoArticulo.insumo) {
            if (datos.categoriaId() != null) {
                throw new ReglaNegocioException("Los insumos no llevan categoría.");
            }
            if (datos.visibleWeb()) {
                throw new ReglaNegocioException("Los insumos no se muestran en la web.");
            }
        } else if (datos.categoriaId() == null) {
            throw new ReglaNegocioException("Los productos terminados y la reventa llevan categoría.");
        }
    }

    /** Una categoría activa; las desactivadas no se pueden asignar. */
    private Categoria categoria(Long id) {
        if (id == null) {
            return null;
        }
        Categoria c = categorias.findById(id)
                .orElseThrow(() -> new NoEncontradoException("No existe la categoría indicada."));
        if (!c.isActivo()) {
            throw new ReglaNegocioException("La categoría está desactivada.");
        }
        return c;
    }

    private ArticuloResponse respuesta(Articulo a, Ambito ambito) {
        BigDecimal costo = costos.findByArticuloIdAndAmbitoId(a.getId(), ambito.getId())
                .map(CostoArticulo::getCostoActual).orElse(null);
        return ArticuloResponse.de(a, ambito.getId(), costo);
    }

    /** Un artículo que el ámbito no ve responde igual que uno inexistente. */
    private Articulo buscarVisible(Long id, Ambito ambito) {
        return articulos.findById(id).filter(a -> esVisible(a, ambito))
                .orElseThrow(() -> new NoEncontradoException(NO_EXISTE));
    }

    /** Los productos de la fábrica se ven desde el local pero no se editan: 403. */
    private Articulo buscarPropio(Long id, Ambito ambito) {
        Articulo a = buscarVisible(id, ambito);
        if (!esDe(a, ambito)) {
            throw new AccesoDenegadoException();
        }
        return a;
    }

    private Ambito ambitoDe(Sesion sesion) {
        return ambitos.findById(sesion.ambitoId()).orElseThrow(() -> new NoEncontradoException("No existe el ámbito."));
    }
}
