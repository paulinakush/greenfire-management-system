package com.greenfire.seguridad;

import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.greenfire.usuarios.UsuarioAmbitoRepository;
import com.greenfire.usuarios.UsuarioRepository;

/**
 * Spring Security aporta CSRF, encabezados de seguridad y la cadena de filtros. El login es propio
 * ({@link LoginService}) y los permisos se validan en cada servicio con {@link Acceso}, a partir de la
 * {@link Sesion} guardada en la sesión HTTP.
 */
@Configuration
public class SeguridadConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain seguridad(HttpSecurity http, UsuarioRepository usuarios, UsuarioAmbitoRepository perfiles,
            PlatformTransactionManager transacciones) throws Exception {
        TransactionTemplate lectura = new TransactionTemplate(transacciones);
        lectura.setReadOnly(true);

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e.accessDeniedHandler((request, response, ex) -> {
                    // Solo llega acá un token CSRF ausente o inválido.
                    response.setStatus(403);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.getWriter().write("{\"status\":403,\"error\":\"FORBIDDEN\","
                            + "\"mensaje\":\"Falta el token CSRF o no es válido.\",\"campos\":null}");
                }))
                .addFilterBefore(new FiltroSesionVigente(usuarios, perfiles, lectura), AuthorizationFilter.class);
        return http.build();
    }
}
