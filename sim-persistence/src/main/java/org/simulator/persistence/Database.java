package org.simulator.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.simulator.common.SimulatorException;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/** Embedded H2 database (file mode) with a Hikari pool and Flyway managed schema. */
public final class Database implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final String url;

    private Database(String url, String user, String password) {
        this.url = url;
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(10);
        cfg.setPoolName("sim-h2");
        this.dataSource = new HikariDataSource(cfg);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    /** File database under {@code dir/sim}. AUTO_SERVER allows external tools to connect concurrently. */
    public static Database file(Path dir) {
        String url = "jdbc:h2:file:" + dir.resolve("sim").toAbsolutePath() + ";AUTO_SERVER=TRUE";
        return new Database(url, "sa", "");
    }

    public static Database inMemory(String name) {
        return new Database("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    public static Path defaultDir() {
        return Path.of(System.getProperty("user.home"), ".simulator", "data");
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public String url() {
        return url;
    }

    public Connection connection() {
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            throw new SimulatorException("Cannot obtain DB connection", e);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
