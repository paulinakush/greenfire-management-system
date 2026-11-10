package com.greenfire.seguridad.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import jakarta.validation.constraints.NotBlank;

/** {@code usuario} acepta el nombre de usuario o el email (también llega como "email"). */
public record LoginRequest(@NotBlank @JsonAlias("email") String usuario, @NotBlank String password) {
}
