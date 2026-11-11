package com.greenfire.ventas;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.hibernate.annotations.Immutable;

import com.greenfire.caja.SesionCaja;
import com.greenfire.comprobantes.Comprobante;
import com.greenfire.comun.EntidadBase;
import com.greenfire.usuarios.Usuario;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Devolución parcial de una venta confirmada, con su nota de crédito X. Solo se inserta (V14). */
@Entity
@Immutable
@Table(name = "devoluciones_venta")
public class DevolucionVenta extends EntidadBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Venta venta;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Usuario usuario;
    private OffsetDateTime fechaHora;
    private String motivo;
    private BigDecimal total;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "nota_credito_id")
    private Comprobante notaCredito;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sesion_caja_id")
    private SesionCaja sesionCaja;

    protected DevolucionVenta() {
    }

    DevolucionVenta(Venta venta, Usuario usuario, String motivo, Comprobante notaCredito, SesionCaja sesionCaja) {
        this.venta = venta;
        this.usuario = usuario;
        this.fechaHora = OffsetDateTime.now();
        this.motivo = motivo;
        this.total = notaCredito.getTotal();
        this.notaCredito = notaCredito;
        this.sesionCaja = sesionCaja;
    }

    public Venta getVenta() {
        return venta;
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public OffsetDateTime getFechaHora() {
        return fechaHora;
    }

    public String getMotivo() {
        return motivo;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public Comprobante getNotaCredito() {
        return notaCredito;
    }

    /** La caja de donde salió el dinero; null en la fábrica. */
    public SesionCaja getSesionCaja() {
        return sesionCaja;
    }
}
