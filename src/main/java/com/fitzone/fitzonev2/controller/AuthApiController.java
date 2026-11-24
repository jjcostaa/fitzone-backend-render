package com.fitzone.fitzonev2.controller;

import com.fitzone.fitzonev2.model.Admin;
import com.fitzone.fitzonev2.model.Usuario;
import com.fitzone.fitzonev2.repository.AdminRepository;
import com.fitzone.fitzonev2.repository.UsuarioRepository;
import com.fitzone.fitzonev2.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthApiController {

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private AdminRepository adminRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @PostMapping("/login")
    public ResponseEntity<?> loginApi(@RequestBody Map<String, String> loginData) {
        String correo = loginData.get("correo");
        String contrasena = loginData.get("contrasena");

        if (correo == null || contrasena == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Correo y contraseña requeridos"));
        }

        Optional<Usuario> optionalUsuario = usuarioRepository.findFirstByCorreoIgnoreCase(correo.trim());
        if (optionalUsuario.isPresent()) {
            Usuario usuario = optionalUsuario.get();
            boolean passwordMatches = usuario.getContrasena() != null
                    && (passwordEncoder.matches(contrasena, usuario.getContrasena())
                        || contrasena.equals(usuario.getContrasena()));
            if (passwordMatches) {
                if (Boolean.TRUE.equals(usuario.isVerificado())) {
                    String token = jwtUtil.generateToken(usuario.getId());
                    return ResponseEntity.ok(buildAuthPayload(token, usuario));
                } else {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(Map.of("message", "Cuenta pendiente de verificación por correo"));
                }
            }
        }

        // Soporte adicional si el administrador está en la colección admins
        Optional<Admin> optionalAdmin = adminRepository.findByCorreo(correo.trim());
        if (optionalAdmin.isPresent()) {
            Admin admin = optionalAdmin.get();
            boolean validPass = passwordEncoder.matches(contrasena, admin.getContrasena())
                    || contrasena.equals(admin.getContrasena());
            if (validPass) {
                String token = jwtUtil.generateToken(admin.getId());
                Map<String, Object> response = new HashMap<>();
                response.put("token", token);
                response.put("usuarioId", admin.getId());
                response.put("rol", "ADMIN");
                response.put("nombre", admin.getNombre());
                response.put("apellido", "");
                response.put("correo", admin.getCorreo());
                return ResponseEntity.ok(response);
            }
        }

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", "Correo o contraseña incorrectos o cuenta no verificada"));
    }

    @PostMapping("/register")
    public ResponseEntity<?> registerApi(@RequestBody Map<String, Object> formData) {
        try {
            String correo = formData.get("correo") != null ? formData.get("correo").toString().trim() : "";
            String contrasena = formData.get("contrasena") != null ? formData.get("contrasena").toString() : "";
            String nombre = formData.get("nombre") != null ? formData.get("nombre").toString().trim() : "";

            if (correo.isEmpty() || contrasena.length() < 6 || nombre.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "Nombre, correo y contraseña (mínimo 6 caracteres) son obligatorios"));
            }

            if (usuarioRepository.findFirstByCorreoIgnoreCase(correo).isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("message", "Ya existe una cuenta registrada con el correo: " + correo));
            }

            Usuario usuario = new Usuario();
            usuario.setNombre(nombre);
            usuario.setApellido(formData.get("apellido") != null ? formData.get("apellido").toString().trim() : "");
            usuario.setCorreo(correo.toLowerCase());
            usuario.setContrasena(passwordEncoder.encode(contrasena));
            usuario.setTelefono(formData.get("telefono") != null ? formData.get("telefono").toString() : "");
            usuario.setDireccion(formData.get("direccion") != null ? formData.get("direccion").toString() : "");
            usuario.setGenero(formData.get("genero") != null ? formData.get("genero").toString() : "hombre");
            usuario.setObjetivo(formData.get("objetivo") != null ? formData.get("objetivo").toString() : "mantener");
            usuario.setNivelActividad(formData.get("nivelActividad") != null ? formData.get("nivelActividad").toString() : "Moderado");
            usuario.setRol("USUARIO");
            usuario.setVerificado(true);

            if (formData.get("edad") != null && !formData.get("edad").toString().isEmpty()) {
                usuario.setEdad(Integer.parseInt(formData.get("edad").toString()));
            }
            if (formData.get("peso") != null && !formData.get("peso").toString().isEmpty()) {
                usuario.setPeso(Double.parseDouble(formData.get("peso").toString()));
            }
            if (formData.get("altura") != null && !formData.get("altura").toString().isEmpty()) {
                usuario.setAltura(Integer.parseInt(formData.get("altura").toString()));
            }

            Usuario guardado = usuarioRepository.save(usuario);
            String token = jwtUtil.generateToken(guardado.getId());
            return ResponseEntity.status(HttpStatus.CREATED).body(buildAuthPayload(token, guardado));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Error al registrar usuario: " + e.getMessage()));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Token no proporcionado"));
        }

        String token = authHeader.substring(7);
        if (!jwtUtil.validateToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Token inválido o expirado"));
        }

        String userId = jwtUtil.extractUserIdFromToken(token);
        Optional<Usuario> usuarioOpt = usuarioRepository.findById(userId);
        if (usuarioOpt.isPresent()) {
            return ResponseEntity.ok(buildAuthPayload(token, usuarioOpt.get()));
        }

        Optional<Admin> adminOpt = adminRepository.findById(userId);
        if (adminOpt.isPresent()) {
            Admin admin = adminOpt.get();
            Map<String, Object> response = new HashMap<>();
            response.put("token", token);
            response.put("usuarioId", admin.getId());
            response.put("rol", "ADMIN");
            response.put("nombre", admin.getNombre());
            response.put("apellido", "");
            response.put("correo", admin.getCorreo());
            return ResponseEntity.ok(response);
        }

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Usuario no encontrado"));
    }

    private Map<String, Object> buildAuthPayload(String token, Usuario usuario) {
        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("usuarioId", usuario.getId());
        response.put("rol", usuario.getRol());
        response.put("nombre", usuario.getNombre());
        response.put("apellido", usuario.getApellido());
        response.put("correo", usuario.getCorreo());
        response.put("edad", usuario.getEdad());
        response.put("peso", usuario.getPeso());
        response.put("altura", usuario.getAltura());
        response.put("objetivo", usuario.getObjetivo());
        response.put("nivelActividad", usuario.getNivelActividad());
        return response;
    }
}
