package org.simulator.config.crypto;

import org.simulator.common.SimulatorException;
import org.simulator.config.model.TlsConfig;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.function.UnaryOperator;

/** Builds {@link SSLContext} instances (TLS / mutual TLS) from a {@link TlsConfig}. */
public final class SslContexts {

    private SslContexts() {
    }

    /**
     * @param secrets resolves ENC(...) passwords to plain text
     */
    public static SSLContext create(TlsConfig cfg, UnaryOperator<String> secrets) {
        try {
            KeyManagerFactory kmf = null;
            if (cfg.getKeyStorePath() != null && !cfg.getKeyStorePath().isBlank()) {
                char[] pwd = password(cfg.getKeyStorePassword(), secrets);
                KeyStore ks = load(cfg.getKeyStorePath(), cfg.getKeyStoreType(), pwd);
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, pwd);
            }
            TrustManager[] tms;
            if (cfg.isTrustAll()) {
                tms = new TrustManager[]{new TrustAllManager()};
            } else if (cfg.getTrustStorePath() != null && !cfg.getTrustStorePath().isBlank()) {
                KeyStore ts = load(cfg.getTrustStorePath(), cfg.getTrustStoreType(), password(cfg.getTrustStorePassword(), secrets));
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ts);
                tms = tmf.getTrustManagers();
            } else {
                tms = null;
            }
            String protocol = cfg.getProtocols().contains("TLSv1.3") ? "TLSv1.3" : "TLSv1.2";
            SSLContext ctx = SSLContext.getInstance(protocol);
            ctx.init(kmf == null ? null : kmf.getKeyManagers(), tms, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new SimulatorException("Cannot build SSLContext: " + e.getMessage(), e);
        }
    }

    private static char[] password(String value, UnaryOperator<String> secrets) {
        String plain = value == null ? null : secrets.apply(value);
        return plain == null ? new char[0] : plain.toCharArray();
    }

    private static KeyStore load(String path, String type, char[] pwd) throws Exception {
        KeyStore ks = KeyStore.getInstance(type == null ? "PKCS12" : type);
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            ks.load(in, pwd);
        }
        return ks;
    }

    private static final class TrustAllManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
