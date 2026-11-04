package com.greenfire.catalogo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CategoriaRequest(
        @NotBlank @Size(max = 120) String nombre,
        @Size(max = 2000) String descripcion) {
}
