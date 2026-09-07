// SPDX-License-Identifier: Apache-2.0
package pl.somaskan.questgpt.adb;

import android.content.Context;
import android.os.Build;
import android.sun.misc.BASE64Encoder;
import android.sun.security.provider.X509Factory;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.PrivateKeyUsageExtension;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.EncodedKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.Random;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * ADB connection identity stored privately inside QuestGPT.
 * The same key is reused so Wireless Debugging does not need to be paired after every launch.
 */
public final class QuestAdbConnectionManager extends AbsAdbConnectionManager {
    private static AbsAdbConnectionManager INSTANCE;

    public static synchronized AbsAdbConnectionManager getInstance(@NonNull Context context) throws Exception {
        if (INSTANCE == null) INSTANCE = new QuestAdbConnectionManager(context.getApplicationContext());
        return INSTANCE;
    }

    private final PrivateKey privateKey;
    private final Certificate certificate;

    private QuestAdbConnectionManager(@NonNull Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        setThrowOnUnauthorised(true);

        PrivateKey storedKey = readPrivateKey(context);
        Certificate storedCertificate = readCertificate(context);
        if (storedKey == null || storedCertificate == null) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, SecureRandom.getInstance("SHA1PRNG"));
            KeyPair keyPair = generator.generateKeyPair();
            storedKey = keyPair.getPrivate();
            storedCertificate = generateCertificate(keyPair.getPublic(), storedKey);
            writePrivateKey(context, storedKey);
            writeCertificate(context, storedCertificate);
        }
        privateKey = storedKey;
        certificate = storedCertificate;
    }

    @NonNull
    @Override
    protected PrivateKey getPrivateKey() {
        return privateKey;
    }

    @NonNull
    @Override
    protected Certificate getCertificate() {
        return certificate;
    }

    @NonNull
    @Override
    protected String getDeviceName() {
        return "QuestGPT";
    }

    private static Certificate generateCertificate(@NonNull PublicKey publicKey, @NonNull PrivateKey privateKey) throws Exception {
        String subject = "CN=QuestGPT Wireless ADB";
        String algorithmName = "SHA512withRSA";
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 60_000L);
        Date notAfter = new Date(now + 10L * 365L * 24L * 60L * 60L * 1000L);

        CertificateExtensions extensions = new CertificateExtensions();
        extensions.set("SubjectKeyIdentifier", new SubjectKeyIdentifierExtension(
                new KeyIdentifier(publicKey).getIdentifier()));
        extensions.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));

        X500Name x500Name = new X500Name(subject);
        CertificateValidity validity = new CertificateValidity(notBefore, notAfter);
        X509CertInfo info = new X509CertInfo();
        info.set("version", new CertificateVersion(2));
        info.set("serialNumber", new CertificateSerialNumber(new Random().nextInt() & Integer.MAX_VALUE));
        info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithmName)));
        info.set("subject", new CertificateSubjectName(x500Name));
        info.set("key", new CertificateX509Key(publicKey));
        info.set("validity", validity);
        info.set("issuer", new CertificateIssuerName(x500Name));
        info.set("extensions", extensions);

        X509CertImpl certificate = new X509CertImpl(info);
        certificate.sign(privateKey, algorithmName);
        return certificate;
    }

    @Nullable
    private static Certificate readCertificate(@NonNull Context context) {
        File file = new File(context.getFilesDir(), "questgpt-adb-cert.pem");
        if (!file.exists()) return null;
        try (InputStream input = new FileInputStream(file)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(input);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeCertificate(@NonNull Context context, @NonNull Certificate certificate) throws Exception {
        File file = new File(context.getFilesDir(), "questgpt-adb-cert.pem");
        BASE64Encoder encoder = new BASE64Encoder();
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(X509Factory.BEGIN_CERT.getBytes(StandardCharsets.UTF_8));
            output.write('\n');
            encoder.encode(certificate.getEncoded(), output);
            output.write('\n');
            output.write(X509Factory.END_CERT.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Nullable
    private static PrivateKey readPrivateKey(@NonNull Context context) {
        File file = new File(context.getFilesDir(), "questgpt-adb-private.key");
        if (!file.exists()) return null;
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int total = 0;
            while (total < bytes.length) {
                int read = input.read(bytes, total, bytes.length - total);
                if (read < 0) break;
                total += read;
            }
            KeyFactory factory = KeyFactory.getInstance("RSA");
            EncodedKeySpec spec = new PKCS8EncodedKeySpec(bytes);
            return factory.generatePrivate(spec);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writePrivateKey(@NonNull Context context, @NonNull PrivateKey privateKey) throws IOException {
        File file = new File(context.getFilesDir(), "questgpt-adb-private.key");
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(privateKey.getEncoded());
        }
    }
}
