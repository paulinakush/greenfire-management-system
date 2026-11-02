-- La auditoría no se modifica ni se borra: la base rechaza UPDATE, DELETE y TRUNCATE
-- sobre auditorias, venga de la aplicación o de cualquier otro cliente.

create function auditorias_inmutable() returns trigger
language plpgsql as $$
begin
    raise exception 'La auditoría no se puede modificar ni borrar (operación % rechazada).', tg_op
        using errcode = 'insufficient_privilege';
end;
$$;

create trigger auditorias_sin_update_ni_delete
    before update or delete on auditorias
    for each row execute function auditorias_inmutable();

create trigger auditorias_sin_truncate
    before truncate on auditorias
    for each statement execute function auditorias_inmutable();

create index ix_auditorias_fecha on auditorias (fecha_hora desc);
create index ix_auditorias_accion on auditorias (accion);
