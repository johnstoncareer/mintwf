package com.intwfs.mintwf.store.jdbc;

import com.intwfs.mintwf.core.MintwfException;
import com.intwfs.mintwf.core.api.InstanceQuery;
import com.intwfs.mintwf.core.api.InstanceStatus;
import com.intwfs.mintwf.core.api.NodeInstance;
import com.intwfs.mintwf.core.spi.DeploymentRecord;
import com.intwfs.mintwf.core.spi.InstanceChange;
import com.intwfs.mintwf.core.spi.InstanceState;
import com.intwfs.mintwf.core.spi.Job;
import com.intwfs.mintwf.core.spi.OptimisticLockException;
import com.intwfs.mintwf.core.spi.ProcessStore;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

/**
 * A {@link ProcessStore} backed by a relational database through JDBC. It is safe to share one database between
 * processes, such as the CLI and the worker.
 *
 * <p>The schema is created or upgraded when the store is constructed. Tables are prefixed {@code mintwf_}.
 */
public final class JdbcProcessStore implements ProcessStore {

    /** SQLState for a unique or primary key violation. */
    private static final String UNIQUE_VIOLATION = "23505";

    private static final String JOB_COLUMNS = "id, instance_id, execution_id, node_id, type, due_at, retries, "
            + "last_error, failed_at, lock_owner, lock_expiry, created_at";

    private static final String INSTANCE_COLUMNS = "id, process_key, process_version, business_key, status, "
            + "started_at, ended_at, revision, doc";

    private static final String NODE_INSTANCE_COLUMNS = "id, instance_id, execution_id, node_id, node_type, state, "
            + "started_at, ended_at";

    private final DataSource dataSource;

