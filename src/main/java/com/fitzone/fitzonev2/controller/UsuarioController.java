package com.fitzone.fitzonev2.controller;

import com.fitzone.fitzonev2.model.IMCRegistro;
import com.fitzone.fitzonev2.model.Usuario;
import com.fitzone.fitzonev2.repository.UsuarioRepository;
import com.fitzone.fitzonev2.service.UsuarioService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.ui.Model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/usuarios")
@CrossOrigin(origins = "*")
public class UsuarioController {

    private final UsuarioRepository usuarioRepository;
    private final UsuarioService usuarioService;

    @Autowired
    private MongoTemplate mongoTemplate;


    @Autowired
    public UsuarioController(UsuarioRepository usuarioRepository, UsuarioService usuarioService) {
        this.usuarioRepository = usuarioRepository;
        this.usuarioService = usuarioService;
    }

    // Obtener usuario por ID (incluye historial IMC)
    @GetMapping("/{usuarioId}")
    public ResponseEntity<?> getUsuarioById(@PathVariable String usuarioId) {
        Optional<Usuario> usuario = usuarioRepository.findById(usuarioId);
        if (usuario.isPresent()) {
            return ResponseEntity.ok(usuario.get());
        } else {
            return ResponseEntity.status(404).body("Usuario no encontrado");
        }
    }

