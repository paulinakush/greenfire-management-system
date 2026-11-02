-- Ámbito que toca cada acción, además del ámbito activo de quien actúa: así cada dueño ve también lo que
-- otros (por ejemplo, el administrador técnico) hicieron sobre su ámbito.
-- Agregar una columna no modifica filas, así que los triggers de inmutabilidad de V2 siguen igual. Los
-- registros anteriores quedan sin ámbito afectado: completarlos sería modificar la auditoría.
alter table auditorias add column ambito_afectado_id bigint references ambitos (id);

create index ix_auditorias_afectado_fecha on auditorias (ambito_afectado_id, fecha_hora desc);
