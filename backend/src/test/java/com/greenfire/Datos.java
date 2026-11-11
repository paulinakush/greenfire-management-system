package com.greenfire;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.greenfire.ambitos.Ambito;
import com.greenfire.ambitos.AmbitoRepository;
import com.greenfire.ambitos.TipoAmbito;
import com.greenfire.catalogo.Articulo;
import com.greenfire.catalogo.ArticuloRepository;
import com.greenfire.catalogo.Categoria;
import com.greenfire.catalogo.CategoriaRepository;
import com.greenfire.catalogo.CostoArticulo;
import com.greenfire.catalogo.CostoArticuloRepository;
import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.catalogo.UnidadMedida;
import com.greenfire.clientes.Cliente;
import com.greenfire.clientes.ClienteRepository;
import com.greenfire.fiscal.CondicionIva;
import com.greenfire.fiscal.EntidadFiscal;
import com.greenfire.fiscal.EntidadFiscalRepository;
import com.greenfire.fiscal.ComprobantePermitido;
import com.greenfire.fiscal.LetraComprobante;
import com.greenfire.fiscal.PuntoVenta;
import com.greenfire.fiscal.PuntoVentaRepository;
import com.greenfire.fiscal.TipoComprobante;
import com.greenfire.precios.ListaPrecios;
import com.greenfire.precios.ListaPreciosItem;
import com.greenfire.precios.ListaPreciosItemRepository;
import com.greenfire.precios.ListaPreciosRepository;
import com.greenfire.seguridad.Perfil;
import com.greenfire.seguridad.Sesion;
import com.greenfire.stock.UbicacionStock;
import com.greenfire.stock.UbicacionStockRepository;
import com.greenfire.usuarios.Usuario;
import com.greenfire.usuarios.UsuarioAmbito;
import com.greenfire.usuarios.UsuarioAmbitoRepository;
import com.greenfire.usuarios.UsuarioRepository;

import jakarta.servlet.http.Cookie;

/** Datos de prueba creados directamente con los repositorios, sin pasar por las reglas de acceso. */
@TestComponent
public class Datos {

    /** Contraseña de todos los usuarios de prueba. */
    public static final String PASSWORD = "clave-de-prueba-1";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder().encode(PASSWORD);
    private static final AtomicLong SECUENCIA = new AtomicLong();
    private static long siguienteCuit = 20_100_000_00L;

    private final EntidadFiscalRepository entidades;
    private final AmbitoRepository ambitos;
    private final UsuarioRepository usuarios;
    private final UsuarioAmbitoRepository perfiles;
    private final FindByIndexNameSessionRepository<? extends Session> sesiones;
    private final CategoriaRepository categorias;
    private final ArticuloRepository articulos;
    private final CostoArticuloRepository costos;
    private final ListaPreciosRepository listas;
    private final ListaPreciosItemRepository precios;
    private final UbicacionStockRepository ubicaciones;
    private final ClienteRepository clientes;
    private final PuntoVentaRepository puntosVenta;
    private final JdbcTemplate jdbc;

    public Datos(EntidadFiscalRepository entidades, AmbitoRepository ambitos, UsuarioRepository usuarios,
            UsuarioAmbitoRepository perfiles, FindByIndexNameSessionRepository<? extends Session> sesiones,
            CategoriaRepository categorias, ArticuloRepository articulos, CostoArticuloRepository costos,
            ListaPreciosRepository listas, ListaPreciosItemRepository precios, UbicacionStockRepository ubicaciones,
            ClienteRepository clientes, PuntoVentaRepository puntosVenta, JdbcTemplate jdbc) {
        this.entidades = entidades;
        this.ambitos = ambitos;
        this.usuarios = usuarios;
        this.perfiles = perfiles;
        this.sesiones = sesiones;
        this.categorias = categorias;
        this.articulos = articulos;
        this.costos = costos;
        this.listas = listas;
        this.precios = precios;
        this.ubicaciones = ubicaciones;
        this.clientes = clientes;
        this.puntosVenta = puntosVenta;
        this.jdbc = jdbc;
    }

    // --- Entidades fiscales y ámbitos ---

