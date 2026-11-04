package com.greenfire.entregas.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.greenfire.entregas.DetalleEntrega;
import com.greenfire.entregas.Entrega;
import com.greenfire.entregas.EstadoEntrega;
import com.greenfire.entregas.ResolucionDiferencia;

/**
 * La entrega como la ve el dueño del local que la recibe: con precios, que son su costo, y su ubicación de
 * destino. Nada del stock de la fábrica: no lleva la ubicación de origen ni la lista.
 */
public record EntregaLocalResponse(
        Long id,
        EstadoEntrega estado,
        String origenNombre,
        Long ubicacionDestinoId,
        String ubicacionDestinoNombre,
        String usuarioEnvioNombre,
        OffsetDateTime fechaEnvio,
        String usuarioRecepcionNombre,
        OffsetDateTime fechaRecepcion,
        OffsetDateTime anuladaAt,
        BigDecimal total,
        String observaciones,
        String observacionesRecepcion,
        ResolucionDiferencia resolucionDiferencia,
        String motivoResolucion,
        OffsetDateTime diferenciaResueltaAt,
        List<EntregaResponse.Renglon> renglones) implements EntregaVista {

    public static EntregaLocalResponse de(Entrega e, List<DetalleEntrega> detalle) {
        return new EntregaLocalResponse(e.getId(), e.getEstado(), e.getOrigen().getNombre(),
                e.getUbicacionDestino() == null ? null : e.getUbicacionDestino().getId(),
                e.getUbicacionDestino() == null ? null : e.getUbicacionDestino().getNombre(),
                EntregaResponse.nombre(e.getUsuarioEnvio()), e.getFechaEnvio(),
                EntregaResponse.nombre(e.getUsuarioRecepcion()), e.getFechaRecepcion(), e.getAnuladaAt(),
                e.getTotal(), e.getObservaciones(), e.getObservacionesRecepcion(), e.getResolucionDiferencia(),
                e.getMotivoResolucion(), e.getDiferenciaResueltaAt(),
                detalle.stream().map(EntregaResponse.Renglon::de).toList());
    }
}
