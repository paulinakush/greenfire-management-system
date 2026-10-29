package com.greenfire.seguridad;

import java.io.IOException;

import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import com.greenfire.usuarios.Usuario;
import com.greenfire.usuarios.UsuarioAmbitoRepository;
import com.greenfire.usuarios.UsuarioRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * En cada pedido a la API verifica, contra la base, que el usuario de la sesión siga activo y que su perfil
 * y su ámbito activos sigan vigentes. Si no, cierra la sesión: un usuario desactivado pierde el acceso al instante.
 */
public class FiltroSesionVigente extends OncePerRequestFilter {

    private final UsuarioRepository usuarios;
    private final UsuarioAmbitoRepository perfiles;
    private final TransactionTemplate lectura;

    public FiltroSesionVigente(UsuarioRepository usuarios, UsuarioAmbitoRepository perfiles,
            TransactionTemplate lectura) {
        this.usuarios = usuarios;
        this.perfiles = perfiles;
        this.lectura = lectura;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession http = request.getSession(false);
        if (http != null && http.getAttribute(Sesion.ATRIBUTO) instanceof Sesion sesion
                && !Boolean.TRUE.equals(lectura.execute(tx -> vigente(sesion)))) {
            http.invalidate();
        }
        chain.doFilter(request, response);
    }

    private boolean vigente(Sesion sesion) {
        Usuario usuario = usuarios.findById(sesion.usuarioId()).orElse(null);
        if (usuario == null || !usuario.isActivo() || usuario.isEsAdminTecnico() != sesion.adminTecnico()) {
            return false;
        }
        if (sesion.ambitoId() == null) {
            return true;
        }
        return perfiles.deUsuarioEnAmbito(sesion.usuarioId(), sesion.ambitoId())
                .filter(ua -> ua.vigente() && ua.getPerfil() == sesion.perfil())
                .isPresent();
    }
}
