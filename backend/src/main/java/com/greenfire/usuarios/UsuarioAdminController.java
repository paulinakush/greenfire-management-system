package com.greenfire.usuarios;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.greenfire.usuarios.dto.DatosUsuarioRequest;
import com.greenfire.usuarios.dto.DuenoRequest;
import com.greenfire.usuarios.dto.PasswordRequest;
import com.greenfire.usuarios.dto.PerfilRequest;
import com.greenfire.usuarios.dto.UsuarioResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/admin/usuarios")
public class UsuarioAdminController {

    private final UsuarioService service;

    public UsuarioAdminController(UsuarioService service) {
        this.service = service;
    }

    @GetMapping
    public List<UsuarioResponse> listar() {
        return service.listar();
    }

    @GetMapping("/{id}")
    public UsuarioResponse obtener(@PathVariable Long id) {
        return service.obtener(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UsuarioResponse crearDueno(@Valid @RequestBody DuenoRequest req) {
        return service.crearDueno(req);
    }

    @PutMapping("/{id}")
    public UsuarioResponse editar(@PathVariable Long id, @Valid @RequestBody DatosUsuarioRequest req) {
        return service.editar(id, req);
    }

    @PostMapping("/{id}/activar")
    public UsuarioResponse activar(@PathVariable Long id) {
        return service.cambiarActivo(id, true);
    }

    @PostMapping("/{id}/desactivar")
    public UsuarioResponse desactivar(@PathVariable Long id) {
        return service.cambiarActivo(id, false);
    }

    @PostMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restablecerPassword(@PathVariable Long id, @Valid @RequestBody PasswordRequest req) {
        service.restablecerPassword(id, req.password());
    }

    @PostMapping("/{id}/perfiles")
    public UsuarioResponse agregarPerfil(@PathVariable Long id, @Valid @RequestBody PerfilRequest req) {
        return service.agregarPerfil(id, req);
    }

    @PostMapping("/{id}/perfiles/{ambitoId}/activar")
    public UsuarioResponse activarPerfil(@PathVariable Long id, @PathVariable Long ambitoId) {
        return service.cambiarActivoPerfil(id, ambitoId, true);
    }

    @PostMapping("/{id}/perfiles/{ambitoId}/desactivar")
    public UsuarioResponse desactivarPerfil(@PathVariable Long id, @PathVariable Long ambitoId) {
        return service.cambiarActivoPerfil(id, ambitoId, false);
    }
}
