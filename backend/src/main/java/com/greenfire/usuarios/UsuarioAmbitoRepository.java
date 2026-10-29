package com.greenfire.usuarios;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UsuarioAmbitoRepository extends JpaRepository<UsuarioAmbito, Long> {

    @Query("""
            select ua from UsuarioAmbito ua join fetch ua.ambito join fetch ua.usuario
            where ua.usuario.id = ?1 order by ua.ambito.tipo, ua.ambito.nombre
            """)
    List<UsuarioAmbito> deUsuario(Long usuarioId);

    @Query("""
            select ua from UsuarioAmbito ua join fetch ua.ambito join fetch ua.usuario
            where ua.usuario.id = ?1 and ua.ambito.id = ?2
            """)
    Optional<UsuarioAmbito> deUsuarioEnAmbito(Long usuarioId, Long ambitoId);

    @Query("""
            select ua from UsuarioAmbito ua join fetch ua.usuario
            where ua.ambito.id = ?1 order by ua.usuario.nombre
            """)
    List<UsuarioAmbito> deAmbito(Long ambitoId);

    boolean existsByUsuarioIdAndAmbitoId(Long usuarioId, Long ambitoId);
}
