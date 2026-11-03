import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** Encrypt/decrypt local signing archives. Key files must stay outside Git. Requires JDK 11+. */
public final class BackupCrypto {
    private static final byte[] AAD = "nok-signing-backup-v1".getBytes(StandardCharsets.UTF_8);
    private static byte[] crypt(int mode, byte[] data, byte[] key, byte[] nonce) throws Exception {
        if (key.length != 32 || nonce.length != 12) throw new IllegalArgumentException("Invalid key or nonce length");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(AAD);
        return cipher.doFinal(data);
    }
    private static String field(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (!matcher.find()) throw new IllegalArgumentException("Missing field: " + name);
        return matcher.group(1);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !(args[0].equals("encrypt") || args[0].equals("decrypt"))) {
            throw new IllegalArgumentException("Usage: java BackupCrypto.java encrypt|decrypt INPUT KEY_FILE OUTPUT");
        }
        Path input = Paths.get(args[1]), keyFile = Paths.get(args[2]), output = Paths.get(args[3]);
        if (Files.exists(output)) throw new FileAlreadyExistsException(output.toString());
        if (args[0].equals("encrypt")) {
            SecureRandom random = new SecureRandom();
            byte[] key = new byte[32], nonce = new byte[12];
            random.nextBytes(key); random.nextBytes(nonce);
            byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, Files.readAllBytes(input), key, nonce);
            String json = "{\n  \"format\": \"nok-signing-backup-v1\",\n  \"algorithm\": \"AES-256-GCM\",\n"
                    + "  \"nonce\": \"" + Base64.getEncoder().encodeToString(nonce) + "\",\n"
                    + "  \"ciphertext\": \"" + Base64.getEncoder().encodeToString(encrypted) + "\"\n}\n";
            Files.write(keyFile, key, StandardOpenOption.CREATE_NEW);
            Files.write(output, json.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
            Arrays.fill(key, (byte) 0);
        } else {
            String json = new String(Files.readAllBytes(input), StandardCharsets.UTF_8);
            if (!field(json, "format").equals("nok-signing-backup-v1") || !field(json, "algorithm").equals("AES-256-GCM"))
                throw new IllegalArgumentException("Unsupported backup format");
            byte[] key = Files.readAllBytes(keyFile);
            byte[] plaintext = crypt(Cipher.DECRYPT_MODE, Base64.getDecoder().decode(field(json, "ciphertext")),
                    key, Base64.getDecoder().decode(field(json, "nonce")));
            Arrays.fill(key, (byte) 0);
            Files.write(output, plaintext, StandardOpenOption.CREATE_NEW);
            Arrays.fill(plaintext, (byte) 0);
        }
    }
}
