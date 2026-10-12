/* ---------------------------------------------------------
   CAPA DE DATOS MOCK — Green Fire (interfaces privadas)

   No hay backend todavía: esto simula las 24 tablas del modelo
   de datos (ver diagramas /Untitled-3.pdf) con un seed inicial +
   persistencia en localStorage, y expone helpers de negocio que
   encapsulan las reglas transversales (stock automático, pago
   que solo se habilita al confirmar, aislamiento por sucursal,
   auditoría de acciones). El día que haya API real, esta es la
   única capa que se reemplaza — las pantallas no cambian.

   Única extensión sobre el diagrama original: `pedidos.observaciones_internas`
   (texto que carga la sucursal, separado de `observaciones` que
   carga el cliente al armar el pedido).
--------------------------------------------------------- */

window.GF_ENUMS = {
  estado_pedido: [
    { value: "pendiente", label: "Pendiente" },
    { value: "confirmado", label: "Confirmado" },
    { value: "rechazado", label: "Rechazado" },
    { value: "en_preparacion", label: "En preparación" },
    { value: "listo_retiro", label: "Listo para retirar" },
    { value: "pendiente_envio", label: "Pendiente de coordinación de envío" },
    { value: "finalizado", label: "Finalizado" },
  ],
  modalidad_entrega: [
    { value: "retiro_sucursal", label: "Retiro en sucursal" },
    { value: "envio_coordinado", label: "Envío coordinado" },
  ],
  medio_pago: [
    { value: "efectivo", label: "Efectivo" },
    { value: "transferencia", label: "Transferencia" },
    { value: "billetera_virtual", label: "Billetera virtual" },
  ],
  tipo_insumo: [
    { value: "materia_prima", label: "Materia prima" },
    { value: "insumo", label: "Insumo" },
  ],
  tipo_compra: [
    { value: "insumo", label: "Insumo" },
    { value: "producto", label: "Producto" },
    { value: "mixta", label: "Mixta" },
  ],
  tipo_comprobante_venta: [
    { value: "factura", label: "Factura" },
    { value: "presupuesto", label: "Presupuesto" },
    { value: "recibo", label: "Recibo" },
    { value: "remito", label: "Remito" },
  ],
  estado_comprobante: [
    { value: "emitido", label: "Emitido" },
    { value: "anulado", label: "Anulado" },
  ],
  estado_transferencia: [
    { value: "enviado", label: "Enviado" },
    { value: "recibido", label: "Recibido" },
  ],
};

