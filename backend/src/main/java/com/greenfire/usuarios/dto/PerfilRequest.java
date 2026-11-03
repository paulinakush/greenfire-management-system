package com.greenfire.usuarios.dto;

import com.greenfire.seguridad.Perfil;

import jakarta.validation.constraints.NotNull;

public record PerfilRequest(@NotNull Long ambitoId, @NotNull Perfil perfil) {
}
