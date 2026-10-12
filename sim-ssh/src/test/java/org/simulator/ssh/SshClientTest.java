package org.simulator.ssh;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simulator.config.model.SshEndpoint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SshClientTest {

    @TempDir
    Path dir;
    private TestSshServer server;
    private SshClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new TestSshServer(dir, "SUPER.OPER", "secret", cmd -> switch (cmd) {
            case "echo hello" -> TestSshServer.Reply.ok("hello\n");
            case "fail" -> new TestSshServer.Reply("", "boom\n", 3);
            case "slow" -> {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {
                    // test
                }
                yield TestSshServer.Reply.ok("late");
            }
            default -> TestSshServer.Reply.ok("ran:" + cmd);
        }, Map.of("STATUS PATHWAY", "PATHWAY RUNNING", "WHO", "USER SUPER.OPER"), "TACL 1> ");
        client = new SshClient(endpoint("secret"), s -> s);
    }

    private SshEndpoint endpoint(String password) {
        SshEndpoint ep = new SshEndpoint();
        ep.setHost("127.0.0.1");
        ep.setPort(server.port());
        ep.setUsername("SUPER.OPER");
        ep.setPassword(password);
        ep.setStrictHostKeyChecking(false);
        ep.setConnectTimeoutMs(5000);
        ep.setCommandTimeoutMs(10000);
        return ep;
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close();
        server.close();
    }

    @Test
    void execCommands() {
        assertThat(client.testConnection()).startsWith("SSH-2.0");
        ExecResult ok = client.exec("echo hello");
        assertThat(ok.ok()).isTrue();
        assertThat(ok.stdout()).isEqualTo("hello\n");
        ExecResult ko = client.exec("fail");
        assertThat(ko.exitCode()).isEqualTo(3);
        assertThat(ko.stderr()).contains("boom");
        assertThat(client.exec("SQLCI").stdout()).isEqualTo("ran:SQLCI");
        assertThatThrownBy(() -> client.exec("slow", 500)).isInstanceOf(SshException.class).hasMessageContaining("timed out");
    }

    @Test
    void interactiveShell() {
        try (InteractiveShell shell = client.openShell("\r")) {
            shell.expect("TACL 1> ", 5000);
            assertThat(shell.command("STATUS PATHWAY", "TACL 1> ", 5000)).isEqualTo("PATHWAY RUNNING");
            assertThat(shell.command("WHO", "TACL 1> ", 5000)).isEqualTo("USER SUPER.OPER");
            assertThatThrownBy(() -> shell.expect("NEVER", 300)).isInstanceOf(SshException.class);
            assertThat(shell.transcript()).contains(">> WHO");
        }
    }

    @Test
    void sftpTransfer() throws Exception {
        Path local = dir.resolve("local.txt");
        Files.writeString(local, "PTLF-DATA");
        client.upload(local, "/remote.txt");
        assertThat(Files.readString(dir.resolve("remote.txt"))).isEqualTo("PTLF-DATA");
        assertThat(client.readRemoteFile("/remote.txt")).isEqualTo("PTLF-DATA");
        Path back = dir.resolve("back.txt");
        client.download("/remote.txt", back);
        assertThat(Files.readString(back)).isEqualTo("PTLF-DATA");
    }

    @Test
    void wrongPasswordFails() {
        try (SshClient bad = new SshClient(endpoint("wrong"), s -> s)) {
            assertThatThrownBy(bad::testConnection).isInstanceOf(SshException.class).hasMessageContaining("failed");
        }
    }
}