(function () {
  const KEY = "gf_db_v1";
  const VERSION = 1;

  const nowISO = () => new Date().toISOString();

  const seed = () => {
    const data = {
      roles: [
        { id: 1, nombre: "empleado_sucursal", descripcion: "Empleado de sucursal" },
        { id: 2, nombre: "responsable_sucursal", descripcion: "Responsable de sucursal" },
        { id: 3, nombre: "administrativo_fabrica", descripcion: "Administrativo de fábrica" },
      ],
      permisos: [],
      rol_permisos: [],
      sucursales: [
        { id: 1, nombre: "Green Fire Centro", direccion: "Av. Corrientes 1234, CABA", telefono: "011 4444-1234", email: "centro@greenfire.com.ar", horarios: "Lun a Sáb de 9 a 19 hs", cuit: "30-71234567-8" },
        { id: 2, nombre: "Green Fire Norte", direccion: "Av. Maipú 2450, Vicente López", telefono: "011 4444-5678", email: "norte@greenfire.com.ar", horarios: "Lun a Vie de 9 a 18:30 hs · Sáb de 9 a 13 hs", cuit: "30-71234568-6" },
        { id: 3, nombre: "Green Fire Oeste", direccion: "Av. Rivadavia 8900, Morón", telefono: "011 4444-9012", email: "oeste@greenfire.com.ar", horarios: "Lun a Sáb de 9 a 19:30 hs", cuit: "30-71234569-4" },
      ],
      usuarios: [
        { id: 1, nombre: "Lucía Fernández", email: "empleado@greenfire.com", password: "empleado123", rol_id: 1, sucursal_id: 1, activo: true },
        { id: 2, nombre: "Martín Ibáñez", email: "responsable@greenfire.com", password: "responsable123", rol_id: 2, sucursal_id: 2, activo: true },
        { id: 3, nombre: "Carla Gómez", email: "fabrica@greenfire.com", password: "fabrica123", rol_id: 3, sucursal_id: null, activo: true },
      ],
      categorias: [
        { id: 1, nombre: "Sustratos", descripcion: "Sustratos de fabricación propia" },
        { id: 2, nombre: "Carpas de cultivo", descripcion: "" },
        { id: 3, nombre: "Fertilizantes", descripcion: "" },
        { id: 4, nombre: "Iluminación LED", descripcion: "" },
        { id: 5, nombre: "Herramientas", descripcion: "" },
      ],
      productos: [
        { id: 1, nombre: "Coco Fire", descripcion: "Sustrato a base de fibra de coco", precio: 8500, imagen: "coco-fire.png", categoria_id: 1, marca_id: 1, grupo_id: 1, es_fabricacion_propia: true, visible: true },
        { id: 2, nombre: "Premium Mix", descripcion: "Mezcla premium para floración", precio: 9800, imagen: "premium-mix.png", categoria_id: 1, marca_id: 1, grupo_id: 1, es_fabricacion_propia: true, visible: true },
        { id: 3, nombre: "Huertero Plus", descripcion: "Sustrato para huerta orgánica", precio: 7200, imagen: "huertero-plus.png", categoria_id: 1, marca_id: 1, grupo_id: 1, es_fabricacion_propia: true, visible: true },
        { id: 4, nombre: "Industrial", descripcion: "Sustrato para escala industrial", precio: 15400, imagen: "industrial-main-nobg.png", categoria_id: 1, marca_id: 1, grupo_id: 1, es_fabricacion_propia: true, visible: true },
        { id: 5, nombre: "ChampiCompost", descripcion: "Enmienda orgánica a base de compost de champiñón", precio: 6300, imagen: "champicompost-nobg.png", categoria_id: 1, marca_id: 1, grupo_id: 1, es_fabricacion_propia: true, visible: true },
        { id: 6, nombre: "Grow Light Silver 2.1", descripcion: "Panel LED full espectro", precio: 185000, imagen: "grow-light.png", categoria_id: 4, marca_id: 2, grupo_id: 2, es_fabricacion_propia: false, visible: true },
      ],
      marcas: [
        { id: 1, nombre: "Green Fire" },
        { id: 2, nombre: "GreenFire Tech" },
      ],
      grupos: [
        { id: 1, nombre: "Sustratos y enmiendas" },
        { id: 2, nombre: "Equipamiento" },
      ],
      producto_variantes: [],
      listas_precios: [{ id: 1, nombre: "Lista general", es_predeterminada: true }],
      lista_precios_items: [],
      ubicaciones_stock: [
        { id: 1, nombre: "Depósito Centro", tipo: "sucursal", sucursal_id: 1 },
        { id: 2, nombre: "Depósito Norte", tipo: "sucursal", sucursal_id: 2 },
        { id: 3, nombre: "Depósito Oeste", tipo: "sucursal", sucursal_id: 3 },
        { id: 4, nombre: "Stock Central Fábrica", tipo: "fabrica", sucursal_id: null },
      ],
      stock_productos: [
        { id: 1, producto_id: 1, ubicacion_stock_id: 1, cantidad_actual: 40, stock_minimo: 10, stock_maximo: 120 },
        { id: 2, producto_id: 2, ubicacion_stock_id: 1, cantidad_actual: 18, stock_minimo: 10, stock_maximo: 100 },
        { id: 3, producto_id: 3, ubicacion_stock_id: 1, cantidad_actual: 6, stock_minimo: 8, stock_maximo: 80 },
        { id: 4, producto_id: 6, ubicacion_stock_id: 1, cantidad_actual: 2, stock_minimo: 5, stock_maximo: 30 },
        { id: 5, producto_id: 1, ubicacion_stock_id: 2, cantidad_actual: 25, stock_minimo: 10, stock_maximo: 120 },
        { id: 6, producto_id: 4, ubicacion_stock_id: 2, cantidad_actual: 12, stock_minimo: 5, stock_maximo: 60 },
        { id: 7, producto_id: 5, ubicacion_stock_id: 2, cantidad_actual: 3, stock_minimo: 6, stock_maximo: 60 },
        { id: 8, producto_id: 2, ubicacion_stock_id: 3, cantidad_actual: 30, stock_minimo: 10, stock_maximo: 100 },
        { id: 9, producto_id: 6, ubicacion_stock_id: 3, cantidad_actual: 9, stock_minimo: 5, stock_maximo: 30 },
        { id: 10, producto_id: 1, ubicacion_stock_id: 4, cantidad_actual: 220, stock_minimo: 50, stock_maximo: 800 },
        { id: 11, producto_id: 2, ubicacion_stock_id: 4, cantidad_actual: 140, stock_minimo: 50, stock_maximo: 800 },
        { id: 12, producto_id: 5, ubicacion_stock_id: 4, cantidad_actual: 90, stock_minimo: 50, stock_maximo: 800 },
      ],
      movimientos_stock: [
        { id: 1, stock_producto_id: 1, usuario_id: 1, tipo_movimiento: "ingreso_mercaderia", cantidad: 40, fecha_hora: "2026-08-20T10:00:00.000Z", motivo: "Reposición inicial", observaciones: "" },
        { id: 2, stock_producto_id: 6, usuario_id: 2, tipo_movimiento: "ingreso_mercaderia", cantidad: 12, fecha_hora: "2026-08-21T14:30:00.000Z", motivo: "Reposición inicial", observaciones: "" },
      ],
      clientes: [
        { id: 1, nombre: "Juan Pérez", telefono: "11 2233-4455", email: "juan.perez@mail.com" },
        { id: 2, nombre: "Sofía Ruiz", telefono: "11 5566-7788", email: "sofia.ruiz@mail.com" },
        { id: 3, nombre: "Consumidor Final", telefono: "", email: "" },
      ],
      pedidos: [
        { id: 1, cliente_id: 1, sucursal_id: 1, fecha: "2026-08-29T15:20:00.000Z", modalidad: "retiro_sucursal", estado: "pendiente", pago_habilitado: false, total: 17000, observaciones: "Prefiero retirar por la tarde.", observaciones_internas: "" },
        { id: 2, cliente_id: 2, sucursal_id: 1, fecha: "2026-08-27T11:05:00.000Z", modalidad: "envio_coordinado", estado: "confirmado", pago_habilitado: true, total: 9800, observaciones: "Enviar a depósito del edificio.", observaciones_internas: "Coordinado con el cadete para el jueves." },
        { id: 3, cliente_id: 1, sucursal_id: 2, fecha: "2026-08-30T09:40:00.000Z", modalidad: "retiro_sucursal", estado: "pendiente", pago_habilitado: false, total: 15400, observaciones: "", observaciones_internas: "" },
        { id: 4, cliente_id: 2, sucursal_id: 1, fecha: "2026-08-22T18:10:00.000Z", modalidad: "retiro_sucursal", estado: "rechazado", pago_habilitado: false, total: 6300, observaciones: "", observaciones_internas: "Sin stock suficiente al momento de revisar." },
      ],
      detalle_pedidos: [
        { id: 1, pedido_id: 1, producto_id: 1, cantidad: 2, precio_unitario: 8500, subtotal: 17000 },
        { id: 2, pedido_id: 2, producto_id: 2, cantidad: 1, precio_unitario: 9800, subtotal: 9800 },
        { id: 3, pedido_id: 3, producto_id: 4, cantidad: 1, precio_unitario: 15400, subtotal: 15400 },
        { id: 4, pedido_id: 4, producto_id: 5, cantidad: 1, precio_unitario: 6300, subtotal: 6300 },
      ],
      ventas: [
        { id: 1, sucursal_id: 1, usuario_id: 1, fecha_hora: "2026-08-31T13:15:00.000Z", medio_pago: "efectivo", total: 8500 },
        { id: 2, sucursal_id: 1, usuario_id: 2, fecha_hora: "2026-08-31T16:40:00.000Z", medio_pago: "transferencia", total: 9800 },
      ],
      detalle_ventas: [
        { id: 1, venta_id: 1, producto_id: 1, cantidad: 1, precio_unitario: 8500, subtotal: 8500 },
        { id: 2, venta_id: 2, producto_id: 2, cantidad: 1, precio_unitario: 9800, subtotal: 9800 },
      ],
      cierres_caja: [
        { id: 1, sucursal_id: 2, usuario_id: 2, fecha: "2026-08-30", total_efectivo: 21000, total_transferencia: 9800, total_billetera: 0, total_general: 30800, observaciones: "" },
      ],
      proveedores: [
        { id: 1, razon_social: "AgroInsumos SRL", contacto: "Roberto Díaz · 11 4000-1111", tipo_provee: "Fibra de coco y perlita", observaciones: "Entrega semanal los martes.", activo: true },
        { id: 2, razon_social: "Compost Sur", contacto: "Marina Sosa · 11 4000-2222", tipo_provee: "Compost de champiñón", observaciones: "", activo: true },
        { id: 3, razon_social: "Packaging Andina", contacto: "Diego Luna · 11 4000-3333", tipo_provee: "Bolsas y etiquetas", observaciones: "", activo: true },
      ],
      insumos: [
        { id: 1, nombre: "Fibra de coco", tipo: "materia_prima", unidad_medida: "kg", stock: 850 },
        { id: 2, nombre: "Perlita", tipo: "materia_prima", unidad_medida: "kg", stock: 320 },
        { id: 3, nombre: "Compost de champiñón", tipo: "materia_prima", unidad_medida: "kg", stock: 610 },
        { id: 4, nombre: "Bolsas de packaging 5kg", tipo: "insumo", unidad_medida: "unidades", stock: 1200 },
      ],
      compras: [
        { id: 1, proveedor_id: 1, usuario_id: 3, ubicacion_stock_id: 4, fecha: "2026-08-18", tipo: "insumo", costo_total: 340000, observaciones: "Compra mensual de fibra de coco." },
        { id: 2, proveedor_id: 2, usuario_id: 3, ubicacion_stock_id: 4, fecha: "2026-08-24", tipo: "insumo", costo_total: 180000, observaciones: "" },
      ],
      detalle_compra_insumos: [
        { id: 1, compra_id: 1, insumo_id: 1, cantidad: 500, costo_unitario: 680, subtotal: 340000 },
        { id: 2, compra_id: 2, insumo_id: 3, cantidad: 300, costo_unitario: 600, subtotal: 180000 },
      ],
      detalle_compra_productos: [],
      producciones: [
        { id: 1, producto_id: 1, usuario_id: 3, ubicacion_stock_id: 4, fecha: "2026-08-25", cantidad_producida: 80, observaciones: "Tanda regular de Coco Fire." },
      ],
      produccion_insumos: [
        { id: 1, produccion_id: 1, insumo_id: 1, cantidad_consumida: 120 },
        { id: 2, produccion_id: 1, insumo_id: 2, cantidad_consumida: 20 },
      ],
      transferencias_stock: [
        { id: 1, origen_ubicacion_id: 4, destino_ubicacion_id: 2, usuario_id: 3, fecha: "2026-08-28", estado: "enviado", observaciones: "Reposición mensual sucursal Norte." },
        { id: 2, origen_ubicacion_id: 4, destino_ubicacion_id: 1, usuario_id: 3, fecha: "2026-08-15", estado: "recibido", observaciones: "" },
      ],
      detalle_transferencia_stock: [
        { id: 1, transferencia_id: 1, producto_id: 1, cantidad: 20 },
        { id: 2, transferencia_id: 2, producto_id: 2, cantidad: 15 },
      ],
      comprobantes_venta: [
        {
          id: 1,
          tipo: "factura",
          punto_venta: "0002",
          numero: 1,
          sucursal_id: 2,
          cliente_id: 2,
          usuario_id: 2,
          fecha: "2026-08-30T12:00:00.000Z",
          estado: "emitido",
          arca_estado: "pendiente_integracion",
          origen: "interno",
          total: 9800,
          observaciones: "",
        },
      ],
      detalle_comprobante_venta: [{ id: 1, comprobante_id: 1, producto_id: 2, cantidad: 1, precio_unitario: 9800, subtotal: 9800 }],
      cobranzas: [],
      detalle_cobranza: [],
      auditorias: [],
    };
    return data;
  };

  let store = null;

  const load = () => {
    if (store) return store;
    try {
      const raw = localStorage.getItem(KEY);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (parsed._version === VERSION) {
          store = parsed.data;
          return store;
        }
      }
    } catch (e) {
      /* localStorage corrupto o inaccesible: se reseedea abajo */
    }
    store = seed();
    persist();
    return store;
  };

  const persist = () => {
    try {
      localStorage.setItem(KEY, JSON.stringify({ _version: VERSION, data: store }));
    } catch (e) {
      /* modo privado del navegador u otra restricción: se sigue trabajando en memoria */
    }
  };

  const nextId = (table) => {
    const rows = load()[table];
    return rows.reduce((max, r) => Math.max(max, r.id), 0) + 1;
  };

  const get = (table) => load()[table];

  const find = (table, id) => load()[table].find((r) => r.id === id) || null;

  const query = (table, predicate) => load()[table].filter(predicate);

  const insert = (table, row) => {
    const full = { id: nextId(table), ...row };
    load()[table].push(full);
    persist();
    return full;
  };

  const update = (table, id, patch) => {
    const row = find(table, id);
    if (!row) return null;
    Object.assign(row, patch);
    persist();
    return row;
  };

  const auditar = (usuarioId, accion, entidad) => {
    insert("auditorias", { usuario_id: usuarioId, accion, entidad, fecha_hora: nowISO() });
  };

  const stockDeProducto = (productoId, ubicacionId) =>
    query("stock_productos", (s) => s.producto_id === productoId && s.ubicacion_stock_id === ubicacionId)[0] || null;

  const ubicacionDeSucursal = (sucursalId) =>
    query("ubicaciones_stock", (u) => u.tipo === "sucursal" && u.sucursal_id === sucursalId)[0] || null;

  const ubicacionFabrica = () => query("ubicaciones_stock", (u) => u.tipo === "fabrica")[0];

  // ---- Reglas de negocio ----

  const confirmarPedido = (pedidoId, usuarioId) => {
    const pedido = find("pedidos", pedidoId);
    if (!pedido || pedido.estado !== "pendiente") return null;
    const ubicacion = ubicacionDeSucursal(pedido.sucursal_id);
    const lineas = query("detalle_pedidos", (d) => d.pedido_id === pedidoId);
    lineas.forEach((linea) => {
      const sp = stockDeProducto(linea.producto_id, ubicacion.id);
      if (sp) {
        update("stock_productos", sp.id, { cantidad_actual: sp.cantidad_actual - linea.cantidad });
        insert("movimientos_stock", {
          stock_producto_id: sp.id,
          usuario_id: usuarioId,
          tipo_movimiento: "pedido_confirmado",
          cantidad: -linea.cantidad,
          fecha_hora: nowISO(),
          motivo: `Pedido #${pedidoId} confirmado`,
          observaciones: "",
        });
      }
    });
    update("pedidos", pedidoId, { estado: "confirmado", pago_habilitado: true });
    auditar(usuarioId, "confirmar_pedido", `pedidos:${pedidoId}`);
    return find("pedidos", pedidoId);
  };

  const rechazarPedido = (pedidoId, usuarioId) => {
    const pedido = find("pedidos", pedidoId);
    if (!pedido || pedido.estado !== "pendiente") return null;
    update("pedidos", pedidoId, { estado: "rechazado", pago_habilitado: false });
    auditar(usuarioId, "rechazar_pedido", `pedidos:${pedidoId}`);
    return find("pedidos", pedidoId);
  };

  // "Nuevo Pedido" desde la barra horizontal de sucursal: hoy los pedidos
  // solo llegaban desde el catálogo público; esto permite que un empleado
  // cargue uno a mano (ej. por teléfono) y entra al mismo circuito de
  // confirmar/rechazar que cualquier otro pedido.
  const crearPedidoManual = ({ sucursalId, clienteId, usuarioId, modalidad, items, observaciones }) => {
    const total = items.reduce((acc, it) => acc + it.cantidad * it.precioUnitario, 0);
    const pedido = insert("pedidos", {
      cliente_id: clienteId,
      sucursal_id: sucursalId,
      fecha: nowISO(),
      modalidad,
      estado: "pendiente",
      pago_habilitado: false,
      total,
      observaciones: observaciones || "",
      observaciones_internas: "",
    });
    items.forEach((it) => {
      insert("detalle_pedidos", {
        pedido_id: pedido.id,
        producto_id: it.productoId,
        cantidad: it.cantidad,
        precio_unitario: it.precioUnitario,
        subtotal: it.cantidad * it.precioUnitario,
      });
    });
    auditar(usuarioId, "crear_pedido_manual", `pedidos:${pedido.id}`);
    return pedido;
  };

  const guardarObservacionInterna = (pedidoId, texto, usuarioId) => {
    update("pedidos", pedidoId, { observaciones_internas: texto });
    auditar(usuarioId, "editar_observacion_interna", `pedidos:${pedidoId}`);
    return find("pedidos", pedidoId);
  };

  // Transiciones válidas más allá de confirmar/rechazar. `pendiente` y
  // `rechazado` no aparecen acá: a esos solo se llega vía confirmarPedido/
  // rechazarPedido, nunca como "próximo paso" de otro estado.
  const TRANSICIONES_PEDIDO = {
    confirmado: ["en_preparacion"],
    en_preparacion: ["listo_retiro", "pendiente_envio"],
    listo_retiro: ["finalizado"],
    pendiente_envio: ["finalizado"],
  };

  const proximosEstadosPedido = (estadoActual) => TRANSICIONES_PEDIDO[estadoActual] || [];

  const avanzarEstadoPedido = (pedidoId, nuevoEstado, usuarioId) => {
    const pedido = find("pedidos", pedidoId);
    if (!pedido) return null;
    const validos = TRANSICIONES_PEDIDO[pedido.estado] || [];
    if (!validos.includes(nuevoEstado)) return null;
    update("pedidos", pedidoId, { estado: nuevoEstado });
    auditar(usuarioId, `pedido_${nuevoEstado}`, `pedidos:${pedidoId}`);
    return find("pedidos", pedidoId);
  };

  const registrarIngresoMercaderia = ({ productoId, ubicacionId, cantidad, usuarioId, motivo }) => {
    let sp = stockDeProducto(productoId, ubicacionId);
    if (!sp) {
      sp = insert("stock_productos", { producto_id: productoId, ubicacion_stock_id: ubicacionId, cantidad_actual: 0, stock_minimo: 0, stock_maximo: 0 });
    }
    update("stock_productos", sp.id, { cantidad_actual: sp.cantidad_actual + cantidad });
    insert("movimientos_stock", {
      stock_producto_id: sp.id,
      usuario_id: usuarioId,
      tipo_movimiento: "ingreso_mercaderia",
      cantidad,
      fecha_hora: nowISO(),
      motivo: motivo || "Ingreso de mercadería",
      observaciones: "",
    });
    auditar(usuarioId, "ingreso_mercaderia", `stock_productos:${sp.id}`);
    return find("stock_productos", sp.id);
  };

  const registrarVentaPresencial = ({ sucursalId, usuarioId, items, medioPago }) => {
    const ubicacion = ubicacionDeSucursal(sucursalId);
    const total = items.reduce((acc, it) => acc + it.cantidad * it.precioUnitario, 0);
    const venta = insert("ventas", { sucursal_id: sucursalId, usuario_id: usuarioId, fecha_hora: nowISO(), medio_pago: medioPago, total });
    items.forEach((it) => {
      insert("detalle_ventas", {
        venta_id: venta.id,
        producto_id: it.productoId,
        cantidad: it.cantidad,
        precio_unitario: it.precioUnitario,
        subtotal: it.cantidad * it.precioUnitario,
      });
      const sp = stockDeProducto(it.productoId, ubicacion.id);
      if (sp) {
        update("stock_productos", sp.id, { cantidad_actual: sp.cantidad_actual - it.cantidad });
        insert("movimientos_stock", {
          stock_producto_id: sp.id,
          usuario_id: usuarioId,
          tipo_movimiento: "venta_presencial",
          cantidad: -it.cantidad,
          fecha_hora: nowISO(),
          motivo: `Venta presencial #${venta.id}`,
          observaciones: "",
        });
      }
    });
    auditar(usuarioId, "venta_presencial", `ventas:${venta.id}`);
    return venta;
  };

  const ajustarStock = ({ stockProductoId, cantidad, motivo, usuarioId }) => {
    const sp = find("stock_productos", stockProductoId);
    if (!sp) return null;
    update("stock_productos", sp.id, { cantidad_actual: sp.cantidad_actual + cantidad });
    insert("movimientos_stock", {
      stock_producto_id: sp.id,
      usuario_id: usuarioId,
      tipo_movimiento: "ajuste",
      cantidad,
      fecha_hora: nowISO(),
      motivo,
      observaciones: "",
    });
    auditar(usuarioId, "ajustar_stock", `stock_productos:${sp.id}`);
    return find("stock_productos", sp.id);
  };

  const cerrarCaja = ({ sucursalId, usuarioId, fecha, totalEfectivo, totalTransferencia, totalBilletera, observaciones }) => {
    const totalGeneral = totalEfectivo + totalTransferencia + totalBilletera;
    const cierre = insert("cierres_caja", {
      sucursal_id: sucursalId,
      usuario_id: usuarioId,
      fecha,
      total_efectivo: totalEfectivo,
      total_transferencia: totalTransferencia,
      total_billetera: totalBilletera,
      total_general: totalGeneral,
      observaciones: observaciones || "",
    });
    auditar(usuarioId, "cierre_caja", `cierres_caja:${cierre.id}`);
    return cierre;
  };

  const registrarCompra = ({ proveedorId, usuarioId, tipo, itemsInsumos = [], itemsProductos = [], observaciones }) => {
    const ubicacion = ubicacionFabrica();
    const costoTotal =
      itemsInsumos.reduce((acc, it) => acc + it.cantidad * it.costoUnitario, 0) +
      itemsProductos.reduce((acc, it) => acc + it.cantidad * it.costoUnitario, 0);
    const compra = insert("compras", {
      proveedor_id: proveedorId,
      usuario_id: usuarioId,
      ubicacion_stock_id: ubicacion.id,
      fecha: fecha_hoy(),
      tipo,
      costo_total: costoTotal,
      observaciones: observaciones || "",
    });
    itemsInsumos.forEach((it) => {
      insert("detalle_compra_insumos", {
        compra_id: compra.id,
        insumo_id: it.insumoId,
        cantidad: it.cantidad,
        costo_unitario: it.costoUnitario,
        subtotal: it.cantidad * it.costoUnitario,
      });
      const insumo = find("insumos", it.insumoId);
      update("insumos", it.insumoId, { stock: insumo.stock + it.cantidad });
    });
    itemsProductos.forEach((it) => {
      insert("detalle_compra_productos", {
        compra_id: compra.id,
        producto_id: it.productoId,
        cantidad: it.cantidad,
        costo_unitario: it.costoUnitario,
        subtotal: it.cantidad * it.costoUnitario,
      });
      let sp = stockDeProducto(it.productoId, ubicacion.id);
      if (!sp) sp = insert("stock_productos", { producto_id: it.productoId, ubicacion_stock_id: ubicacion.id, cantidad_actual: 0, stock_minimo: 0, stock_maximo: 0 });
      update("stock_productos", sp.id, { cantidad_actual: sp.cantidad_actual + it.cantidad });
      insert("movimientos_stock", {
        stock_producto_id: sp.id,
        usuario_id: usuarioId,
        tipo_movimiento: "compra_producto",
        cantidad: it.cantidad,
        fecha_hora: nowISO(),
        motivo: `Compra #${compra.id}`,
        observaciones: "",
      });
    });
    auditar(usuarioId, "registrar_compra", `compras:${compra.id}`);
    return compra;
  };

  const registrarProduccion = ({ productoId, usuarioId, insumosConsumidos, cantidadProducida, observaciones }) => {
    const ubicacion = ubicacionFabrica();
    const produccion = insert("producciones", {
      producto_id: productoId,
      usuario_id: usuarioId,
      ubicacion_stock_id: ubicacion.id,
      fecha: fecha_hoy(),
      cantidad_producida: cantidadProducida,
      observaciones: observaciones || "",
    });
    insumosConsumidos.forEach((it) => {
      insert("produccion_insumos", { produccion_id: produccion.id, insumo_id: it.insumoId, cantidad_consumida: it.cantidad });
      const insumo = find("insumos", it.insumoId);
      update("insumos", it.insumoId, { stock: insumo.stock - it.cantidad });
    });
    // include: cargar automáticamente el producto terminado al stock central
    let sp = stockDeProducto(productoId, ubicacion.id);
    if (!sp) sp = insert("stock_productos", { producto_id: productoId, ubicacion_stock_id: ubicacion.id, cantidad_actual: 0, stock_minimo: 0, stock_maximo: 0 });
    update("stock_productos", sp.id, { cantidad_actual: sp.cantidad_actual + cantidadProducida });
    insert("movimientos_stock", {
      stock_producto_id: sp.id,
      usuario_id: usuarioId,
      tipo_movimiento: "produccion",
      cantidad: cantidadProducida,
      fecha_hora: nowISO(),
      motivo: `Producción #${produccion.id}`,
      observaciones: "",
    });
    auditar(usuarioId, "registrar_produccion", `producciones:${produccion.id}`);
    return produccion;
  };

  // Traza a nivel insumo: el modelo no linkea una compra puntual con una
  // producción puntual, así que la cadena llega hasta "de qué insumo salió",
  // no hasta "de qué compra específica salió ese kilo".
  const trazabilidad = (productoId) => {
    const producciones = query("producciones", (p) => p.producto_id === productoId);
    return producciones.map((p) => {
      const insumosUsados = query("produccion_insumos", (pi) => pi.produccion_id === p.id).map((pi) => {
        const insumo = find("insumos", pi.insumo_id);
        const comprasDelInsumo = query("detalle_compra_insumos", (dc) => dc.insumo_id === pi.insumo_id).map((dc) => {
          const compra = find("compras", dc.compra_id);
          const proveedor = find("proveedores", compra.proveedor_id);
          return { compra, proveedor, detalle: dc };
        });
        return { insumo, cantidadConsumida: pi.cantidad_consumida, comprasDelInsumo };
      });
      return { produccion: p, insumosUsados };
    });
  };

  // ---- Comprobantes de venta (Factura/Presupuesto/Recibo/Remito) ----
  // Una sola tabla discriminada por `tipo`, como hacen los ERPs reales, en
  // vez de 4 tablas casi idénticas. Solo `factura` lleva `arca_estado`: es
  // el único documento realmente fiscal de este conjunto; se marca siempre
  // `pendiente_integracion` porque la conexión a ARCA no existe todavía
  // (no se simula una autorización que no es real).

  const puntoVentaDeSucursal = (sucursalId) => String(sucursalId).padStart(4, "0");

  const siguienteNumeroComprobante = (sucursalId, tipo) => {
    const existentes = query("comprobantes_venta", (c) => c.sucursal_id === sucursalId && c.tipo === tipo);
    return existentes.reduce((max, c) => Math.max(max, c.numero), 0) + 1;
  };

  const emitirComprobante = ({ sucursalId, usuarioId, tipo, clienteId, items, observaciones }) => {
    const total = items.reduce((acc, it) => acc + it.cantidad * it.precioUnitario, 0);
    const comprobante = insert("comprobantes_venta", {
      tipo,
      punto_venta: puntoVentaDeSucursal(sucursalId),
      numero: siguienteNumeroComprobante(sucursalId, tipo),
      sucursal_id: sucursalId,
      cliente_id: clienteId,
      usuario_id: usuarioId,
      fecha: nowISO(),
      estado: "emitido",
      arca_estado: tipo === "factura" ? "pendiente_integracion" : null,
      origen: "interno",
      total,
      observaciones: observaciones || "",
    });
    items.forEach((it) => {
      insert("detalle_comprobante_venta", {
        comprobante_id: comprobante.id,
        producto_id: it.productoId,
        cantidad: it.cantidad,
        precio_unitario: it.precioUnitario,
        subtotal: it.cantidad * it.precioUnitario,
      });
    });
    // Factura y remito representan mercadería que efectivamente sale del
    // depósito; presupuesto y recibo no mueven stock.
    if (tipo === "factura" || tipo === "remito") {
      const ubicacion = ubicacionDeSucursal(sucursalId);
      items.forEach((it) => {
        const sp = stockDeProducto(it.productoId, ubicacion.id);
        if (sp) {
          update("stock_productos", sp.id, { cantidad_actual: sp.cantidad_actual - it.cantidad });
          insert("movimientos_stock", {
            stock_producto_id: sp.id,
            usuario_id: usuarioId,
            tipo_movimiento: "venta_presencial",
            cantidad: -it.cantidad,
            fecha_hora: nowISO(),
            motivo: `${tipo === "factura" ? "Factura" : "Remito"} ${comprobante.punto_venta}-${String(comprobante.numero).padStart(8, "0")}`,
            observaciones: "",
          });
        }
      });
    }
    auditar(usuarioId, `emitir_${tipo}`, `comprobantes_venta:${comprobante.id}`);
    return comprobante;
  };

  const anularComprobante = (comprobanteId, usuarioId) => {
    const comprobante = find("comprobantes_venta", comprobanteId);
    if (!comprobante || comprobante.estado === "anulado") return null;
    update("comprobantes_venta", comprobanteId, { estado: "anulado" });
    auditar(usuarioId, "anular_comprobante", `comprobantes_venta:${comprobanteId}`);
    return find("comprobantes_venta", comprobanteId);
  };

  // ---- Cobranzas: aplican contra uno o más comprobantes existentes ----

  const registrarCobranza = ({ sucursalId, clienteId, usuarioId, medioPago, monto, aplicaciones = [], observaciones }) => {
    const cobranza = insert("cobranzas", {
      sucursal_id: sucursalId,
      cliente_id: clienteId,
      usuario_id: usuarioId,
      fecha: nowISO(),
      medio_pago: medioPago,
      monto,
      observaciones: observaciones || "",
    });
    aplicaciones.forEach((a) => {
      insert("detalle_cobranza", { cobranza_id: cobranza.id, comprobante_id: a.comprobanteId, importe_aplicado: a.importe });
    });
    auditar(usuarioId, "registrar_cobranza", `cobranzas:${cobranza.id}`);
    return cobranza;
  };

  // ---- Transferencias de stock (fábrica → sucursal) ----
  // Al crear, el stock sale del origen de inmediato (la mercadería viaja);
  // recién se acredita al destino cuando esa sucursal confirma la recepción.

  const crearTransferencia = ({ origenId, destinoId, usuarioId, items, observaciones }) => {
    const transferencia = insert("transferencias_stock", {
      origen_ubicacion_id: origenId,
      destino_ubicacion_id: destinoId,
      usuario_id: usuarioId,
      fecha: fecha_hoy(),
      estado: "enviado",
      observaciones: observaciones || "",
    });
    items.forEach((it) => {
      insert("detalle_transferencia_stock", { transferencia_id: transferencia.id, producto_id: it.productoId, cantidad: it.cantidad });
      const sp = stockDeProducto(it.productoId, origenId);
      if (sp) {
        update("stock_productos", sp.id, { cantidad_actual: sp.cantidad_actual - it.cantidad });
        insert("movimientos_stock", {
          stock_producto_id: sp.id,
          usuario_id: usuarioId,
          tipo_movimiento: "transferencia_salida",
          cantidad: -it.cantidad,
          fecha_hora: nowISO(),
          motivo: `Transferencia #${transferencia.id}`,
          observaciones: "",
        });
      }
    });
    auditar(usuarioId, "crear_transferencia", `transferencias_stock:${transferencia.id}`);
    return transferencia;
  };

  const recibirTransferencia = (transferenciaId, usuarioId) => {
    const transferencia = find("transferencias_stock", transferenciaId);
    if (!transferencia || transferencia.estado === "recibido") return null;
    const lineas = query("detalle_transferencia_stock", (d) => d.transferencia_id === transferenciaId);
    lineas.forEach((linea) => {
      let sp = stockDeProducto(linea.producto_id, transferencia.destino_ubicacion_id);
      if (!sp) {
        sp = insert("stock_productos", { producto_id: linea.producto_id, ubicacion_stock_id: transferencia.destino_ubicacion_id, cantidad_actual: 0, stock_minimo: 0, stock_maximo: 0 });
      }
      update("stock_productos", sp.id, { cantidad_actual: sp.cantidad_actual + linea.cantidad });
      insert("movimientos_stock", {
        stock_producto_id: sp.id,
        usuario_id: usuarioId,
        tipo_movimiento: "transferencia_entrada",
        cantidad: linea.cantidad,
        fecha_hora: nowISO(),
        motivo: `Transferencia #${transferenciaId}`,
        observaciones: "",
      });
    });
    update("transferencias_stock", transferenciaId, { estado: "recibido" });
    auditar(usuarioId, "recibir_transferencia", `transferencias_stock:${transferenciaId}`);
    return find("transferencias_stock", transferenciaId);
  };

  const fecha_hoy = () => new Date().toISOString().slice(0, 10);

  window.GFDB = {
    get,
    find,
    query,
    insert,
    update,
    nowISO,
    fechaHoy: fecha_hoy,
    ubicacionDeSucursal,
    ubicacionFabrica,
    stockDeProducto,
    confirmarPedido,
    rechazarPedido,
    crearPedidoManual,
    avanzarEstadoPedido,
    proximosEstadosPedido,
    guardarObservacionInterna,
    registrarIngresoMercaderia,
    registrarVentaPresencial,
    ajustarStock,
    cerrarCaja,
    registrarCompra,
    registrarProduccion,
    trazabilidad,
    puntoVentaDeSucursal,
    emitirComprobante,
    anularComprobante,
    registrarCobranza,
    crearTransferencia,
    recibirTransferencia,
  };
})();
