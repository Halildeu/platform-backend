using Microsoft.Data.Sqlite;

namespace TeamsCapture.Worker.Tests;

internal static class SnapshotTestStorage
{
    public static SqliteConnection Open(string configuredPath)
    {
        var connection = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = configuredPath + ".sqlite3", Mode = SqliteOpenMode.ReadWrite, Pooling = false
        }.ToString());
        connection.Open();
        return connection;
    }

    public static void BlockWrites(string path)
    {
        using var connection = Open(path);
        using var command = connection.CreateCommand();
        command.CommandText = "CREATE TRIGGER reject_write BEFORE UPDATE ON snapshot BEGIN SELECT RAISE(ABORT, 'synthetic write failure'); END";
        command.ExecuteNonQuery();
    }

    public static void AllowWrites(string path)
    {
        using var connection = Open(path);
        using var command = connection.CreateCommand();
        command.CommandText = "DROP TRIGGER reject_write";
        command.ExecuteNonQuery();
    }

    public static string Read(string path)
    {
        using var connection = Open(path);
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT payload FROM snapshot WHERE slot=1";
        return (string)command.ExecuteScalar()!;
    }
}
