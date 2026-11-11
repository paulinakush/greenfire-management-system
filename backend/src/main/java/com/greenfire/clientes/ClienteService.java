package com.greenfire.clientes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.greenfire.ambitos.Ambito;
import com.greenfire.ambitos.AmbitoRepository;
import com.greenfire.auditoria.Auditor;
import com.greenfire.clientes.dto.ClienteCondicionesRequest;
import com.greenfire.clientes.dto.ClienteDatosRequest;
import com.greenfire.clientes.dto.ClienteEmpleadoResponse;
import com.greenfire.clientes.dto.ClienteRequest;
import com.greenfire.clientes.dto.ClienteResponse;
import com.greenfire.clientes.dto.ClienteVista;
import com.greenfire.clientes.dto.PaginaClientes;
import com.greenfire.comun.AccesoDenegadoException;
import com.greenfire.comun.Cambios;
import com.greenfire.comun.NoEncontradoException;
import com.greenfire.comun.ReglaNegocioException;
import com.greenfire.comun.SolicitudInvalidaException;
import com.greenfire.fiscal.CondicionIva;
import com.greenfire.fiscal.CuitValidator;
import com.greenfire.precios.ListaPrecios;
import com.greenfire.precios.ListaPreciosRepository;
import com.greenfire.seguridad.Acceso;
import com.greenfire.seguridad.Perfil;
import com.greenfire.seguridad.Sesion;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * Clientes de cada ámbito. Cada local ve y administra solo los suyos. La fábrica tiene un cliente por local (lo
 * crea la base, con los datos del local y de su entidad fiscal; su dueño solo cambia la lista y el plazo) y sus
 * mayoristas de la Línea Jardinería. El empleado busca, da de alta y edita los datos de contacto, sin condiciones
 * comerciales. Los clientes no se borran: se desactivan.
 */
@Service
@Transactional
public class ClienteService {

    public static final int TAMANO_MAXIMO = 100;

    private static final String ENTIDAD = "cliente";
    private static final String NO_EXISTE = "No existe el cliente indicado.";

    private final ClienteRepository clientes;
    private final AmbitoRepository ambitos;
    private final ListaPreciosRepository listas;
    private final EntityManager em;
    private final Acceso acceso;
    private final Auditor auditor;

    public ClienteService(ClienteRepository clientes, AmbitoRepository ambitos, ListaPreciosRepository listas,
            EntityManager em, Acceso acceso, Auditor auditor) {
        this.clientes = clientes;
        this.ambitos = ambitos;
        this.listas = listas;
        this.em = em;
        this.acceso = acceso;
        this.auditor = auditor;
    }

    // --- Consulta ---

    /** Busca por nombre, razón social o documento. El empleado ve solo los activos, sin importar {@code activo}. */
    @Transactional(readOnly = true)
    public PaginaClientes listar(String texto, Boolean activo, int pagina, int tamano) {
        Sesion sesion = acceso.exigirAmbito();
        boolean empleado = esEmpleado(sesion);
        Boolean soloActivo = empleado ? Boolean.TRUE : activo;
        int tamanoReal = Math.clamp(tamano, 1, TAMANO_MAXIMO);
        int paginaReal = Math.max(pagina, 0);
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<Cliente> consulta = cb.createQuery(Cliente.class);
        Root<Cliente> c = consulta.from(Cliente.class);
        c.fetch("listaPrecios", JoinType.LEFT);
        Expression<String> orden = cb.lower(cb.coalesce(c.get("nombre"), c.get("razonSocial")));
        consulta.where(filtros(cb, c, sesion.ambitoId(), texto, soloActivo)).orderBy(cb.asc(orden), cb.asc(c.get("id")));
        List<ClienteVista> lista = em.createQuery(consulta)
                .setFirstResult(paginaReal * tamanoReal)
                .setMaxResults(tamanoReal)
                .getResultStream().map(cl -> vista(cl, empleado)).toList();

        CriteriaQuery<Long> conteo = cb.createQuery(Long.class);
        Root<Cliente> k = conteo.from(Cliente.class);
        conteo.select(cb.count(k)).where(filtros(cb, k, sesion.ambitoId(), texto, soloActivo));
        return new PaginaClientes(lista, paginaReal, tamanoReal, em.createQuery(conteo).getSingleResult());
    }

    @Transactional(readOnly = true)
    public ClienteVista obtener(Long id) {
        Sesion sesion = acceso.exigirAmbito();
        Cliente c = buscar(id, sesion);
        boolean empleado = esEmpleado(sesion);
        if (empleado && !c.isActivo()) {
            throw new NoEncontradoException(NO_EXISTE);
        }
        return vista(c, empleado);
    }

    // --- Alta y edición ---

