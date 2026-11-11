package com.greenfire.clientes.dto;

import java.math.BigDecimal;

import com.greenfire.clientes.Cliente;
import com.greenfire.clientes.TipoDocumento;
import com.greenfire.fiscal.CondicionIva;

/** Cliente para los dueños, con sus condiciones comerciales. {@code localId} solo en los que representan un local. */
public record ClienteResponse(Long id, String nombre, String razonSocial, TipoDocumento documentoTipo,
        String documentoNumero, CondicionIva condicionIva, String telefono, String email, String direccion,
        boolean cuentaCorrienteHabilitada, BigDecimal limiteCredito, Long listaPreciosId, String listaPreciosNombre,
        Integer plazoPagoDias, Long localId, boolean activo) implements ClienteVista {

    public static ClienteResponse de(Cliente c) {
        return new ClienteResponse(c.getId(), c.getNombre(), c.getRazonSocial(), c.getDocumentoTipo(),
                c.getDocumentoNumero(), c.getCondicionIva(), c.getTelefono(), c.getEmail(), c.getDireccion(),
                c.isCuentaCorrienteHabilitada(), c.getLimiteCredito(),
                c.getListaPrecios() == null ? null : c.getListaPrecios().getId(),
                c.getListaPrecios() == null ? null : c.getListaPrecios().getNombre(),
                c.getPlazoPagoDias(), c.representaUnLocal() ? c.getAmbitoRepresentado().getId() : null, c.isActivo());
    }
}
