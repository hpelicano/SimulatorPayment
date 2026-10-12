package org.simulator.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simulator.common.SimulatorException;
import org.simulator.config.crypto.SecretCipher;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigServiceTest {

    @TempDir
    Path dir;

    @Test
    void cipherRoundTripAndWrongPassword() {
        SecretCipher c = new SecretCipher("super-secret".toCharArray());
        String enc = c.encrypt("p@ssw0rd");
        assertThat(SecretCipher.isEncrypted(enc)).isTrue();
        assertThat(c.decrypt(enc)).isEqualTo("p@ssw0rd");
        assertThatThrownBy(() -> new SecretCipher("other-secret".toCharArray()).decrypt(enc))
                .isInstanceOf(SimulatorException.class);
    }

    @Test
    void createsDefaultsAndUnlocks() {
        ConfigService cfg = new ConfigService(dir).load();
        assertThat(Files.exists(dir.resolve("environments.json"))).isTrue();
        assertThat(cfg.validate()).isEmpty();
        assertThat(cfg.activeEnvironment().getAcquirer().getPort()).isEqualTo(9100);

        assertThat(cfg.unlock("master-pass".toCharArray())).isTrue();
        String enc = cfg.encryptSecret("guardian-pwd");
        cfg.activeEnvironment().getGuardian().setPassword(enc);
        cfg.save();

        ConfigService reloaded = new ConfigService(dir).load();
        assertThat(reloaded.unlock("wrong-pass!".toCharArray())).isFalse();
        assertThatThrownBy(() -> reloaded.secret(enc)).isInstanceOf(SimulatorException.class);
        assertThat(reloaded.unlock("master-pass".toCharArray())).isTrue();
        assertThat(reloaded.secret(reloaded.activeEnvironment().getGuardian().getPassword())).isEqualTo("guardian-pwd");
    }

    @Test
    void pathwayCredentialsAndServersPersistWithValidation() {
        ConfigService cfg = new ConfigService(dir).load();
        var xp = cfg.activeEnvironment().getPathway();
        assertThat(xp.getPort()).isEqualTo(22);
        xp.setHost("pathway.example");
        xp.setPort(22);
        xp.setMode("SSH");
        xp.setUsername("OPER.PATH");
        xp.setPassword("test-password");
        xp.setServers(java.util.List.of("$POS", "$ATM"));
        xp.setServerName("$POS");
        assertThat(cfg.validate()).isEmpty();
        cfg.save();
        var reloaded = new ConfigService(dir).load().activeEnvironment().getPathway();
        assertThat(reloaded.getHost()).isEqualTo("pathway.example");
        assertThat(reloaded.sshEndpoint().getUsername()).isEqualTo("OPER.PATH");
        assertThat(reloaded.sshEndpoint().getPassword()).isEqualTo("test-password");
        assertThat(reloaded.serverNames()).containsExactly("$POS", "$ATM");
        assertThat(reloaded.getServerName()).isEqualTo("$POS");
        reloaded.setMode("INVALID");
        reloaded.setPort(-1);
        cfg.activeEnvironment().setPathway(reloaded);
        assertThat(cfg.validate()).anyMatch(error -> error.contains("pathway.mode"))
                .anyMatch(error -> error.contains("pathway.port"));
    }

    @Test
    void olderEnvironmentJsonDropsXpnetAndHasDocBuilderDefaults() {
        var env = org.simulator.common.json.Json.read("{\"name\":\"OLD\"}",
                org.simulator.config.model.EnvironmentConfig.class);
        assertThat(env.getDocBuilder()).isNotNull();
        assertThat(env.getPathway().getMode()).isEqualTo("SSH");
        assertThat(org.simulator.common.json.Json.write(env)).doesNotContain("xpnet");
        assertThat(env.getPathway().sshEndpoint()).isNotSameAs(env.getGuardian());
    }

    @Test
    void legacyBase24ConfigurationIsUpgradedAndPersisted() throws Exception {
        new ConfigService(dir).load();
        Files.writeString(dir.resolve(ConfigService.ENV_FILE), """
                {"activeEnvironment":"OLD","environments":[{
                  "name":"OLD","guardian":{"username":"OPER.OLD","password":"old-password"},
                  "pathway":{"mode":"SOCKET","host":"legacy.example","port":7001,"pathmon":"$OLD"},
                  "xpnet":{"mode":"SOCKET","port":7002}
                }]}
                """);
        ConfigService cfg = new ConfigService(dir).load();
        var path = cfg.activeEnvironment().getPathway();
        assertThat(path.getMode()).isEqualTo("SSH");
        assertThat(path.getPort()).isEqualTo(22);
        assertThat(path.getUsername()).isEqualTo("OPER.OLD");
        assertThat(path.getPassword()).isEqualTo("old-password");
        assertThat(path.serverNames()).containsExactly("$OLD");
        assertThat(Files.readString(dir.resolve(ConfigService.ENV_FILE))).doesNotContain("xpnet")
                .contains("docBuilder", "server_name", "servers");
        path.setPassword("independent");
        assertThat(cfg.activeEnvironment().getGuardian().getPassword()).isEqualTo("old-password");
    }
}
