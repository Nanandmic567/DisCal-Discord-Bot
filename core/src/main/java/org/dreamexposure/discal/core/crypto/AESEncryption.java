package org.dreamexposure.discal.core.crypto;

import org.apache.commons.codec.binary.Base64;
import org.dreamexposure.discal.core.object.GuildSettings;

import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Created by Nova Fox on 11/10/17.
 * Website: www.cloudcraftgaming.com
 * For Project: DisCal-Discord-Bot
 */
@SuppressWarnings("SpellCheckingInspection")
public class AESEncryption {
    //Public key, its fine if this is here, I don't even have access to private keys
    private static final String SECRET_KEY_1 = "E4B39r8F57F1Csde";

    private final IvParameterSpec ivParameterSpec;
    private final SecretKeySpec secretKeySpec;
    private final Cipher cipher;

    /**
     * Constructor for AESEncryption.
     * This class it to be used for encrypting/decrypting data.
     *
     * @throws Exception if something fails
     */
    public AESEncryption(GuildSettings gs) throws Exception {
        String SECRET_KEY_2 = gs.getPrivateKey();
        ivParameterSpec = new IvParameterSpec(SECRET_KEY_1.getBytes(StandardCharsets.UTF_8));
        secretKeySpec = new SecretKeySpec(SECRET_KEY_2.getBytes(StandardCharsets.UTF_8), "AES");
        cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING");
    }

    /**
     * Encrypt the Data with the secret key.
     * **WARNING** Can only be decrypted by this class!!!
     *
     * @param data The data to encrypt.
     * @return The encrypted, unreadable data.
     * @throws Exception If something fails.
     */
    public String encrypt(String data) throws Exception {
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec);
        byte[] encrypted = cipher.doFinal(data.getBytes());
        return Base64.encodeBase64String(encrypted);
    }

    /**
     * Decrypt the Data with the secret key.
     * **WARNING** Can only be encrypted with this class!!!
     * **WARNING** Decrypting of data can be a security risk! Treat with care!!
     *
     * @param encryptedData The data to decrypt.
     * @return The data, decrypted.
     * @throws Exception If something fails.
     */
    public String decrypt(String encryptedData) throws Exception {
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec);
        byte[] decryptedBytes = cipher.doFinal(Base64.decodeBase64(encryptedData));
        return new String(decryptedBytes);
    }
}