package com.greenfire.clientes.dto;

import java.util.List;

public record PaginaClientes(List<ClienteVista> clientes, int pagina, int tamano, long total) {
}