    public ClienteVista crear(ClienteRequest req) {
        Sesion sesion = acceso.exigirAmbito();
        boolean empleado = esEmpleado(sesion);
        // Las condiciones comerciales las fija el dueño.
        if (empleado && req.condiciones() != null) {
            throw new AccesoDenegadoException();
        }
        Ambito ambito = ambitos.findById(sesion.ambitoId())
                .orElseThrow(() -> new NoEncontradoException("No existe el ámbito."));
        ClienteDatosRequest datos = req.datos();
        validarDatos(datos, ambito.getId(), null);

        Cliente c = new Cliente();
        c.setAmbito(ambito);
        aplicarDatos(c, datos, new Cambios());
        if (req.condiciones() != null) {
            aplicarCondiciones(c, req.condiciones(), new Cambios());
        }
        clientes.save(c);

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("nombre", c.getNombre());
        detalle.put("razonSocial", c.getRazonSocial());
        detalle.put("documentoTipo", c.getDocumentoTipo());
        detalle.put("documentoNumero", c.getDocumentoNumero());
        detalle.put("condicionIva", c.getCondicionIva());
        if (req.condiciones() != null) {
            detalle.put("cuentaCorrienteHabilitada", c.isCuentaCorrienteHabilitada());
            detalle.put("limiteCredito", c.getLimiteCredito());
            detalle.put("listaPreciosId", req.condiciones().listaPreciosId());
            detalle.put("plazoPagoDias", c.getPlazoPagoDias());
        }
        auditor.registrar("crear", ENTIDAD, c.getId(), List.of(ambito.getId()), detalle);
        return vista(c, empleado);
    }

    /** Datos de identificación y contacto. Los de un cliente que representa a un local salen del local. */
    public ClienteVista editarDatos(Long id, ClienteDatosRequest datos) {
        Sesion sesion = acceso.exigirAmbito();
        boolean empleado = esEmpleado(sesion);
        Cliente c = buscar(id, sesion);
        if (empleado && !c.isActivo()) {
            throw new NoEncontradoException(NO_EXISTE);
        }
        if (c.representaUnLocal()) {
            throw new ReglaNegocioException("Los datos de un local salen de su ámbito y de su entidad fiscal: "
                    + "los cambia el administrador técnico.");
        }
        validarDatos(datos, sesion.ambitoId(), id);
        Cambios cambios = new Cambios();
        aplicarDatos(c, datos, cambios);
        auditor.registrarCambios("editar", ENTIDAD, id, afectados(c), cambios);
        return vista(c, empleado);
    }

    /** Cuenta corriente, límite, lista de precios y plazo: solo los dueños. */
    public ClienteResponse editarCondiciones(Long id, ClienteCondicionesRequest req) {
        Sesion sesion = acceso.exigirPerfil(Perfil.dueno_local, Perfil.dueno_fabrica);
        Cliente c = buscar(id, sesion);
        if (c.representaUnLocal() && (!req.cuentaCorrienteHabilitada() || req.limiteCredito() != null)) {
            throw new ReglaNegocioException(
                    "La cuenta de un local con la fábrica está siempre habilitada y no tiene límite: solo plazo.");
        }
        Cambios cambios = new Cambios();
        aplicarCondiciones(c, req, cambios);
        auditor.registrarCambios("editar_condiciones", ENTIDAD, id, afectados(c), cambios);
        return ClienteResponse.de(c);
    }

    public ClienteResponse cambiarActivo(Long id, boolean activo) {
        Sesion sesion = acceso.exigirPerfil(Perfil.dueno_local, Perfil.dueno_fabrica);
        Cliente c = buscar(id, sesion);
        if (c.representaUnLocal() && !activo && c.getAmbitoRepresentado().isActivo()) {
            throw new ReglaNegocioException("El cliente de un local activo no se desactiva.");
        }
        Cambios cambios = new Cambios();
        c.setActivo(cambios.registrar("activo", c.isActivo(), activo));
        auditor.registrarCambios(activo ? "activar" : "desactivar", ENTIDAD, id, afectados(c), cambios);
        return ClienteResponse.de(c);
    }

    // --- Reglas ---

    private void validarDatos(ClienteDatosRequest d, Long ambitoId, Long excepto) {
        if (!StringUtils.hasText(d.nombre()) && !StringUtils.hasText(d.razonSocial())) {
            throw new SolicitudInvalidaException("Indicá el nombre o la razón social.");
        }
        if ((d.documentoTipo() == null) != (d.documentoNumero() == null)) {
            throw new SolicitudInvalidaException("El documento lleva tipo y número, o ninguno de los dos.");
        }
        if (d.documentoTipo() == TipoDocumento.cuit && !CuitValidator.esValido(d.documentoNumero())) {
            throw new SolicitudInvalidaException(
                    "CUIT inválido: deben ser 11 dígitos sin guiones, con dígito verificador correcto.");
        }
        if (d.documentoTipo() == TipoDocumento.dni && !d.documentoNumero().matches("\\d{7,8}")) {
            throw new SolicitudInvalidaException("El DNI tiene 7 u 8 dígitos, sin puntos.");
        }
        if (d.documentoTipo() != null
                && clientes.existeDocumento(ambitoId, d.documentoTipo(), d.documentoNumero(), excepto)) {
            throw new ReglaNegocioException("Ya existe un cliente con ese documento.");
        }
    }

