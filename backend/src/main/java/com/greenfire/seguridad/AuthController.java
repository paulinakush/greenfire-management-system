package com.greenfire.seguridad;

import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.greenfire.comun.NoAutenticadoException;
import com.greenfire.seguridad.dto.ElegirAmbitoRequest;
import com.greenfire.seguridad.dto.LoginRequest;
import com.greenfire.seguridad.dto.RecuperarRequest;
import com.greenfire.seguridad.dto.RestablecerRequest;
import com.greenfire.seguridad.dto.SesionResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final LoginService login;
    private final RecuperacionService recuperacion;

    public AuthController(LoginService login, RecuperacionService recuperacion) {
        this.login = login;
        this.recuperacion = recuperacion;
    }

    /** Deja la cookie XSRF-TOKEN en el navegador antes del primer POST. */
    @GetMapping("/csrf")
    public CsrfToken csrf(CsrfToken token) {
        return token;
    }

    @PostMapping("/login")
    public SesionResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        return login.login(req.email(), req.password(), request)
                .orElseThrow(() -> new NoAutenticadoException(LoginService.LOGIN_FALLIDO));
    }

    @PostMapping("/ambito")
    public SesionResponse elegirAmbito(@Valid @RequestBody ElegirAmbitoRequest req, HttpServletRequest request) {
        return login.elegirAmbito(req.ambitoId(), request);
    }

    @GetMapping("/me")
    public SesionResponse me() {
        return login.actual();
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        login.logout(request);
    }

    @PostMapping("/recuperar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recuperar(@Valid @RequestBody RecuperarRequest req) {
        recuperacion.solicitar(req.email());
    }

    @PostMapping("/restablecer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restablecer(@Valid @RequestBody RestablecerRequest req) {
        recuperacion.restablecer(req.token(), req.password());
    }
}
