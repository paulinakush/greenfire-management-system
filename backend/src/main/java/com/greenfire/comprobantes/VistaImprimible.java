package com.greenfire.comprobantes;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import com.greenfire.clientes.TipoDocumento;
import com.greenfire.fiscal.CondicionIva;

/**
 * El comprobante en HTML para imprimir o guardar como PDF desde el navegador (punto 8.4 del plan): sin librería
 * de PDF ni JavaScript. Muestra el IVA discriminado solo si el emisor lo discrimina, y nunca datos de costo (los
 * comprobantes no los tienen). Todo texto que viene de la base se escapa.
 */
@Component
public class VistaImprimible {

    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ZoneId zona;

    public VistaImprimible(@Value("${greenfire.zona-horaria}") String zona) {
        this.zona = ZoneId.of(zona);
    }

    public String html(Comprobante c) {
        CalculoComprobante.Importes importes = CalculoComprobante.calcular(c);
        boolean discrimina = c.discriminaIva();
        String titulo = c.getTipo().nombre() + " " + c.getLetra() + " " + c.numeroCompleto();
        StringBuilder h = new StringBuilder(4096);
        h.append("""
                <!doctype html>
                <html lang="es">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>""").append(e(titulo)).append("</title>\n<style>").append(ESTILO).append("</style>\n</head>\n<body>\n");
        h.append("<main class=\"hoja\">\n");

        // Encabezado: emisor, letra, tipo y número
        h.append("<header class=\"encabezado\">\n<div class=\"emisor\">\n");
        h.append("<p class=\"nombre\">").append(e(c.getEmisorNombre())).append("</p>\n");
        h.append("<p>").append(e(c.getEmisorRazonSocial())).append("</p>\n");
        h.append("<p>CUIT ").append(e(cuit(c.getEmisorCuit()))).append("</p>\n");
        h.append("<p>").append(e(condicion(c.getEmisorCondicionIva()))).append("</p>\n");
        if (c.getEmisorDomicilioFiscal() != null) {
            h.append("<p>Domicilio fiscal: ").append(e(c.getEmisorDomicilioFiscal())).append("</p>\n");
        }
        if (c.getEmisorDireccion() != null) {
            h.append("<p>").append(e(c.getEmisorDireccion())).append("</p>\n");
        }
        h.append("</div>\n<div class=\"letra\" aria-label=\"Letra\">").append(e(c.getLetra().name())).append("</div>\n");
        h.append("<div class=\"datos\">\n<p class=\"tipo\">").append(e(c.getTipo().nombre())).append("</p>\n");
        h.append("<p>N.º <strong>").append(e(c.numeroCompleto())).append("</strong></p>\n");
        h.append("<p>Fecha: ").append(e(FECHA_HORA.format(c.getFecha().atZoneSameInstant(zona)))).append("</p>\n");
        if (c.getFechaVencimiento() != null) {
            h.append("<p>Vencimiento: ").append(e(FECHA.format(c.getFechaVencimiento()))).append("</p>\n");
        }
        h.append("</div>\n</header>\n");

        if (c.leyenda() != null) {
            h.append("<p class=\"leyenda\">").append(e(c.leyenda())).append("</p>\n");
        }
        if (c.getEstado() == EstadoComprobante.anulado) {
            h.append("<p class=\"anulado\">ANULADO el ")
                    .append(e(FECHA_HORA.format(c.getAnuladoAt().atZoneSameInstant(zona))))
                    .append(". Motivo: ").append(e(c.getMotivoAnulacion())).append("</p>\n");
        }

        // Receptor
        h.append("<section class=\"receptor\">\n");
        h.append("<p><span>Cliente:</span> ")
                .append(e(c.getReceptorRazonSocial() == null ? "Consumidor final" : c.getReceptorRazonSocial()))
                .append("</p>\n");
        if (c.getReceptorDocumento() != null) {
            h.append("<p><span>").append(c.getReceptorDocumentoTipo() == TipoDocumento.cuit ? "CUIT" : "DNI")
                    .append(":</span> ")
                    .append(e(c.getReceptorDocumentoTipo() == TipoDocumento.cuit ? cuit(c.getReceptorDocumento())
                            : c.getReceptorDocumento()))
                    .append("</p>\n");
        }
        h.append("<p><span>Condición frente al IVA:</span> ").append(e(condicion(c.getReceptorCondicionIva())))
                .append("</p>\n");
        if (c.getReceptorDomicilio() != null) {
            h.append("<p><span>Domicilio:</span> ").append(e(c.getReceptorDomicilio())).append("</p>\n");
        }
        if (c.getAsociado() != null) {
            Comprobante a = c.getAsociado();
            h.append("<p><span>Comprobante asociado:</span> ").append(e(a.getTipo().nombre() + " " + a.getLetra()
                    + " " + a.numeroCompleto())).append("</p>\n");
        }
        h.append("</section>\n");

        // Renglones
        String precio = !discrimina ? "Precio unit." : c.isPreciosConIva() ? "Precio unit. (con IVA)"
                : "Precio unit. (sin IVA)";
        h.append("<table>\n<thead><tr><th>Descripción</th><th class=\"num\">Cantidad</th><th class=\"num\">")
                .append(e(precio)).append("</th>");
        if (discrimina) {
            h.append("<th class=\"num\">IVA</th>");
        }
        h.append("<th class=\"num\">Subtotal</th></tr></thead>\n<tbody>\n");
        for (DetalleComprobante d : c.getRenglones()) {
            h.append("<tr><td>").append(e(d.getDescripcion())).append("</td><td class=\"num\">")
                    .append(e(cantidad(d.getCantidad()))).append("</td><td class=\"num\">")
                    .append(e(pesos(d.getPrecioUnitario()))).append("</td>");
            if (discrimina) {
                h.append("<td class=\"num\">").append(e(porcentaje(d.getAlicuotaIva()))).append("</td>");
            }
            h.append("<td class=\"num\">").append(e(pesos(d.getSubtotal()))).append("</td></tr>\n");
        }
        h.append("</tbody>\n</table>\n");

        // Totales
        h.append("<dl class=\"totales\">\n");
        if (discrimina) {
            h.append(fila("Neto gravado", c.getNeto()));
            for (CalculoComprobante.IvaAlicuota a : importes.porAlicuota()) {
                h.append(fila("IVA " + porcentaje(a.alicuota()), a.iva()));
            }
        }
        h.append("<div class=\"total\"><dt>Total</dt><dd>").append(e(pesos(c.getTotal()))).append("</dd></div>\n");
        h.append("</dl>\n");

        if (c.getObservaciones() != null) {
            h.append("<p class=\"observaciones\">").append(e(c.getObservaciones())).append("</p>\n");
        }
        if (c.leyenda() != null) {
            h.append("<footer><p class=\"leyenda\">").append(e(c.leyenda())).append("</p></footer>\n");
        }
        h.append("</main>\n</body>\n</html>\n");
        return h.toString();
    }

