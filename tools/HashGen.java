import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Utilidad de un solo uso: genera el hash BCrypt de una contrasena para
 * anadirla a local-users.json. Compilar/ejecutar con el classpath del
 * proyecto (ver README.md).
 */
public class HashGen {
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Uso: HashGen <contrasena>");
            System.exit(1);
        }
        System.out.println(new BCryptPasswordEncoder().encode(args[0]));
    }
}
