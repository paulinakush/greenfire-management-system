-- Nombre de usuario para el login: se puede ingresar con el usuario o con el email.

alter table usuarios add column usuario varchar(30);

-- Los usuarios existentes reciben la parte del email antes de la @, adaptada a las reglas: en minúsculas, sin
-- los caracteres no admitidos, de 3 a 30 caracteres y con un número al final si ya está tomado.
do $$
declare
    u record;
    base varchar;
    candidato varchar;
    n integer;
begin
    for u in select id, email from usuarios order by id loop
        base := regexp_replace(lower(split_part(u.email, '@', 1)), '[^a-z0-9._]', '', 'g');
        base := rpad(left(base, 30), greatest(least(length(base), 30), 3), '0');
        candidato := base;
        n := 1;
        while exists (select 1 from usuarios where usuario = candidato) loop
            n := n + 1;
            candidato := left(base, 30 - length(n::text)) || n;
        end loop;
        update usuarios set usuario = candidato where id = u.id;
    end loop;
end;
$$;

alter table usuarios alter column usuario set not null;

-- Solo letras minúsculas, números, punto y guion bajo: así no puede confundirse con un email.
alter table usuarios add constraint usuarios_usuario_valido check (usuario ~ '^[a-z0-9._]{3,30}$');

create unique index uq_usuarios_usuario on usuarios (lower(usuario));
