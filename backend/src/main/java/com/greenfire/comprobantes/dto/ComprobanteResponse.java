package com.greenfire.comprobantes.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.greenfire.clientes.TipoDocumento;
import com.greenfire.comprobantes.CalculoComprobante;
import com.greenfire.comprobantes.Comprobante;
import com.greenfire.comprobantes.DetalleComprobante;
import com.greenfire.comprobantes.EstadoComprobante;
import com.greenfire.fiscal.CondicionIva;
import com.greenfire.fiscal.LetraComprobante;
import com.greenfire.fiscal.TipoComprobante;

/**
 * Un comprobante completo, tal como se emitió. El IVA por alícuota viene solo si el emisor discrimina IVA. No
 * tiene datos de costo: los comprobantes no los guardan, así que es el mismo para el dueño y el empleado.
 */
public record ComprobanteResponse(
        Long id,
        TipoComprobante tipo,
        LetraComprobante letra,
        String leyenda,
        String numeroCompleto,
        Integer puntoVenta,
        Integer numero,
        OffsetDateTime fecha,
        LocalDate fechaVencimiento,
        EstadoComprobante estado,
        Emisor emisor,
        Receptor receptor,
        boolean discriminaIva,
        boolean preciosConIva,
        List<Renglon> renglones,
        List<CalculoComprobante.IvaAlicuota> ivaPorAlicuota,
        BigDecimal neto,
        BigDecimal iva,
        BigDecimal total,
        Long comprobanteAsociadoId,
        String observaciones,
        String emitidoPor,
        Anulacion anulacion) {

    public record Emisor(String razonSocial, String cuit, CondicionIva condicionIva, String domicilioFiscal,
            String nombre, String direccion) {
    }

    public record Receptor(Long clienteId, String razonSocial, TipoDocumento documentoTipo, String documento,
            CondicionIva condicionIva, String domicilio) {
    }

    public record Renglon(Long articuloId, String descripcion, BigDecimal cantidad, BigDecimal precioUnitario,
            BigDecimal alicuotaIva, BigDecimal subtotal) {

        static Renglon de(DetalleComprobante d) {
            return new Renglon(d.getArticulo() == null ? null : d.getArticulo().getId(), d.getDescripcion(),
                    d.getCantidad(), d.getPrecioUnitario(), d.getAlicuotaIva(), d.getSubtotal());
        }
    }

    public record Anulacion(OffsetDateTime fecha, String usuario, String motivo) {
    }

    public static ComprobanteResponse de(Comprobante c) {
        CalculoComprobante.Importes importes = CalculoComprobante.calcular(c);
        return new ComprobanteResponse(c.getId(), c.getTipo(), c.getLetra(), c.leyenda(), c.numeroCompleto(),
                c.getPuntoVenta().getNumero(), c.getNumero(), c.getFecha(), c.getFechaVencimiento(), c.getEstado(),
                new Emisor(c.getEmisorRazonSocial(), c.getEmisorCuit(), c.getEmisorCondicionIva(),
                        c.getEmisorDomicilioFiscal(), c.getEmisorNombre(), c.getEmisorDireccion()),
                new Receptor(c.getCliente() == null ? null : c.getCliente().getId(), c.getReceptorRazonSocial(),
                        c.getReceptorDocumentoTipo(), c.getReceptorDocumento(), c.getReceptorCondicionIva(),
                        c.getReceptorDomicilio()),
                c.discriminaIva(), c.isPreciosConIva(), c.getRenglones().stream().map(Renglon::de).toList(),
                importes.porAlicuota(), c.getNeto(), c.getIva(), c.getTotal(),
                c.getAsociado() == null ? null : c.getAsociado().getId(), c.getObservaciones(),
                c.getUsuario().getNombre(),
                c.getEstado() == EstadoComprobante.anulado
                        ? new Anulacion(c.getAnuladoAt(), c.getAnuladoPor().getNombre(), c.getMotivoAnulacion())
                        : null);
    }
}
