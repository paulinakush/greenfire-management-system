package com.greenfire.stock.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.greenfire.stock.ConteoStock;
import com.greenfire.stock.EstadoConteo;

public record ConteoResumenResponse(Long id, Long ubicacionId, String ubicacionNombre, LocalDate fecha,
        EstadoConteo estado, String observaciones, Long usuarioId, String usuarioNombre, OffsetDateTime cerradoAt,
        OffsetDateTime anuladoAt) {

    public static ConteoResumenResponse de(ConteoStock c) {
        return new ConteoResumenResponse(c.getId(), c.getUbicacion().getId(), c.getUbicacion().getNombre(),
                c.getFecha(), c.getEstado(), c.getObservaciones(), c.getUsuario().getId(), c.getUsuario().getNombre(),
                c.getCerradoAt(), c.getAnuladoAt());
    }
}