    private static String fila(String nombre, BigDecimal importe) {
        return "<div><dt>" + e(nombre) + "</dt><dd>" + e(pesos(importe)) + "</dd></div>\n";
    }

    private static String e(String texto) {
        return texto == null ? "" : HtmlUtils.htmlEscape(texto, "UTF-8");
    }

    private static DecimalFormat formato(String patron) {
        return new DecimalFormat(patron, DecimalFormatSymbols.getInstance(Locale.forLanguageTag("es-AR")));
    }

    static String pesos(BigDecimal importe) {
        return "$ " + formato("#,##0.00").format(importe);
    }

    private static String cantidad(BigDecimal cantidad) {
        return formato("#,##0.###").format(cantidad);
    }

    private static String porcentaje(BigDecimal alicuota) {
        return formato("0.##").format(alicuota) + " %";
    }

    private static String cuit(String cuit) {
        return cuit != null && cuit.length() == 11
                ? cuit.substring(0, 2) + "-" + cuit.substring(2, 10) + "-" + cuit.substring(10)
                : cuit;
    }

    static String condicion(CondicionIva condicion) {
        return switch (condicion) {
            case responsable_inscripto -> "IVA Responsable Inscripto";
            case monotributo -> "Responsable Monotributo";
            case exento -> "IVA Exento";
            case consumidor_final -> "Consumidor Final";
        };
    }

    /** Colores de la identidad visual: verde institucional, verde profundo y gris. */
    private static final String ESTILO = """
            :root { color-scheme: light; }
            * { box-sizing: border-box; }
            body { margin: 0; background: #f2f2f2; color: #1A312B; font: 14px/1.4 system-ui, -apple-system, "Segoe UI", Roboto, Arial, sans-serif; }
            .hoja { max-width: 800px; margin: 24px auto; padding: 32px; background: #fff; border-top: 6px solid #00AE4C; }
            .encabezado { display: grid; grid-template-columns: 1fr auto 1fr; gap: 16px; align-items: start; border-bottom: 1px solid #C3C3C3; padding-bottom: 16px; }
            .encabezado p { margin: 2px 0; }
            .nombre { font-size: 20px; font-weight: 700; color: #29683D; }
            .letra { width: 64px; height: 64px; border: 2px solid #1A312B; display: flex; align-items: center; justify-content: center; font-size: 40px; font-weight: 700; }
            .datos { text-align: right; }
            .tipo { font-size: 18px; font-weight: 700; text-transform: uppercase; }
            .leyenda { text-align: center; font-weight: 700; letter-spacing: .02em; margin: 12px 0; }
            .anulado { text-align: center; color: #b00020; border: 2px solid #b00020; padding: 8px; font-weight: 700; }
            .receptor { border-bottom: 1px solid #C3C3C3; padding: 8px 0 12px; }
            .receptor p { margin: 2px 0; }
            .receptor span { color: #29683D; font-weight: 600; }
            table { width: 100%; border-collapse: collapse; margin-top: 16px; }
            th { text-align: left; border-bottom: 2px solid #1A312B; padding: 6px 4px; font-size: 12px; text-transform: uppercase; }
            td { border-bottom: 1px solid #C3C3C3; padding: 6px 4px; vertical-align: top; }
            .num { text-align: right; white-space: nowrap; }
            .totales { margin: 16px 0 0 auto; width: 280px; }
            .totales div { display: flex; justify-content: space-between; padding: 2px 0; }
            .totales dt, .totales dd { margin: 0; }
            .totales .total { border-top: 2px solid #1A312B; margin-top: 4px; padding-top: 6px; font-size: 18px; font-weight: 700; }
            .observaciones { margin-top: 16px; white-space: pre-wrap; }
            footer { margin-top: 24px; border-top: 1px solid #C3C3C3; }
            @media (max-width: 600px) { .hoja { margin: 0; padding: 16px; } .encabezado { grid-template-columns: 1fr auto; } .datos { grid-column: 1 / -1; text-align: left; } .totales { width: 100%; } }
            @media print { body { background: #fff; } .hoja { margin: 0; max-width: none; border-top: 0; padding: 0; } }
            """;
}