    // Crear usuario desde Admin (acepta tanto 'contrasena' como 'password', valida duplicados y encripta con BCrypt)
    @PostMapping("/agregar")
    public ResponseEntity<?> agregarUsuario(@RequestBody java.util.Map<String, Object> body) {
        String nombre = body.get("nombre") != null ? body.get("nombre").toString().trim() : "";
        String apellido = body.get("apellido") != null ? body.get("apellido").toString().trim() : "";
        String correo = body.get("correo") != null ? body.get("correo").toString().trim().toLowerCase() : "";
        Object rawPass = body.get("contrasena") != null ? body.get("contrasena") : body.get("password");
        String contrasena = rawPass != null ? rawPass.toString() : "";
        String rol = body.get("rol") != null ? body.get("rol").toString().trim().toUpperCase() : "USUARIO";

        if (nombre.isEmpty() || correo.isEmpty() || !correo.contains("@")) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "Nombre y correo válido son obligatorios"));
        }
        if (contrasena.length() < 6) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "La contraseña debe tener al menos 6 caracteres"));
        }
        if (usuarioRepository.findFirstByCorreoIgnoreCase(correo).isPresent()) {
            return ResponseEntity.status(409).body(java.util.Map.of("message", "Ya existe un usuario registrado con ese correo"));
        }
        if ("USER".equals(rol)) {
            rol = "USUARIO";
        }

        Usuario usuario = new Usuario();
        usuario.setNombre(nombre);
        usuario.setApellido(apellido);
        usuario.setCorreo(correo);
        if (!contrasena.startsWith("$2a$") && !contrasena.startsWith("$2b$")) {
            usuario.setContrasena(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(contrasena));
        } else {
            usuario.setContrasena(contrasena);
        }
        usuario.setRol(rol);
        usuario.setVerificado(true);

        Usuario saved = usuarioRepository.save(usuario);
        return ResponseEntity.ok(saved);
    }

    // Agregar registro IMC al historial del usuario
    @PutMapping("/{usuarioId}/imc")
    public ResponseEntity<?> agregarIMC(
            @PathVariable String usuarioId,
            @RequestBody IMCRegistro imcRegistro) {
        try {
            usuarioService.agregarIMC(usuarioId, imcRegistro);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // Eliminar todo el historial IMC del usuario
    @DeleteMapping("/{usuarioId}/imc")
    public ResponseEntity<?> eliminarHistorialIMC(@PathVariable String usuarioId) {
        Optional<Usuario> usuarioOpt = usuarioRepository.findById(usuarioId);
        if (!usuarioOpt.isPresent()) {
            return ResponseEntity.status(404).body("Usuario no encontrado");
        }
        Usuario usuario = usuarioOpt.get();
        usuario.setHistorialIMC(new ArrayList<>());
        usuarioRepository.save(usuario);
        return ResponseEntity.ok("Historial IMC eliminado");
    }

    // Eliminar un registro específico por fecha (ISO string exacta)
@DeleteMapping("/{usuarioId}/imc/{fecha}")
public ResponseEntity<?> eliminarRegistroIMC(
        @PathVariable String usuarioId,
        @PathVariable String fecha) {
    Optional<Usuario> usuarioOpt = usuarioRepository.findById(usuarioId);
    if (!usuarioOpt.isPresent()) {
        return ResponseEntity.status(404).body("Usuario no encontrado");
    }
    Usuario usuario = usuarioOpt.get();
    // Normaliza la fecha entrante a Instant
    try {
        Instant fechaInstant = Instant.parse(fecha.replace(" ", "+").replace("%2B", "+"));
        boolean removed = usuario.getHistorialIMC().removeIf(imc ->
            imc.getFecha() != null &&
            imc.getFecha().toInstant().equals(fechaInstant)
        );
        usuarioRepository.save(usuario);
        if (removed) {
            return ResponseEntity.ok("Registro eliminado");
        } else {
            return ResponseEntity.status(404).body("Registro no encontrado");
        }
    } catch(Exception e) {
        return ResponseEntity.badRequest().body("Formato de fecha incorrecto: " + e.getMessage());
    }
}

// Listar todos los usuarios
@GetMapping("/listar")
public ResponseEntity<?> listarUsuarios() {
    return ResponseEntity.ok(usuarioRepository.findAll());
}

// Listado optimizado y paginado para la app móvil (evita transferir miles de registros pesados)
@GetMapping("/resumen")
public ResponseEntity<List<Usuario>> listarUsuariosResumen(
        @RequestParam(required = false) String rol,
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "60") int limit) {
    int safeLimit = Math.max(1, Math.min(limit, 150));
    Query query = new Query();
    query.fields().exclude("contrasena").exclude("clasesSuscritas");

    List<Criteria> criteriaList = new ArrayList<>();
    if (rol != null && !rol.isBlank() && !"TODOS".equalsIgnoreCase(rol)) {
        if ("USUARIO".equalsIgnoreCase(rol)) {
            criteriaList.add(new Criteria().orOperator(
                    Criteria.where("rol").is("USUARIO"),
                    Criteria.where("rol").is("USER")
            ));
        } else {
            criteriaList.add(Criteria.where("rol").is(rol.toUpperCase()));
        }
    }
    if (q != null && !q.isBlank()) {
        Pattern regex = Pattern.compile(Pattern.quote(q.trim()), Pattern.CASE_INSENSITIVE);
        criteriaList.add(new Criteria().orOperator(
                Criteria.where("nombre").regex(regex),
                Criteria.where("apellido").regex(regex),
                Criteria.where("correo").regex(regex)
        ));
    }
    if (!criteriaList.isEmpty()) {
        query.addCriteria(new Criteria().andOperator(criteriaList.toArray(new Criteria[0])));
    }
    query.with(Sort.by(Sort.Direction.DESC, "_id")).limit(safeLimit);
    return ResponseEntity.ok(mongoTemplate.find(query, Usuario.class));
}

// Actualizar datos básicos del perfil (usado desde Flutter y Web)
@PutMapping("/{usuarioId}/perfil")
public ResponseEntity<?> actualizarPerfil(
        @PathVariable String usuarioId,
        @RequestBody java.util.Map<String, Object> updates) {
    Optional<Usuario> usuarioOpt = usuarioRepository.findById(usuarioId);
    if (usuarioOpt.isEmpty()) {
        return ResponseEntity.status(404).body("Usuario no encontrado");
    }
    Usuario u = usuarioOpt.get();
    if (updates.get("nombre") != null) u.setNombre(updates.get("nombre").toString());
    if (updates.get("apellido") != null) u.setApellido(updates.get("apellido").toString());
    if (updates.get("telefono") != null) u.setTelefono(updates.get("telefono").toString());
    if (updates.get("direccion") != null) u.setDireccion(updates.get("direccion").toString());
    if (updates.get("objetivo") != null) u.setObjetivo(updates.get("objetivo").toString());
    if (updates.get("nivelActividad") != null) u.setNivelActividad(updates.get("nivelActividad").toString());
    if (updates.get("edad") != null) u.setEdad(Integer.parseInt(updates.get("edad").toString()));
    if (updates.get("peso") != null) u.setPeso(Double.parseDouble(updates.get("peso").toString()));
    if (updates.get("altura") != null) u.setAltura(Integer.parseInt(updates.get("altura").toString()));
    return ResponseEntity.ok(usuarioRepository.save(u));
}
}