    /**
     * @throws MintwfException if the schema cannot be created or upgraded
     */
    public JdbcProcessStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        try {
            new SchemaMigrator(dataSource).migrate();
        } catch (SQLException e) {
            throw new MintwfException("cannot migrate the mintwf schema: " + e.getMessage(), e);
        }
    }

    /**
     * Opens, or creates, an H2 database in a file. {@code AUTO_SERVER} mode lets several processes use the file at
     * once: the first to open it serves the others over a local socket.
     *
     * @param file the database path without H2's {@code .mv.db} suffix
     */
    public static JdbcProcessStore h2(Path file) {
        JdbcDataSource dataSource = new JdbcDataSource();
        String path = file.toAbsolutePath().toString().replace('\\', '/');
        dataSource.setURL("jdbc:h2:file:" + path + ";AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1");
        return new JdbcProcessStore(dataSource);
    }

    @Override
    public void insertDeployment(DeploymentRecord deployment) {
        run(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO mintwf_deployment "
                    + "(process_key, version, name, hash, xml, deployed_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, deployment.processKey());
                insert.setInt(2, deployment.version());
                insert.setString(3, deployment.name());
                insert.setString(4, deployment.hash());
                insert.setBytes(5, deployment.xml());
                setInstant(insert, 6, deployment.deployedAt());
                insert.executeUpdate();
            } catch (SQLException e) {
                throw conflictOr(e, "process '" + deployment.processKey() + "' version " + deployment.version()
                        + " already exists");
            }
            return null;
        });
    }

    @Override
    public Optional<DeploymentRecord> latestDeployment(String processKey) {
        return queryOne("SELECT process_key, version, name, hash, xml, deployed_at FROM mintwf_deployment "
                + "WHERE process_key = ? ORDER BY version DESC FETCH FIRST 1 ROWS ONLY",
                JdbcProcessStore::deployment, processKey);
    }

    @Override
    public Optional<DeploymentRecord> deployment(String processKey, int version) {
        return queryOne("SELECT process_key, version, name, hash, xml, deployed_at FROM mintwf_deployment "
                + "WHERE process_key = ? AND version = ?", JdbcProcessStore::deployment, processKey, version);
    }

    @Override
    public void save(InstanceChange change) {
        InstanceState state = change.state();
        run(connection -> {
            connection.setAutoCommit(false);
            try {
                if (change.expectedRevision() == null) {
                    insertInstance(connection, state);
                } else {
                    updateInstance(connection, state, change.expectedRevision());
                }
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM mintwf_job WHERE id = ?")) {
                    for (String id : change.deletedJobIds()) {
                        delete.setString(1, id);
                        delete.addBatch();
                    }
                    delete.executeBatch();
                }
                for (Job job : change.createdJobs()) {
                    insertJob(connection, job);
                }
                for (NodeInstance node : change.nodeInstances()) {
                    writeNodeInstance(connection, node);
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
            return null;
        });
    }

    private static void insertInstance(Connection connection, InstanceState state) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO mintwf_instance ("
                + INSTANCE_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, state.id());
            insert.setString(2, state.processKey());
            insert.setInt(3, state.processVersion());
            insert.setString(4, state.businessKey());
            insert.setString(5, state.status().name());
            setInstant(insert, 6, state.startedAt());
            setInstant(insert, 7, state.endedAt());
            insert.setLong(8, state.revision());
            insert.setString(9, StateCodec.encode(state));
            insert.executeUpdate();
        } catch (SQLException e) {
            throw conflictOr(e, "instance '" + state.id() + "' already exists");
        }
    }

    private static void updateInstance(Connection connection, InstanceState state, long expectedRevision)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE mintwf_instance SET business_key = ?, "
                + "status = ?, ended_at = ?, revision = ?, doc = ? WHERE id = ? AND revision = ?")) {
            update.setString(1, state.businessKey());
            update.setString(2, state.status().name());
            setInstant(update, 3, state.endedAt());
            update.setLong(4, state.revision());
            update.setString(5, StateCodec.encode(state));
            update.setString(6, state.id());
            update.setLong(7, expectedRevision);
            if (update.executeUpdate() != 1) {
                throw new OptimisticLockException("instance '" + state.id() + "' was changed concurrently");
            }
        }
    }

    private static void insertJob(Connection connection, Job job) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO mintwf_job (" + JOB_COLUMNS
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, job.id());
            insert.setString(2, job.instanceId());
            insert.setString(3, job.executionId());
            insert.setString(4, job.nodeId());
            insert.setString(5, job.type().name());
            setInstant(insert, 6, job.dueAt());
            insert.setInt(7, job.retries());
            insert.setString(8, job.lastError());
            setInstant(insert, 9, job.failedAt());
            insert.setString(10, job.lockOwner());
            setInstant(insert, 11, job.lockExpiry());
            setInstant(insert, 12, job.createdAt());
            insert.executeUpdate();
        } catch (SQLException e) {
            throw conflictOr(e, "job '" + job.id() + "' already exists");
        }
    }

    /** Ends a stored node instance, or inserts it if it is new. Only its state and end time ever change. */
    private static void writeNodeInstance(Connection connection, NodeInstance node) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE mintwf_node_instance SET state = ?, ended_at = ? WHERE id = ?")) {
            update.setString(1, node.state().name());
            setInstant(update, 2, node.endedAt());
            update.setString(3, node.id());
            if (update.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO mintwf_node_instance ("
                + NODE_INSTANCE_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, node.id());
            insert.setString(2, node.instanceId());
            insert.setString(3, node.executionId());
            insert.setString(4, node.nodeId());
            insert.setString(5, node.nodeType());
            insert.setString(6, node.state().name());
            setInstant(insert, 7, node.startedAt());
            setInstant(insert, 8, node.endedAt());
            insert.executeUpdate();
        }
    }

    @Override
    public Optional<InstanceState> instance(String id) {
        return queryOne("SELECT " + INSTANCE_COLUMNS + " FROM mintwf_instance WHERE id = ?",
                JdbcProcessStore::instance, id);
    }

    @Override
    public List<InstanceState> instances(InstanceQuery query) {
        StringBuilder sql = new StringBuilder("SELECT " + INSTANCE_COLUMNS + " FROM mintwf_instance WHERE 1 = 1");
        List<Object> parameters = new ArrayList<>();
        if (query.processKey() != null) {
            sql.append(" AND process_key = ?");
            parameters.add(query.processKey());
        }
        if (query.status() != null) {
            sql.append(" AND status = ?");
            parameters.add(query.status().name());
        }
        sql.append(" ORDER BY started_at, seq");
        return queryList(sql.toString(), JdbcProcessStore::instance, parameters.toArray());
    }

    @Override
    public List<NodeInstance> nodeInstances(String instanceId) {
        return queryList("SELECT " + NODE_INSTANCE_COLUMNS + " FROM mintwf_node_instance WHERE instance_id = ? "
                + "ORDER BY seq", JdbcProcessStore::nodeInstance, instanceId);
    }

    @Override
    public List<NodeInstance> activeNodeInstances(String instanceId) {
        return queryList("SELECT " + NODE_INSTANCE_COLUMNS + " FROM mintwf_node_instance WHERE instance_id = ? "
                + "AND state = ? ORDER BY seq", JdbcProcessStore::nodeInstance, instanceId,
                NodeInstance.State.ACTIVE.name());
    }

    @Override
    public Optional<Job> job(String id) {
        return queryOne("SELECT " + JOB_COLUMNS + " FROM mintwf_job WHERE id = ?", JdbcProcessStore::job, id);
    }

    @Override
    public List<Job> jobs(String instanceId) {
        return queryList("SELECT " + JOB_COLUMNS + " FROM mintwf_job WHERE instance_id = ? ORDER BY created_at, seq",
                JdbcProcessStore::job, instanceId);
    }

    @Override
    public List<Job> acquireJobs(String owner, Instant now, Instant lockExpiry, int limit) {
        String claimable = "retries > 0 AND due_at <= ? AND (lock_owner IS NULL OR lock_expiry < ?)";
        List<String> candidates = queryList("SELECT id FROM mintwf_job WHERE " + claimable
                        + " ORDER BY due_at, seq FETCH FIRST ? ROWS ONLY",
                result -> result.getString(1), timestamp(now), timestamp(now), limit);
        List<Job> claimed = new ArrayList<>();
        for (String id : candidates) {
            // Claiming is a conditional update, so of two workers racing for a job only one gets it.
            boolean won = run(connection -> {
                try (PreparedStatement update = connection.prepareStatement("UPDATE mintwf_job SET lock_owner = ?, "
                        + "lock_expiry = ? WHERE id = ? AND " + claimable)) {
                    update.setString(1, owner);
                    setInstant(update, 2, lockExpiry);
                    update.setString(3, id);
                    setInstant(update, 4, now);
                    setInstant(update, 5, now);
                    return update.executeUpdate() == 1;
                }
            });
            if (won) {
                job(id).filter(job -> owner.equals(job.lockOwner())).ifPresent(claimed::add);
            }
        }
        return claimed;
    }

    @Override
    public boolean updateJob(Job job, String expectedLockOwner) {
        String ownerCondition = expectedLockOwner == null ? "lock_owner IS NULL" : "lock_owner = ?";
        return run(connection -> {
            try (PreparedStatement update = connection.prepareStatement("UPDATE mintwf_job SET due_at = ?, "
                    + "retries = ?, last_error = ?, failed_at = ?, lock_owner = ?, lock_expiry = ? "
                    + "WHERE id = ? AND " + ownerCondition)) {
                setInstant(update, 1, job.dueAt());
                update.setInt(2, job.retries());
                update.setString(3, job.lastError());
                setInstant(update, 4, job.failedAt());
                update.setString(5, job.lockOwner());
                setInstant(update, 6, job.lockExpiry());
                update.setString(7, job.id());
                if (expectedLockOwner != null) {
                    update.setString(8, expectedLockOwner);
                }
                return update.executeUpdate() == 1;
            }
        });
    }

    @Override
    public void deleteJob(String id) {
        run(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM mintwf_job WHERE id = ?")) {
                delete.setString(1, id);
                delete.executeUpdate();
            }
            return null;
        });
    }

    private static DeploymentRecord deployment(ResultSet row) throws SQLException {
        return new DeploymentRecord(row.getString(1), row.getInt(2), row.getString(3), row.getString(4).strip(),
                row.getBytes(5), instant(row, 6));
    }

    private static InstanceState instance(ResultSet row) throws SQLException {
        StateCodec.Doc doc = StateCodec.decode(row.getString(9));
        return new InstanceState(row.getString(1), row.getString(2), row.getInt(3), row.getString(4),
                InstanceStatus.valueOf(row.getString(5)), doc.variables(), doc.executions(), doc.nextExecutionId(),
                instant(row, 6), instant(row, 7), row.getLong(8));
    }

    private static NodeInstance nodeInstance(ResultSet row) throws SQLException {
        return new NodeInstance(row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                row.getString(5), NodeInstance.State.valueOf(row.getString(6)), instant(row, 7), instant(row, 8));
    }

    private static Job job(ResultSet row) throws SQLException {
        return new Job(row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                Job.Type.valueOf(row.getString(5)), instant(row, 6), row.getInt(7), row.getString(8),
                instant(row, 9), row.getString(10), instant(row, 11), instant(row, 12));
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet row) throws SQLException;
    }

    private <T> T run(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            return work.apply(connection);
        } catch (SQLException e) {
            throw new MintwfException("database error: " + e.getMessage(), e);
        }
    }

    private <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... parameters) {
        List<T> rows = queryList(sql, mapper, parameters);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... parameters) {
        return run(connection -> {
            try (PreparedStatement query = connection.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) {
                    query.setObject(i + 1, parameters[i]);
                }
                List<T> rows = new ArrayList<>();
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        rows.add(mapper.map(result));
                    }
                }
                return rows;
            }
        });
    }

    private static RuntimeException conflictOr(SQLException e, String conflictMessage) throws SQLException {
        if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
            return new OptimisticLockException(conflictMessage);
        }
        throw e;
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void setInstant(PreparedStatement statement, int index, Instant instant) throws SQLException {
        if (instant == null) {
            statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            statement.setObject(index, timestamp(instant));
        }
    }

    private static Instant instant(ResultSet row, int index) throws SQLException {
        OffsetDateTime value = row.getObject(index, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