    private static void aplicarDatos(Cliente c, ClienteDatosRequest d, Cambios cambios) {
        c.setNombre(cambios.registrar("nombre", c.getNombre(), texto(d.nombre())));
        c.setRazonSocial(cambios.registrar("razonSocial", c.getRazonSocial(), texto(d.razonSocial())));
        c.setDocumentoTipo(cambios.registrar("documentoTipo", c.getDocumentoTipo(), d.documentoTipo()));
        c.setDocumentoNumero(cambios.registrar("documentoNumero", c.getDocumentoNumero(), d.documentoNumero()));
        CondicionIva condicion = d.condicionIva() == null ? CondicionIva.consumidor_final : d.condicionIva();
        c.setCondicionIva(cambios.registrar("condicionIva", c.getCondicionIva(), condicion));
        c.setTelefono(cambios.registrar("telefono", c.getTelefono(), texto(d.telefono())));
        c.setEmail(cambios.registrar("email", c.getEmail(), texto(d.email())));
        c.setDireccion(cambios.registrar("direccion", c.getDireccion(), texto(d.direccion())));
        if (c.isCuentaCorrienteHabilitada() && c.getDocumentoNumero() == null) {
            throw new ReglaNegocioException("Un cliente con cuenta corriente necesita documento.");
        }
    }

    private void aplicarCondiciones(Cliente c, ClienteCondicionesRequest req, Cambios cambios) {
        if (req.cuentaCorrienteHabilitada() && c.getDocumentoNumero() == null) {
            throw new ReglaNegocioException("Para habilitar la cuenta corriente, el cliente necesita documento.");
        }
        Long listaAntes = c.getListaPrecios() == null ? null : c.getListaPrecios().getId();
        if (req.listaPreciosId() != null && !req.listaPreciosId().equals(listaAntes)) {
            c.setListaPrecios(lista(req.listaPreciosId(), c.getAmbito().getId()));
        } else if (req.listaPreciosId() == null) {
            c.setListaPrecios(null);
        }
        cambios.registrar("listaPreciosId", listaAntes, req.listaPreciosId());
        c.setCuentaCorrienteHabilitada(cambios.registrar("cuentaCorrienteHabilitada",
                c.isCuentaCorrienteHabilitada(), req.cuentaCorrienteHabilitada()));
        if (c.getLimiteCredito() == null || req.limiteCredito() == null
                || c.getLimiteCredito().compareTo(req.limiteCredito()) != 0) {
            c.setLimiteCredito(cambios.registrar("limiteCredito", c.getLimiteCredito(), req.limiteCredito()));
        }
        c.setPlazoPagoDias(cambios.registrar("plazoPagoDias", c.getPlazoPagoDias(), req.plazoPagoDias()));
    }

    /** Una lista activa del ámbito del cliente; la de otro ámbito no existe para él. */
    private ListaPrecios lista(Long id, Long ambitoId) {
        ListaPrecios l = listas.findById(id).filter(x -> x.getAmbito().getId().equals(ambitoId))
                .orElseThrow(() -> new NoEncontradoException("No existe la lista de precios indicada."));
        if (!l.isActivo()) {
            throw new ReglaNegocioException("La lista de precios está desactivada.");
        }
        return l;
    }

    /** El cliente de un local toca también a ese local, que ve en auditoría los cambios de su cuenta. */
    private static List<Long> afectados(Cliente c) {
        List<Long> ids = new ArrayList<>(List.of(c.getAmbito().getId()));
        if (c.representaUnLocal()) {
            ids.add(c.getAmbitoRepresentado().getId());
        }
        return ids;
    }

    private Cliente buscar(Long id, Sesion sesion) {
        return clientes.findByIdAndAmbitoId(id, sesion.ambitoId())
                .orElseThrow(() -> new NoEncontradoException(NO_EXISTE));
    }

    private static Predicate[] filtros(CriteriaBuilder cb, Root<Cliente> c, Long ambitoId, String texto,
            Boolean activo) {
        List<Predicate> p = new ArrayList<>();
        p.add(cb.equal(c.get("ambito").get("id"), ambitoId));
        if (StringUtils.hasText(texto)) {
            String patron = "%" + texto.trim().toLowerCase() + "%";
            p.add(cb.or(cb.like(cb.lower(c.get("nombre")), patron), cb.like(cb.lower(c.get("razonSocial")), patron),
                    cb.like(c.get("documentoNumero"), patron)));
        }
        if (activo != null) {
            p.add(cb.equal(c.get("activo"), activo));
        }
        return p.toArray(Predicate[]::new);
    }

    private static boolean esEmpleado(Sesion sesion) {
        return sesion.perfil() == Perfil.empleado_local;
    }

    private static ClienteVista vista(Cliente c, boolean empleado) {
        return empleado ? ClienteEmpleadoResponse.de(c) : ClienteResponse.de(c);
    }

    private static String texto(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
