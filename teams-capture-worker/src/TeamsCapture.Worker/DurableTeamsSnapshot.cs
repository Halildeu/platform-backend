using Microsoft.Data.Sqlite;

namespace TeamsCapture.Worker;

/// <summary>Single-writer metadata snapshot; a successful write is committed before Graph dispatch.</summary>
internal sealed class DurableTeamsSnapshot(string legacyPath, string kind)
{
    // Keep the legacy JSON untouched. Once this database exists, never fall back to
    // stale JSON, including after an incomplete initialization or a corrupt database.
    private readonly string databasePath = legacyPath + ".sqlite3";

    public string? Read()
    {
        if (!File.Exists(databasePath) && !Directory.Exists(databasePath))
            return File.Exists(legacyPath) ? File.ReadAllText(legacyPath) : null;
        try
        {
            using var connection = Open(create: false);
            using var command = connection.CreateCommand();
            command.CommandText = "SELECT payload FROM snapshot WHERE slot = 1 AND version = 1 AND kind = $kind";
            command.Parameters.AddWithValue("$kind", kind);
            return command.ExecuteScalar() as string
                ?? throw new InvalidDataException("Missing or incompatible Teams durable snapshot.");
        }
        catch (SqliteException error) { throw StorageError(error); }
    }

    public void Write(string payload)
    {
        // The provisioned volume directory must already exist: creating an unflushed
        // ancestor directory here would weaken the database's durability guarantee.
        if (!Directory.Exists(Path.GetDirectoryName(databasePath)))
            throw new DirectoryNotFoundException("Teams durable storage directory must be provisioned first.");
        var create = !File.Exists(databasePath) && !Directory.Exists(databasePath);
        try
        {
            using var connection = Open(create);
            using var transaction = connection.BeginTransaction();
            using var command = connection.CreateCommand();
            command.Transaction = transaction;
            if (create)
            {
                command.CommandText = """
                    CREATE TABLE snapshot (
                        slot INTEGER PRIMARY KEY CHECK (slot = 1),
                        version INTEGER NOT NULL CHECK (version = 1),
                        kind TEXT NOT NULL,
                        payload TEXT NOT NULL);
                    INSERT INTO snapshot VALUES (1, 1, $kind, $payload);
                    """;
            }
            else
            {
                command.CommandText = "UPDATE snapshot SET payload = $payload WHERE slot = 1 AND version = 1 AND kind = $kind";
            }
            command.Parameters.AddWithValue("$kind", kind);
            command.Parameters.AddWithValue("$payload", payload);
            if (command.ExecuteNonQuery() != 1)
                throw new InvalidDataException("Missing or incompatible Teams durable snapshot.");
            transaction.Commit();
        }
        catch (SqliteException error) { throw StorageError(error); }
    }

    private SqliteConnection Open(bool create)
    {
        var connection = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = databasePath,
            Mode = create ? SqliteOpenMode.ReadWriteCreate : SqliteOpenMode.ReadWrite,
            Pooling = false,
            DefaultTimeout = 2
        }.ToString());
        try
        {
            connection.Open();
            using var command = connection.CreateCommand();
            command.CommandText = "PRAGMA journal_mode=DELETE";
            if (!string.Equals(command.ExecuteScalar() as string, "delete", StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("Teams durable storage journal mode unavailable.");
            // EXTRA syncs the rollback-journal directory after unlink as well as the
            // data itself. FULL alone can lose the last commit in DELETE mode.
            command.CommandText = "PRAGMA synchronous=EXTRA; PRAGMA synchronous";
            if (Convert.ToInt32(command.ExecuteScalar()) != 3)
                throw new InvalidDataException("Teams durable storage synchronization unavailable.");
            return connection;
        }
        catch { connection.Dispose(); throw; }
    }

    private static IOException StorageError(SqliteException error) =>
        new("Teams durable storage is unavailable.", error);
}
