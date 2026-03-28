package com.example.report.export;

import com.example.report.query.SqlBuilder;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public class CsvStreamingExporter {

    private static final String SEPARATOR = ";";
    private static final byte[] UTF8_BOM = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    public static void export(NamedParameterJdbcTemplate jdbc,
                               SqlBuilder.BuiltQuery query,
                               List<String> columns,
                               OutputStream out) {
        try {
            out.write(UTF8_BOM);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));

            writer.println(String.join(SEPARATOR, columns));

            jdbc.query(query.sql(), query.params(), rs -> {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < columns.size(); i++) {
                    if (i > 0) {
                        line.append(SEPARATOR);
                    }
                    Object val = rs.getObject(columns.get(i));
                    if (val != null) {
                        String str = val.toString();
                        if (str.contains(SEPARATOR) || str.contains("\"") || str.contains("\n")) {
                            str = "\"" + str.replace("\"", "\"\"") + "\"";
                        }
                        line.append(str);
                    }
                }
                writer.println(line);
            });

            writer.flush();
        } catch (Exception e) {
            throw new RuntimeException("CSV export failed", e);
        }
    }
}
