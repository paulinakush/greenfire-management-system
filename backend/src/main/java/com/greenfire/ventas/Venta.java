package com.greenfire.ventas;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.hibernate.annotations.DynamicUpdate;

import com.greenfire.ambitos.Ambito;
import com.greenfire.caja.MedioPago;
import com.greenfire.caja.SesionCaja;
import com.greenfire.clientes.Cliente;
import com.greenfire.comprobantes.Comprobante;
import com.greenfire.comun.EntidadBase;
import com.greenfire.usuarios.Usuario;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Venta presencial de un local o venta de la fábrica a un mayorista. Solo se inserta, con su comprobante; después
 * solo pasa a anulada (V14). Las devoluciones parciales van aparte ({@link DevolucionVenta}).
 */
@Entity
@DynamicUpdate
@Table(name = "ventas")
public class Venta extends EntidadBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(updatable = false)
    private Ambito ambito;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(updatable = false)
    private Usuario usuario;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(updatable = false)
    private Cliente cliente;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sesion_caja_id", updatable = false)
    private SesionCaja sesionCaja;
    private OffsetDateTime fechaHora;
    @Enumerated(EnumType.STRING)
    private MedioPago medioPago;
    private LocalDate fechaCobro;
    private BigDecimal subtotal;
    private BigDecimal porcentajeDescuento;
    private BigDecimal descuento;
    private BigDecimal total;
    @Enumerated(EnumType.STRING)
    private EstadoVenta estado = EstadoVenta.confirmada;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(updatable = false)
    private Comprobante comprobante;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "anulada_por_usuario_id")
    private Usuario anuladaPor;
    private OffsetDateTime anuladaAt;
    private String motivoAnulacion;

    protected Venta() {
    }

    Venta(Ambito ambito, Usuario usuario, Cliente cliente, SesionCaja sesionCaja, MedioPago medioPago,
            LocalDate fechaCobro, BigDecimal subtotal, BigDecimal porcentajeDescuento, BigDecimal total,
            Comprobante comprobante) {
        this.ambito = ambito;
        this.usuario = usuario;
        this.cliente = cliente;
        this.sesionCaja = sesionCaja;
        this.fechaHora = OffsetDateTime.now();
        this.medioPago = medioPago;
        this.fechaCobro = fechaCobro;
        this.subtotal = subtotal;
        this.porcentajeDescuento = porcentajeDescuento;
        this.descuento = subtotal.subtract(total);
        this.total = total;
        this.comprobante = comprobante;
    }

    void anular(Usuario usuario, String motivo) {
        this.estado = EstadoVenta.anulada;
        this.anuladaPor = usuario;
        this.anuladaAt = OffsetDateTime.now();
        this.motivoAnulacion = motivo;
    }

    /** Si al anularla o devolverla hay que devolver dinero por la caja: una venta de un local, cobrada en su caja. */
    boolean pasoPorCaja() {
        return sesionCaja != null;
    }

    public Ambito getAmbito() {
        return ambito;
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public Cliente getCliente() {
        return cliente;
    }

    public SesionCaja getSesionCaja() {
        return sesionCaja;
    }

    public OffsetDateTime getFechaHora() {
        return fechaHora;
    }

    public MedioPago getMedioPago() {
        return medioPago;
    }

    public LocalDate getFechaCobro() {
        return fechaCobro;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getPorcentajeDescuento() {
        return porcentajeDescuento;
    }

    public BigDecimal getDescuento() {
        return descuento;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public EstadoVenta getEstado() {
        return estado;
    }

    public Comprobante getComprobante() {
        return comprobante;
    }

    public Usuario getAnuladaPor() {
        return anuladaPor;
    }

    public OffsetDateTime getAnuladaAt() {
        return anuladaAt;
    }

    public String getMotivoAnulacion() {
        return motivoAnulacion;
    }
}
