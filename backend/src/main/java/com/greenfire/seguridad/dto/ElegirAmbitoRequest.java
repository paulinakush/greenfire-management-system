package com.greenfire.seguridad.dto;

import jakarta.validation.constraints.NotNull;

public record ElegirAmbitoRequest(@NotNull Long ambitoId) {
}
