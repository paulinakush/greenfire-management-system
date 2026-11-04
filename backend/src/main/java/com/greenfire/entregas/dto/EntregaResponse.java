package com.greenfire.entregas.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.greenfire.catalogo.UnidadMedida;
import com.greenfire.entregas.DetalleEntrega;
import com.greenfire.entregas.Entrega;
import com.greenfire.entregas.EstadoEntrega;
import com.greenfire.entregas.ResolucionDiferencia;
import com.greenfire.usuarios.Usuario;

/**
 * La entrega como la ve la fábrica, que la envió: con precios, su ubicación de origen y su lista. Nada del stock
 * del local: no lleva la ubicación de destino.
 */
public record EntregaResponse(
        Long id,
        EstadoEntrega estado,
        Long localId,
        String localNombre,
        Long ubicacionOrigenId,
        String ubicacionOrigenNombre,
        Long listaPreciosId,
        String listaPreciosNombre,
        String usuarioEnvioNombre,
        OffsetDateTime fechaEnvio,
        String usuarioRecepcionNombre,
        OffsetDateTime fechaRecepcion,
        String usuarioAnulacionNombre,
        OffsetDateTime anuladaAt,
        BigDecimal total,
        String observaciones,
        String observacionesRecepcion,
        ResolucionDiferencia resolucionDiferencia,
        String motivoResolucion,
        OffsetDateTime diferenciaResueltaAt,
        List<Renglon> renglones) implements EntregaVista {

    public record Renglon(Long id, Long articuloId, String articuloNombre, UnidadMedida unidadMedida, Long loteId,
            BigDecimal cantidadEnviada, BigDecimal cantidadRecibida, BigDecimal precioUnitario, BigDecimal subtotal) {

        public static Renglon de(DetalleEntrega d) {
            return new Renglon(d.getId(), d.getArticulo().getId(), d.getArticulo().getNombre(),
                    d.getArticulo().getUnidadMedida(), d.getLoteId(), d.getCantidadEnviada(), d.getCantidadRecibida(),
                    d.getPrecioUnitario(), d.getSubtotal());
        }
    }

    public static EntregaResponse de(Entrega e, List<DetalleEntrega> detalle) {
        return new EntregaResponse(e.getId(), e.getEstado(), e.getDestino().getId(), e.getDestino().getNombre(),
                e.getUbicacionOrigen().getId(), e.getUbicacionOrigen().getNombre(), e.getLista().getId(),
                e.getLista().getNombre(), nombre(e.getUsuarioEnvio()), e.getFechaEnvio(),
                nombre(e.getUsuarioRecepcion()), e.getFechaRecepcion(), nombre(e.getUsuarioAnulacion()),
                e.getAnuladaAt(), e.getTotal(), e.getObservaciones(), e.getObservacionesRecepcion(),
                e.getResolucionDiferencia(), e.getMotivoResolucion(), e.getDiferenciaResueltaAt(),
                detalle.stream().map(Renglon::de).toList());
    }

    static String nombre(Usuario u) {
        return u == null ? null : u.getNombre();
    }
}
