package com.greenfire.entregas.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.greenfire.catalogo.UnidadMedida;
import com.greenfire.entregas.DetalleEntrega;
import com.greenfire.entregas.Entrega;
import com.greenfire.entregas.EstadoEntrega;

/** La entrega como la ve el empleado que la recibe: qué llegó y cuánto, sin precios, subtotales ni total. */
public record RecepcionEmpleadoResponse(
        Long id,
        EstadoEntrega estado,
        String origenNombre,
        Long ubicacionDestinoId,
        String ubicacionDestinoNombre,
        OffsetDateTime fechaEnvio,
        String usuarioRecepcionNombre,
        OffsetDateTime fechaRecepcion,
        String observaciones,
        String observacionesRecepcion,
        List<Renglon> renglones) implements EntregaVista {

    public record Renglon(Long id, Long articuloId, String articuloNombre, UnidadMedida unidadMedida, Long loteId,
            BigDecimal cantidadEnviada, BigDecimal cantidadRecibida) {

        public static Renglon de(DetalleEntrega d) {
            return new Renglon(d.getId(), d.getArticulo().getId(), d.getArticulo().getNombre(),
                    d.getArticulo().getUnidadMedida(), d.getLoteId(), d.getCantidadEnviada(), d.getCantidadRecibida());
        }
    }

    public static RecepcionEmpleadoResponse de(Entrega e, List<DetalleEntrega> detalle) {
        return new RecepcionEmpleadoResponse(e.getId(), e.getEstado(), e.getOrigen().getNombre(),
                e.getUbicacionDestino() == null ? null : e.getUbicacionDestino().getId(),
                e.getUbicacionDestino() == null ? null : e.getUbicacionDestino().getNombre(), e.getFechaEnvio(),
                EntregaResponse.nombre(e.getUsuarioRecepcion()), e.getFechaRecepcion(), e.getObservaciones(),
                e.getObservacionesRecepcion(), detalle.stream().map(Renglon::de).toList());
    }
}
