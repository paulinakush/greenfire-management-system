-- Etapa 2, módulo 5: el local central, que el catálogo público usa cuando el visitante no eligió un local.

alter table ambitos add column es_central boolean not null default false;

-- El central tiene que poder mostrarse: un local activo y visible en la web. Para ocultarlo o desactivarlo,
-- primero hay que marcar otro local como central.
alter table ambitos add constraint ambitos_central_valido
    check (not es_central or (tipo = 'local' and activo and visible_web));

-- A lo sumo un local central.
create unique index uq_ambitos_central on ambitos ((true)) where es_central;
