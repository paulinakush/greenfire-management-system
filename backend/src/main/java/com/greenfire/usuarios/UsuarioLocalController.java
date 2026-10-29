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
import com.greenfire.usuarios.dto.EmpleadoRequest;
import com.greenfire.usuarios.dto.UsuarioLocalResponse;

import jakarta.validation.Valid;

/** Usuarios del local en el que trabaja el dueño. */
@RestController
@RequestMapping("/api/usuarios")
public class UsuarioLocalController {

    private final UsuarioService service;

    public UsuarioLocalController(UsuarioService service) {
        this.service = service;
    }

    @GetMapping
    public List<UsuarioLocalResponse> listar() {
        return service.listarDeMiLocal();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UsuarioLocalResponse crearEmpleado(@Valid @RequestBody EmpleadoRequest req) {
        return service.crearEmpleado(req);
    }

    @PutMapping("/{id}")
    public UsuarioLocalResponse editarEmpleado(@PathVariable Long id, @Valid @RequestBody DatosUsuarioRequest req) {
        return service.editarEmpleado(id, req);
    }

    @PostMapping("/{id}/activar")
    public UsuarioLocalResponse activar(@PathVariable Long id) {
        return service.cambiarActivoEmpleado(id, true);
    }

    @PostMapping("/{id}/desactivar")
    public UsuarioLocalResponse desactivar(@PathVariable Long id) {
        return service.cambiarActivoEmpleado(id, false);
    }
}
