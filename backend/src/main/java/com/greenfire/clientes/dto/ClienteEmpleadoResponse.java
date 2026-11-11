package com.greenfire.clientes.dto;

import com.greenfire.clientes.Cliente;
import com.greenfire.clientes.TipoDocumento;
import com.greenfire.fiscal.CondicionIva;

/** Cliente para el empleado: datos de contacto y si puede comprar a cuenta corriente, sin límite, lista ni plazo. */
public record ClienteEmpleadoResponse(Long id, String nombre, String razonSocial, TipoDocumento documentoTipo,
        String documentoNumero, CondicionIva condicionIva, String telefono, String email, String direccion,
        boolean cuentaCorrienteHabilitada) implements ClienteVista {

    public static ClienteEmpleadoResponse de(Cliente c) {
        return new ClienteEmpleadoResponse(c.getId(), c.getNombre(), c.getRazonSocial(), c.getDocumentoTipo(),
                c.getDocumentoNumero(), c.getCondicionIva(), c.getTelefono(), c.getEmail(), c.getDireccion(),
                c.isCuentaCorrienteHabilitada());
    }
}