    /** Un CUIT nuevo con dígito verificador válido. */
    public static synchronized String cuit() {
        while (true) {
            String base = String.valueOf(siguienteCuit++);
            int[] pesos = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};
            int suma = 0;
            for (int i = 0; i < 10; i++) {
                suma += (base.charAt(i) - '0') * pesos[i];
            }
            int dv = 11 - suma % 11;
            if (dv == 11) {
                dv = 0;
            }
            if (dv != 10) {
                return base + dv;
            }
        }
    }

    public EntidadFiscal entidad(CondicionIva condicion) {
        EntidadFiscal e = new EntidadFiscal();
        e.setRazonSocial("Entidad " + condicion);
        e.setCuit(cuit());
        e.setCondicionIva(condicion);
        return entidades.save(e);
    }

    public Ambito ambito(TipoAmbito tipo, String nombre, CondicionIva condicion) {
        Ambito a = new Ambito();
        a.setTipo(tipo);
        a.setNombre(nombre);
        a.setEntidadFiscal(entidad(condicion));
        return ambitos.save(a);
    }

    public Ambito local(String nombre) {
        return ambito(TipoAmbito.local, nombre, CondicionIva.monotributo);
    }

    public Ambito fabrica() {
        return ambito(TipoAmbito.fabrica, "Fábrica", CondicionIva.responsable_inscripto);
    }

    /** Punto de venta activo del ámbito, con el número indicado, que emite los tipos indicados en letra X. */
    public PuntoVenta puntoVentaX(Ambito ambito, int numero, TipoComprobante... tipos) {
        PuntoVenta p = new PuntoVenta();
        p.setAmbito(ambito);
        p.setNumero(numero);
        p.setDescripcion("Punto de venta X de prueba");
        p.setComprobantes(Arrays.stream(tipos).map(t -> new ComprobantePermitido(t, LetraComprobante.X))
                .collect(Collectors.toSet()));
        return puntosVenta.saveAndFlush(p);
    }

    /** Punto de venta número 1 del ámbito, con todos los tipos X. */
    public PuntoVenta puntoVentaX(Ambito ambito) {
        return puntoVentaX(ambito, 1, TipoComprobante.comprobante_venta, TipoComprobante.nota_credito,
                TipoComprobante.nota_debito, TipoComprobante.remito, TipoComprobante.recibo,
                TipoComprobante.presupuesto);
    }

    // --- Catálogo ---

    public Categoria categoria(String nombre) {
        Categoria c = new Categoria();
        c.setNombre(nombre + " " + SECUENCIA.incrementAndGet());
        return categorias.save(c);
    }

    /** Artículo activo del ámbito; los insumos van sin categoría. */
    public Articulo articulo(Ambito ambito, TipoArticulo tipo, String nombre, Categoria categoria) {
        Articulo a = new Articulo();
        a.setAmbito(ambito);
        a.setTipo(tipo);
        a.setNombre(nombre);
        a.setCategoria(tipo == TipoArticulo.insumo ? null : categoria);
        a.setUnidadMedida(UnidadMedida.unidad);
        a.setAlicuotaIva(new BigDecimal("21"));
        return articulos.save(a);
    }

    public CostoArticulo costo(Articulo articulo, Ambito ambito, String costo) {
        CostoArticulo c = new CostoArticulo(articulo, ambito);
        c.setCostoActual(new BigDecimal(costo));
        return costos.save(c);
    }

    // --- Precios ---

    public ListaPrecios lista(Ambito ambito, String nombre, boolean preciosConIva, boolean predeterminada) {
        ListaPrecios l = new ListaPrecios();
        l.setAmbito(ambito);
        l.setNombre(nombre);
        l.setPreciosConIva(preciosConIva);
        l.setEsPredeterminada(predeterminada);
        return listas.save(l);
    }

    public ListaPreciosItem precio(ListaPrecios lista, Articulo articulo, String precio) {
        ListaPreciosItem i = new ListaPreciosItem(lista, articulo);
        i.setPrecio(new BigDecimal(precio).setScale(2)); // como lo devuelve la base
        return precios.save(i);
    }

    // --- Stock ---

    /** La ubicación "Principal" que la base crea con cada ámbito. */
    public UbicacionStock principal(Ambito ambito) {
        return ubicaciones.findByAmbitoIdOrderByNombre(ambito.getId()).stream()
                .filter(u -> u.getNombre().equals("Principal")).findFirst().orElseThrow();
    }

    public UbicacionStock ubicacion(Ambito ambito, String nombre) {
        UbicacionStock u = new UbicacionStock();
        u.setAmbito(ambito);
        u.setNombre(nombre);
        return ubicaciones.save(u);
    }

    /** Carga stock con un movimiento de ajuste insertado directamente en la base (el trigger suma la cantidad). */
    public void stock(Articulo articulo, UbicacionStock ubicacion, String cantidad) {
        ubicaciones.flush();
        jdbc.update("""
                insert into movimientos_stock (articulo_id, ubicacion_stock_id, tipo, cantidad, usuario_id, motivo)
                values (?, ?, 'ajuste', ?::numeric, ?, 'Carga de prueba')
                """, articulo.getId(), ubicacion.getId(), cantidad, usuario("Carga").getId());
    }

    /** Cantidad actual leída de la base. */
    public BigDecimal cantidad(Articulo articulo, UbicacionStock ubicacion) {
        return jdbc.query("select cantidad_actual from stock_articulos where articulo_id = ? and ubicacion_stock_id = ?",
                rs -> rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO.setScale(3), articulo.getId(),
                ubicacion.getId());
    }

    // --- Clientes ---

    /** Cliente activo del ámbito, consumidor final, sin documento ni cuenta corriente. */
    public Cliente cliente(Ambito ambito, String nombre) {
        Cliente c = new Cliente();
        c.setAmbito(ambito);
        c.setNombre(nombre);
        return clientes.save(c);
    }

    /** El cliente de la fábrica que representa al local, creado por la base. */
    public Cliente clienteDelLocal(Ambito local) {
        clientes.flush();
        return clientes.findByAmbitoRepresentadoId(local.getId()).orElseThrow();
    }

    // --- Usuarios ---

    /** Usuario con la contraseña {@link #PASSWORD}, y usuario y email únicos (el email es el usuario + @greenfire.test). */
    public Usuario usuario(String nombre) {
        Usuario u = new Usuario();
        u.setNombre(nombre);
        u.setUsuario(nombre.toLowerCase().replaceAll("[^a-z]", "") + SECUENCIA.incrementAndGet());
        u.setEmail(u.getUsuario() + "@greenfire.test");
        u.setPasswordHash(PASSWORD_HASH);
        return usuarios.save(u);
    }

    public Usuario adminTecnico() {
        Usuario u = usuario("Admin");
        u.setEsAdminTecnico(true);
        return u;
    }

    public UsuarioAmbito perfil(Usuario usuario, Ambito ambito, Perfil perfil) {
        UsuarioAmbito ua = new UsuarioAmbito();
        ua.setUsuario(usuario);
        ua.setAmbito(ambito);
        ua.setPerfil(perfil);
        return perfiles.save(ua);
    }

    // --- Sesiones ya iniciadas (para probar permisos sin pasar por el login) ---

    /** Request de un administrador técnico nuevo, con sesión iniciada y token CSRF. */
    public RequestPostProcessor admin() {
        return sesion(Sesion.adminTecnico(adminTecnico().getId()));
    }

    /** Request de un usuario nuevo con ese perfil en el ámbito, trabajando en él. */
    public RequestPostProcessor en(Ambito ambito, Perfil perfil) {
        Usuario u = usuario(perfil.name());
        perfil(u, ambito, perfil);
        return sesion(Sesion.enAmbito(u.getId(), ambito.getId(), perfil));
    }

    /** Request con una sesión guardada en la base (como la que crea el login) y token CSRF. */
    public RequestPostProcessor sesion(Sesion sesion) {
        String id = guardar(sesiones, sesion);
        Cookie cookie = new Cookie("SESSION", Base64.getEncoder().encodeToString(id.getBytes(StandardCharsets.UTF_8)));
        return request -> {
            request.setCookies(cookie);
            return csrf().postProcessRequest(request);
        };
    }

    private static <S extends Session> String guardar(SessionRepository<S> repositorio, Sesion sesion) {
        S s = repositorio.createSession();
        s.setAttribute(Sesion.ATRIBUTO, sesion);
        s.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, sesion.usuarioId().toString());
        repositorio.save(s);
        return s.getId();
    }

    /** Login real por la API, con el usuario o el email; devuelve la cookie de sesión. */
    public static Cookie login(MockMvc mvc, String usuario, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usuario\": \"%s\", \"password\": \"%s\"}".formatted(usuario, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("SESSION");
    }

    /** Request con la cookie de un login real y token CSRF. */
    public static RequestPostProcessor con(Cookie sesion) {
        return request -> {
            request.setCookies(sesion);
            return csrf().postProcessRequest(request);
        };
    }

    /** Request JSON con la sesión indicada. */
    public static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, RequestPostProcessor sesion,
            String cuerpo) {
        return req.with(sesion).contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }
}
