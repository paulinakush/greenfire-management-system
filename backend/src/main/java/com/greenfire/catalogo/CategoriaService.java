package com.greenfire.catalogo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greenfire.auditoria.Auditor;
import com.greenfire.catalogo.dto.CategoriaRequest;
import com.greenfire.catalogo.dto.CategoriaResponse;
import com.greenfire.comun.Cambios;
import com.greenfire.comun.NoEncontradoException;
import com.greenfire.comun.ReglaNegocioException;
import com.greenfire.seguridad.Acceso;
import com.greenfire.seguridad.Perfil;
import com.greenfire.seguridad.Sesion;

/** Las categorías son comunes a todos los ámbitos: las consultan todos y las administra el dueño de fábrica. */
@Service
@Transactional
public class CategoriaService {

    private static final String ENTIDAD = "categoria";

    private final CategoriaRepository categorias;
    private final Acceso acceso;
    private final Auditor auditor;

    public CategoriaService(CategoriaRepository categorias, Acceso acceso, Auditor auditor) {
        this.categorias = categorias;
        this.acceso = acceso;
        this.auditor = auditor;
    }

    /** El empleado ve solo las activas. */
    @Transactional(readOnly = true)
    public List<CategoriaResponse> listar() {
        Sesion sesion = acceso.exigirAmbito();
        List<Categoria> lista = sesion.perfil() == Perfil.empleado_local
                ? categorias.findByActivoTrueOrderByNombre()
                : categorias.findAllByOrderByNombre();
        return lista.stream().map(CategoriaResponse::de).toList();
    }

    public CategoriaResponse crear(CategoriaRequest req) {
        acceso.exigirPerfil(Perfil.dueno_fabrica);
        String nombre = req.nombre().trim();
        if (categorias.existsByNombreIgnoreCase(nombre)) {
            throw new ReglaNegocioException("Ya existe una categoría con ese nombre.");
        }
        Categoria c = new Categoria();
        c.setNombre(nombre);
        c.setDescripcion(req.descripcion());
        categorias.save(c);

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("nombre", c.getNombre());
        detalle.put("descripcion", c.getDescripcion());
        auditor.registrar("crear", ENTIDAD, c.getId(), detalle);
        return CategoriaResponse.de(c);
    }

    public CategoriaResponse editar(Long id, CategoriaRequest req) {
        acceso.exigirPerfil(Perfil.dueno_fabrica);
        Categoria c = buscar(id);
        String nombre = req.nombre().trim();
        if (categorias.existsByNombreIgnoreCaseAndIdNot(nombre, id)) {
            throw new ReglaNegocioException("Ya existe una categoría con ese nombre.");
        }
        Cambios cambios = new Cambios();
        c.setNombre(cambios.registrar("nombre", c.getNombre(), nombre));
        c.setDescripcion(cambios.registrar("descripcion", c.getDescripcion(), req.descripcion()));
        auditor.registrarCambios("editar", ENTIDAD, id, List.of(), cambios);
        return CategoriaResponse.de(c);
    }

    /** Los artículos que ya la tienen la conservan; solo deja de poder asignarse. */
    public CategoriaResponse cambiarActivo(Long id, boolean activo) {
        acceso.exigirPerfil(Perfil.dueno_fabrica);
        Categoria c = buscar(id);
        Cambios cambios = new Cambios();
        c.setActivo(cambios.registrar("activo", c.isActivo(), activo));
        auditor.registrarCambios(activo ? "activar" : "desactivar", ENTIDAD, id, List.of(), cambios);
        return CategoriaResponse.de(c);
    }

    private Categoria buscar(Long id) {
        return categorias.findById(id).orElseThrow(() -> new NoEncontradoException("No existe la categoría indicada."));
    }
}
