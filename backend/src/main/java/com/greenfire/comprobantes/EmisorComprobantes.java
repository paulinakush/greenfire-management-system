package com.greenfire.comprobantes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.greenfire.ambitos.Ambito;
import com.greenfire.auditoria.Auditor;
import com.greenfire.catalogo.Articulo;
import com.greenfire.clientes.Cliente;
import com.greenfire.comun.ReglaNegocioException;
import com.greenfire.fiscal.LetraComprobante;
import com.greenfire.fiscal.PuntoVentaRepository;
import com.greenfire.fiscal.TipoComprobante;
import com.greenfire.usuarios.UsuarioRepository;

/**
 * Punto único para emitir y anular comprobantes, como {@link com.greenfire.stock.RegistroStock} para el stock. No
 * hay alta directa por la API: lo llaman las ventas, las entregas y las cobranzas, que validan los permisos antes.
 * <p>
 * Corre siempre dentro de la transacción de la operación (MANDATORY): si la operación falla después de emitir, el
 * comprobante y su número se revierten con ella. En esta etapa emite solo comprobantes X (no fiscales), con la
 * leyenda "Documento no válido como factura"; las facturas quedan para la etapa 6, con ARCA.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class EmisorComprobantes {

    private static final String ENTIDAD = "comprobante";
    private static final BigDecimal CIEN = new BigDecimal("100");
    private static final Set<TipoComprobante> CON_ASOCIADO = EnumSet.of(TipoComprobante.nota_credito,
            TipoComprobante.nota_debito);

    /** Un renglón. La descripción, si falta, es el nombre del artículo; la alícuota, la del artículo. */
    public record Renglon(Articulo articulo, String descripcion, BigDecimal cantidad, BigDecimal precioUnitario,
            BigDecimal alicuotaIva) {

        public static Renglon de(Articulo articulo, BigDecimal cantidad, BigDecimal precioUnitario) {
            return new Renglon(articulo, null, cantidad, precioUnitario, null);
        }

        /** Un renglón sin artículo, por ejemplo el de un recibo o un ajuste. */
        public static Renglon libre(String descripcion, BigDecimal importe, BigDecimal alicuotaIva) {
            return new Renglon(null, descripcion, BigDecimal.ONE, importe, alicuotaIva);
        }
    }

    /**
     * Lo que se emite. {@code preciosConIva} dice si los precios de los renglones incluyen IVA (como la lista de la
     * que salen); solo cuenta si el emisor discrimina IVA: para los demás, los precios son finales. {@code cliente}
     * es null para un consumidor final sin identificar. {@code asociado}, solo en notas de crédito y de débito.
     */
    public record Emision(Ambito ambito, TipoComprobante tipo, Cliente cliente, boolean preciosConIva,
            List<Renglon> renglones, Comprobante asociado, LocalDate fechaVencimiento, String observaciones,
            Long usuarioId) {

        public Emision(Ambito ambito, TipoComprobante tipo, Cliente cliente, boolean preciosConIva,
                List<Renglon> renglones, Long usuarioId) {
            this(ambito, tipo, cliente, preciosConIva, renglones, null, null, null, usuarioId);
        }
    }

    private final NumeradorComprobantes numerador;
    private final ComprobanteRepository comprobantes;
    private final DetalleComprobanteRepository detalles;
    private final PuntoVentaRepository puntosVenta;
    private final UsuarioRepository usuarios;
    private final Auditor auditor;

    public EmisorComprobantes(NumeradorComprobantes numerador, ComprobanteRepository comprobantes,
            DetalleComprobanteRepository detalles, PuntoVentaRepository puntosVenta, UsuarioRepository usuarios,
            Auditor auditor) {
        this.numerador = numerador;
        this.comprobantes = comprobantes;
        this.detalles = detalles;
        this.puntosVenta = puntosVenta;
        this.usuarios = usuarios;
        this.auditor = auditor;
    }

    /** Valida todo antes de tomar el número; después numera, guarda y deja la auditoría. */
    public Comprobante emitir(Emision e) {
        validar(e);
        LetraComprobante letra = LetraComprobante.X;
        NumeradorComprobantes.Numero numero = numerador.siguiente(e.ambito(), e.tipo(), letra);

        Comprobante c = new Comprobante(e.tipo(), letra, puntosVenta.getReferenceById(numero.puntoVentaId()),
                numero.numero(), usuarios.getReferenceById(e.usuarioId()));
        c.copiarReceptor(e.cliente());
        c.fijarOtros(e.asociado(), e.fechaVencimiento(), texto(e.observaciones()));
        boolean preciosConIva = e.preciosConIva() || !c.discriminaIva();
        List<DetalleComprobante> renglones = e.renglones().stream().map(r -> renglon(c, r)).toList();
        CalculoComprobante.Importes importes = CalculoComprobante.calcular(c.discriminaIva(), preciosConIva,
                renglones.stream().map(r -> new CalculoComprobante.Linea(r.getSubtotal(), r.getAlicuotaIva())).toList());
        c.fijarImportes(preciosConIva, importes.neto(), importes.iva(), importes.total());
        comprobantes.save(c);
        c.getRenglones().addAll(detalles.saveAll(renglones));

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("tipo", c.getTipo());
        detalle.put("letra", c.getLetra());
        detalle.put("puntoVenta", c.getPuntoVenta().getNumero());
        detalle.put("numero", c.getNumero());
        detalle.put("clienteId", e.cliente() == null ? null : e.cliente().getId());
        detalle.put("asociadoId", e.asociado() == null ? null : e.asociado().getId());
        detalle.put("total", c.getTotal());
        auditor.registrar("emitir", ENTIDAD, c.getId(), List.of(e.ambito().getId()), detalle);
        return c;
    }

    /** Pasa un comprobante emitido a anulado, con motivo. Lo llama la operación que se anula (por ejemplo, la venta). */
    public Comprobante anular(Comprobante c, String motivo, Long usuarioId) {
        if (!StringUtils.hasText(motivo)) {
            throw new ReglaNegocioException("Para anular un comprobante hace falta un motivo.");
        }
        if (c.getEstado() == EstadoComprobante.anulado) {
            throw new ReglaNegocioException("El comprobante %s %s %s ya está anulado."
                    .formatted(c.getTipo().nombre(), c.getLetra(), c.numeroCompleto()));
        }
        c.anular(usuarios.getReferenceById(usuarioId), motivo.trim());
        comprobantes.flush();

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("tipo", c.getTipo());
        detalle.put("letra", c.getLetra());
        detalle.put("puntoVenta", c.getPuntoVenta().getNumero());
        detalle.put("numero", c.getNumero());
        detalle.put("motivo", c.getMotivoAnulacion());
        auditor.registrar("anular", ENTIDAD, c.getId(), List.of(c.getPuntoVenta().getAmbito().getId()), detalle);
        return c;
    }

    private static void validar(Emision e) {
        if (e.tipo() == TipoComprobante.factura) {
            throw new ReglaNegocioException("Las facturas se emiten con ARCA, en la etapa 6.");
        }
        if (e.renglones() == null || e.renglones().isEmpty()) {
            throw new ReglaNegocioException("El comprobante no tiene renglones.");
        }
        for (Renglon r : e.renglones()) {
            String nombre = descripcion(r);
            if (!StringUtils.hasText(nombre)) {
                throw new ReglaNegocioException("Cada renglón lleva un artículo o una descripción.");
            }
            if (r.cantidad() == null || r.cantidad().signum() <= 0) {
                throw new ReglaNegocioException("La cantidad de '" + nombre + "' tiene que ser mayor que cero.");
            }
            if (r.cantidad().stripTrailingZeros().scale() > 3) {
                throw new ReglaNegocioException("La cantidad de '" + nombre + "' admite hasta tres decimales.");
            }
            if (r.precioUnitario() == null || r.precioUnitario().signum() < 0) {
                throw new ReglaNegocioException("El precio de '" + nombre + "' no puede ser negativo.");
            }
            BigDecimal alicuota = alicuota(r);
            if (alicuota == null || alicuota.signum() < 0 || alicuota.compareTo(CIEN) > 0) {
                throw new ReglaNegocioException("La alícuota de IVA de '" + nombre + "' no es válida.");
            }
            if (r.articulo() != null && !r.articulo().visiblePara(e.ambito())) {
                throw new ReglaNegocioException("'" + nombre + "' no es un artículo de " + e.ambito().getNombre() + ".");
            }
        }
        if (e.cliente() != null && !e.cliente().getAmbito().getId().equals(e.ambito().getId())) {
            throw new ReglaNegocioException("El cliente no es de " + e.ambito().getNombre() + ".");
        }
        if (e.asociado() != null) {
            if (!CON_ASOCIADO.contains(e.tipo())) {
                throw new ReglaNegocioException("Solo una nota de crédito o de débito lleva un comprobante asociado.");
            }
            if (!e.asociado().getPuntoVenta().getEntidadFiscal().getId()
                    .equals(e.ambito().getEntidadFiscal().getId())) {
                throw new ReglaNegocioException("El comprobante asociado es de otra entidad fiscal.");
            }
            if (e.asociado().getEstado() != EstadoComprobante.emitido) {
                throw new ReglaNegocioException("El comprobante asociado está anulado.");
            }
        }
    }

    private static DetalleComprobante renglon(Comprobante c, Renglon r) {
        BigDecimal precio = r.precioUnitario().setScale(2, RoundingMode.HALF_UP);
        return new DetalleComprobante(c, r.articulo(), descripcion(r).trim(), r.cantidad(), precio, alicuota(r),
                CalculoComprobante.subtotal(r.cantidad(), precio));
    }

    private static String descripcion(Renglon r) {
        return StringUtils.hasText(r.descripcion()) ? r.descripcion()
                : r.articulo() == null ? null : r.articulo().getNombre();
    }

    private static BigDecimal alicuota(Renglon r) {
        return r.alicuotaIva() != null ? r.alicuotaIva() : r.articulo() == null ? null : r.articulo().getAlicuotaIva();
    }

    private static String texto(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
