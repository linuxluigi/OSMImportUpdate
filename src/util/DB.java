package util;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Properties;

/**
 *
 * @author thsc
 */
public class DB {
    
    
    ////////////////////////////////////////////////////////////////////////
    //                          CREATE STRUCTURES                         //
    ////////////////////////////////////////////////////////////////////////

    // ids are defined identically in each table
    static public String getCreateTableBegin(String schema, String tableName) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("CREATE TABLE ");
        sb.append(DB.getFullTableName(schema, tableName));
        sb.append(" (");
        sb.append(DB.getCreatePrimaryKeyDescription(schema, tableName));
        
        return sb.toString();
    }
    
    // primary key are created identically
    static public String getCreatePrimaryKeyDescription(String schema, String tableName) {
        return "id bigint NOT NULL DEFAULT nextval('"
                + DB.getSequenceName(DB.getFullTableName(schema, tableName))
                + "'::regclass),"
                + " CONSTRAINT "
                + tableName
                + "_pkey PRIMARY KEY (id)";
    }
    
    static public void createSequence(Connection targetConnection, String schema, String tableName) throws SQLException {
        DB.createSequence(new SQLStatementQueue(targetConnection), schema, tableName);
    }
    
    static public void createSequence(SQLStatementQueue targetQueue, String schema, String tableName) throws SQLException {
        
        targetQueue.append("CREATE SEQUENCE "); 
        targetQueue.append(DB.getSequenceName(DB.getFullTableName(schema, tableName)));
        targetQueue.append(" INCREMENT 1 MINVALUE 1 MAXVALUE 9223372036854775807 START 1 CACHE 1;");
        targetQueue.forceExecute();
    }
    
    static public void drop(Connection targetConnection, String schema, String tableName) throws SQLException {
        DB.drop(new SQLStatementQueue(targetConnection), schema, tableName);
    }
    
    /**
     * Drop table - at least try.. SQL Exceptions are just reported to stderr
     * @param sq
     * @param schema
     * @param tableName 
     */
    static public void drop(SQLStatementQueue sq, String schema, String tableName) {
        String fullTableName = DB.getFullTableName(schema, tableName);
        try {
            sq.append("DROP SEQUENCE ");
            sq.append(DB.getSequenceName(fullTableName));
            sq.append(" CASCADE;");
            sq.forceExecute();
        }
        catch(SQLException e) {
            System.err.println("cannot drop sequence (prob. non-fatal): " + sq.toString());
        }
        try {
            sq.append("DROP TABLE ");
            sq.append(fullTableName);
            sq.append(" CASCADE;");
            sq.forceExecute();
        }
        catch(SQLException e) {
            System.err.println("cannot drop table (prob. non-fatal): " + sq.toString());
        }
    }
    
    ////////////////////////////////////////////////////////////////////////
    //                                names                               //
    ////////////////////////////////////////////////////////////////////////
    
    static public String getSequenceName(String tableName) {
        return tableName + "_id ";
    }
    
    static public String getFullTableName(String schema, String tableName) {
        return schema + "." + tableName;
    }
    
    ////////////////////////////////////////////////////////////////////////
    //                                helper                               //
    ////////////////////////////////////////////////////////////////////////
    
    public static Connection createConnection(Parameter parameter) throws SQLException{
        Properties connProps = new Properties();
        connProps.put("user", parameter.getUserName());
        connProps.put("password", parameter.getPWD());
        
        Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://" + parameter.getServerName()
                + ":" + parameter.getPortNumber() + "/" + parameter.getdbName(), connProps);
        
        if (parameter.getSchema() != null && !parameter.getSchema().equalsIgnoreCase("")) {
            StringBuilder sql = new StringBuilder("SET search_path = ");
            sql.append(parameter.getSchema());
            try (PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
              stmt.execute();
            } catch (SQLException e) {
            }
        }
        
        return connection;
    }
    
    public static SQLStatementQueue createSQLStatementQueue(Connection target, Parameter parameter) {
        File recordFile = new File(parameter.getRecordFileName());
        int maxThreads = 1;
        try {
            String v = parameter.getMaxThread();
            maxThreads = Integer.parseInt(v.trim());
            maxThreads = maxThreads > 0 ? maxThreads : 1;
        }
        catch(NumberFormatException e) {
            System.err.println("no integer value (run single threaded instead): " + parameter.getMaxThread());
            maxThreads = 1;
        }
        
        return new SQLStatementQueue(target, recordFile, maxThreads);
    }
}