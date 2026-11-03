package com.greenfire;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
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
import com.greenfire.fiscal.CondicionIva;
import com.greenfire.fiscal.EntidadFiscal;
import com.greenfire.fiscal.EntidadFiscalRepository;
import com.greenfire.seguridad.Perfil;
import com.greenfire.seguridad.Sesion;
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

    public Datos(EntidadFiscalRepository entidades, AmbitoRepository ambitos, UsuarioRepository usuarios,
            UsuarioAmbitoRepository perfiles, FindByIndexNameSessionRepository<? extends Session> sesiones,
            CategoriaRepository categorias, ArticuloRepository articulos, CostoArticuloRepository costos) {
        this.entidades = entidades;
        this.ambitos = ambitos;
        this.usuarios = usuarios;
        this.perfiles = perfiles;
        this.sesiones = sesiones;
        this.categorias = categorias;
        this.articulos = articulos;
        this.costos = costos;
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

    // --- Usuarios ---

    /** Usuario con la contraseña {@link #PASSWORD} y un email único. */
    public Usuario usuario(String nombre) {
        Usuario u = new Usuario();
        u.setNombre(nombre);
        u.setEmail(nombre.toLowerCase().replaceAll("[^a-z]", "") + SECUENCIA.incrementAndGet() + "@greenfire.test");
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

    /** Login real por la API; devuelve la cookie de sesión. */
    public static Cookie login(MockMvc mvc, String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, password)))
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
