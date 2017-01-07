package osm2inter;

import java.io.File;
import java.io.IOException;
import util.SQLStatementQueue;
import osm.OSMClassification;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import util.Parameter;

/**
 *
 * @author thsc, Sven Petsche
 */
public class SQLImportCommandBuilder implements OSM2InterBuilder {

    public static final String TAGTABLE = "Tags";
    public static final String NODETABLE = "Nodes";
    public static final String WAYTABLE = "Ways";
    public static final String RELATIONTABLE = "Relations";
    public static final String RELATIONMEMBER = "RelationMember";
    public static final String CLASSIFICATIONTABLE = "Classification";
    public static final String WAYMEMBER = "WayNodes";
    public static final String MAX_ID_SIZE = "10485760";

    private boolean n = true;
    private boolean w = true;
    private boolean r = true;

    private long nodesNew = 0;
    private long nodesChanged = 0;
    private long nodesExisting = 0;

    private long waysNew = 0;
    private long waysChanged = 0;
    private long waysExisting = 0;

    private long relNew = 0;
    private long relChanged = 0;
    private long relExisting = 0;

    private int rCount = 10;
    private int wCount = 10;
    
    private String user;
    private String pwd;
    private String serverName;
    private String portNumber;
    private String path;
    private String schema;
    private int maxThreads;
    
    private Connection targetConnection;

    private String importMode = "initial_import";
    private Integer tmpStorageSize = 100;

    private final MyLogger logger;
    private final Classification classification;
    private final Config config;
    
    private final Parameter parameter;
    private File recordFile = null;

    private static SQLImportCommandBuilder instance = null;

    public static SQLImportCommandBuilder getInstance(Parameter parameter) {
        if (instance == null) {
            instance = new SQLImportCommandBuilder(parameter);
        }
        return instance;
    }
    
    private SQLImportCommandBuilder(Parameter parameter) {
        this.parameter = parameter;
        this.config = Config.getInstance();
        this.logger = MyLogger.getInstance();
        this.classification = Classification.getInstance();
    
    try {
        this.user = this.parameter.getUserName();
        this.pwd = this.parameter.getPWD();
        this.serverName = this.parameter.getServerName();
        this.portNumber = this.parameter.getPortNumber();
        this.path = this.parameter.getdbName();
        this.schema = this.parameter.getSchema();
        this.recordFile = new File(this.parameter.getRecordFileName());
        try {
            String v = this.parameter.getMaxThread();
            this.maxThreads = Integer.parseInt(v.trim());
            this.maxThreads = this.maxThreads > 0 ? this.maxThreads : 1;
        }
        catch(NumberFormatException e) {
            System.err.println("no integer value (run single threaded instead): " + this.parameter.getMaxThread());
            this.maxThreads = 1;
        }
      
        Properties connProps = new Properties();
        connProps.put("user", this.user);
        connProps.put("password", this.pwd);
        this.targetConnection = DriverManager.getConnection(
                "jdbc:postgresql://" + this.serverName
                + ":" + this.portNumber + "/" + this.path, connProps);
        if (!this.schema.equalsIgnoreCase("")) {
            StringBuilder sql = new StringBuilder("SET search_path = ");
            sql.append(this.schema);
            try (PreparedStatement stmt = targetConnection.prepareStatement(sql.toString())) {
              stmt.execute();
              logger.print(4, "schema altered");
            } catch (SQLException e) {
              logger.print(4, "failed to alter schema: " + e.getLocalizedMessage());
            }
        }
    } catch (SQLException e) {
        logger.print(0, "cannot connect to database: " + e.getLocalizedMessage());
    }

    if (this.targetConnection == null) {
        System.err.println("cannot connect to database: reason unknown");
    }
    this.sqlQueue = new SQLStatementQueue(this.targetConnection, this.recordFile, this.maxThreads);
    
    this.setupKB();
  }

  private List<String> createTablesList() {
    List<String> l = new ArrayList<>();
    l.add(RELATIONMEMBER); // dependencies to way, node and relations
    l.add(WAYTABLE); // dependencies to node
    l.add(NODETABLE);
    l.add(RELATIONTABLE);
    l.add(CLASSIFICATIONTABLE);
    l.add(WAYMEMBER);
    return l;
  }

  private void dropTables() throws SQLException {
//    if (config.getValue("db_dropTables").equalsIgnoreCase("yes")) {
      StringBuilder sqlDel = new StringBuilder("DROP TABLE ");
      PreparedStatement delStmt = null;
      List<String> tables = this.createTablesList();
      tables.stream().forEach((table) -> {
        sqlDel.append(table).append(", ");
      });
      sqlDel.delete(sqlDel.lastIndexOf(","), sqlDel.length()).replace(sqlDel.length(), sqlDel.length(), ";");
      try {
        delStmt = targetConnection.prepareStatement(sqlDel.toString());
        delStmt.execute();
        logger.print(4, "tables dropped");
      } catch (SQLException e) {
        logger.print(4, "failed to drop table: " + e.getLocalizedMessage());
      } finally {
        if (delStmt != null) {
          delStmt.close();
        }
      }
//    }
  }

  private void setupTable(String table, String sqlCreate) throws SQLException {
    logger.print(4, "creating table if not exists: " + table);
    PreparedStatement stmt = null;
    StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
    sql.append(table).append(sqlCreate);
    try {
      stmt = targetConnection.prepareStatement(sql.toString());
      stmt.execute();
    } catch (SQLException ex) {
      logger.print(1, ex.getLocalizedMessage(), true);
      logger.print(1, sql.toString());
    } finally {
      if (stmt != null) {
        stmt.close();
      }
    }
  }
  
    private boolean isClassName(String key) {
        return OSMClassification.getOSMClassification().osmFeatureClasses.keySet().contains(key);
    }
  
    /**
     * @return -1 if no known class and sub class name, a non-negative number 
     * otherwise
     */
    private int getOHDMClassID(String className, String subClassName) {
        String fullClassName = this.createFullClassName(className, subClassName);
        
        // find entry
        Integer id = this.classIDs.get(fullClassName);
        if(id != null) {
            return id;
        }
        
        // try undefined
        fullClassName = this.createFullClassName(className, OSMClassification.UNDEFINED);
        id = this.classIDs.get(fullClassName);
        if(id != null) {
            return id;
        }
        
//        System.out.println("not found: " + this.createFullClassName(className, subClassName));
        
        // else
        return -1;
    }

  private void setupKB() {
    logger.print(4, "--- setting up tables ---", true);
    try {
      this.dropTables();
      /**
       * ************ Knowledge base tables ****************************
       */
    this.loadClassification();
      
      StringBuilder sqlWay = new StringBuilder();
      sqlWay.append(" (osm_id bigint PRIMARY KEY, ")
              .append("classcode bigint REFERENCES ").append(CLASSIFICATIONTABLE).append(" (classcode), ")
              .append("serializedTags character varying, ")
              .append("ohdm_geom_id bigint, ")
              .append("ohdm_object_id bigint, ")
              .append("node_ids character varying, ")
              .append("is_part boolean DEFAULT false, ")
              .append("valid boolean);");
      this.setupTable(WAYTABLE, sqlWay.toString());

      StringBuilder sqlNode = new StringBuilder();
      sqlNode.append(" (osm_id bigint PRIMARY KEY, ")
              .append("classcode bigint REFERENCES ").append(CLASSIFICATIONTABLE).append(" (classcode), ")
              .append("serializedTags character varying, ")
              .append("longitude character varying(").append(MAX_ID_SIZE).append("), ")
              .append("latitude character varying(").append(MAX_ID_SIZE).append("), ")
              .append("ohdm_geom_id bigint, ")
              .append("ohdm_object_id bigint, ")
//              .append("id_way bigint REFERENCES ").append(WAYTABLE).append(" (osm_id), ")
              .append("is_part boolean DEFAULT false, ")
              .append("valid boolean);");
      this.setupTable(NODETABLE, sqlNode.toString());

      StringBuilder sqlRelation = new StringBuilder();
      sqlRelation.append(" (osm_id bigint PRIMARY KEY, ")
              .append("classcode bigint REFERENCES ").append(CLASSIFICATIONTABLE).append(" (classcode), ")
              .append("serializedTags character varying, ")
              .append("ohdm_geom_id bigint, ")
              .append("ohdm_object_id bigint, ")
              .append("member_ids character varying, ")
              .append("valid boolean);");
      this.setupTable(RELATIONTABLE, sqlRelation.toString());
      
      StringBuilder sqlWayMember = new StringBuilder();
      sqlWayMember.append(" (way_id bigint, ");
      sqlWayMember.append("node_id bigint");
      sqlWayMember.append(");");
      this.setupTable(WAYMEMBER, sqlWayMember.toString());
      
      StringBuilder sqlRelMember = new StringBuilder();
      sqlRelMember.append(" (relation_id bigint REFERENCES ").append(RELATIONTABLE).append(" (osm_id) NOT NULL, ")
//              .append("way_id bigint REFERENCES ").append(WAYTABLE).append(" (osm_id), ")
              .append("way_id bigint, ")
//              .append("node_id bigint REFERENCES ").append(NODETABLE).append(" (osm_id), ")
              .append("node_id bigint, ")
//              .append("member_rel_id bigint REFERENCES ").append(RELATIONTABLE).append(" (osm_id));");
              .append("member_rel_id bigint, ")
              .append("role character varying);");
      this.setupTable(RELATIONMEMBER, sqlRelMember.toString());
      
    } catch (SQLException e) {
      System.err.println("error while setting up tables: " + e.getLocalizedMessage());
    }
    logger.print(4, "--- finished setting up tables ---", true);
  }
  
  private HashMap<String, Integer> classIDs = new HashMap<>();

    private void loadClassification() throws SQLException {      
        String db_classificationTable = config.getValue("db_classificationTable");
        if (db_classificationTable!= null && db_classificationTable.equalsIgnoreCase("useExisting")) {
            Statement stmt = null;
            try {
                stmt = targetConnection.createStatement();
                StringBuilder sb = new StringBuilder("SELECT * FROM ");
                if (!this.schema.equalsIgnoreCase("")) {
                    sb.append(schema).append(".");
                }
                sb.append(CLASSIFICATIONTABLE).append(";");
                try (ResultSet rs = stmt.executeQuery(sb.toString())) {
                    while (rs.next()) {
                        this.classification.put(rs.getString("class"), rs.getString("subclassname"), rs.getInt("classcode"));
                    }
                }
            } catch (SQLException e) {
                logger.print(4, "classification loadingt failed: " + e.getLocalizedMessage(), true);
            } finally {
                if (stmt != null) {
                    try {
                      stmt.close();
                    } catch (SQLException e) {
                    }
                }
            }
        } else {
            StringBuilder sqlRelMember = new StringBuilder();
            sqlRelMember.append("(classcode bigint PRIMARY KEY, ")
              .append("classname character varying, ")
              .append("subclassname character varying);");

            this.setupTable(CLASSIFICATIONTABLE, sqlRelMember.toString());

            // fill that table
            // set up classification table from scratch
            OSMClassification osmClassification = OSMClassification.getOSMClassification();

            // init first line: unknown classification
            StringBuilder insertStatement = new StringBuilder();
            insertStatement.append("INSERT INTO ")
                    .append(CLASSIFICATIONTABLE)
                    .append(" VALUES (-1, 'no_class', 'no_subclass');");

            PreparedStatement stmt = null;
            try {
                stmt = targetConnection.prepareStatement(insertStatement.toString());
                stmt.execute();
            } catch (SQLException ex) {
                logger.print(1, ex.getLocalizedMessage(), true);
                logger.print(1, insertStatement.toString());
            } finally {
                if (stmt != null) {
                    stmt.close();
                }
            }

            // no append real data
            int id = 0;

            // create classification table
            // iterate classes
            Iterator<String> classIter = osmClassification.osmFeatureClasses.keySet().iterator();

            while(classIter.hasNext()) {
                String className = classIter.next();
                List<String> subClasses = osmClassification.osmFeatureClasses.get(className);
                Iterator<String> subClassIter = subClasses.iterator();

                while(subClassIter.hasNext()) {
                    String subClassName = subClassIter.next();

                    // keep in memory
                    Integer idInteger = id;
                    String fullClassName = this.createFullClassName(className, subClassName);

                    this.classIDs.put(fullClassName, idInteger);

                    // add to database
                    insertStatement = new StringBuilder();
                    insertStatement.append("INSERT INTO ")
                            .append(CLASSIFICATIONTABLE)
                            .append(" VALUES (")
                            .append(id++)
                            .append(", '")
                            .append(className)
                            .append("', '")
                            .append(subClassName)
                            .append("');");

                    stmt = null;
                    try {
                        stmt = targetConnection.prepareStatement(insertStatement.toString());
                        stmt.execute();
                    } catch (SQLException ex) {
                        logger.print(1, ex.getLocalizedMessage(), true);
                        logger.print(1, insertStatement.toString());
                    } finally {
                      if (stmt != null) {
                        stmt.close();
                      }
                    }
                }

            }
        }
    }

    private Map<String, NodeElement> nodes = new HashMap<>();
  
    private String createFullClassName(String className, String subclassname) {
        return className + "_" + subclassname;
    }
  
    /**
     * TODO: Add here translation of unused OSM types to OHDM types etc.
     * @param osmElement
     * @return 
     */
    private int getOHDMClassID(OSMElement osmElement) {
        // a node can have tags which can describe geometrie feature classe
        // get attributes of that tag
        Iterator<String> keyIter = osmElement.getAttributes().keySet().iterator();
        while(keyIter.hasNext()) {
            String key = keyIter.next();

            // is this key name of a feature class?
            if(this.isClassName(key)) {
                String value = osmElement.getValue(key);

                // find id of class / subclass
                return this.getOHDMClassID(key, value);
            }
        }
        // there is no class description - sorry
        return -1;
    }
    
    String lastOSMID = "-1";
  
    private void saveNodeElements() throws SQLException, IOException {
//    SQLStatementQueue sqlQueue = new SQLStatementQueue(this.targetConnection, this.logger);
      
    for (Map.Entry<String, NodeElement> entry : nodes.entrySet()) {
        NodeElement node = entry.getValue();
        int classID = OSMClassification.getOSMClassification().getOHDMClassID(node);
        String sTags = node.getSerializedTagsAndAttributes();
        
        sqlQueue.append("INSERT INTO ");
        sqlQueue.append(NODETABLE);
        sqlQueue.append(" (osm_id, longitude, latitude, classcode, serializedtags, valid) VALUES");

        sqlQueue.append(" (");
        sqlQueue.append(entry.getKey());
        sqlQueue.append(", ");
        sqlQueue.append(entry.getValue().getLatitude());
        sqlQueue.append(", ");
        sqlQueue.append(entry.getValue().getLongitude());
        sqlQueue.append(", ");
        sqlQueue.append(classID);
        sqlQueue.append(", '");
        sqlQueue.append(sTags);
        sqlQueue.append("', ");
        sqlQueue.append("true");
        sqlQueue.append("); ");
    }
    
    sqlQueue.forceExecute(lastOSMID);
//    sqlQueue.forceExecute();
      
    nodes.clear();
  }

  private void updateNode(long id, HashMap<String, String> map) {
    StringBuilder sb = new StringBuilder("UPDATE ");
    sb.append(NODETABLE).append(" SET ");
    map.entrySet().stream().forEach((e) -> {
      sb.append(e.getKey()).append(" = ").append(e.getValue()).append(" ");
    });
    sb.append("WHERE osm_id = ?;");
    try (PreparedStatement stmt = targetConnection.prepareStatement(sb.toString())) {
      stmt.setLong(1, id);
      stmt.executeUpdate();
    } catch (SQLException e) {
      logger.print(1, e.getLocalizedMessage(), true);
      logger.print(3, sb.toString());
    }
  }

    private void logLastElement(OSMElement e) {
        this.lastOSMID = e.getOSMID();
    }
    
    private int nextLog = 0;
    private int nextSteps = 10000;
    
    @Override
    public void addNode(HashMap<String, String> attributes, ArrayList<TagElement> tags) throws Exception {
        NodeElement newNode = new NodeElement(attributes, tags);
        nodes.put(String.valueOf(newNode.getID()), newNode);
        if (nodes.size() > this.tmpStorageSize) {
            nodesNew += nodes.size();
            this.saveNodeElements();
            if(this.nextLog < this.nodesNew) {
                this.nextLog += this.nextSteps;
                this.printStatusShort(4);
            }
//            System.gc();
//            Thread.sleep(GC_PAUSE);
        }
        this.logLastElement(newNode);
  }

  private NodeElement selectNodeById(long osm_id) {
    StringBuilder sb = new StringBuilder("SELECT * FROM ");
    sb.append(NODETABLE).append(" WHERE osm_id = ?;");
    HashMap<String, String> attributes = null;
    try (PreparedStatement stmt = targetConnection.prepareStatement(sb.toString())) {
      stmt.setLong(1, osm_id);
      ResultSet rs = stmt.executeQuery();
      if (rs.next()) {
        attributes = new HashMap<>();
        attributes.put("id", String.valueOf(rs.getInt("osm_id")));
        attributes.put("lat", rs.getString("lat"));
        attributes.put("lon", rs.getString("long"));
      }
    } catch (SQLException ex) {
      logger.print(1, ex.getLocalizedMessage(), true);
      logger.print(3, sb.toString());
    }
    if (attributes == null) {
      return null;
    } else {
      return new NodeElement(attributes, null);
    }
  }

  /**
   * checks latitude and longitude of node
   *
   * @param newNode
   * @param dbNode
   * @return 0: node does not exist, 1: node has changed, 2: node exists
   */
  private Integer matchNodes(NodeElement newNode, NodeElement dbNode) {
    Integer state = 1;
    if (dbNode == null) {
      return 0;
    } else {
      if (newNode.getLatitude().equals(dbNode.getLatitude())
              && newNode.getLongitude().equals(dbNode.getLongitude())) {
        state = 2;
      }
    }
    return state;
  }

    private final HashMap<String, WayElement> ways = new HashMap<>();
    private SQLStatementQueue sqlQueue;

    private boolean waysProcessed = false;
    private boolean relationsProcessed = false;

    private void saveWayElements() throws SQLException, IOException {
        // set up a sql queue
//        SQLStatementQueue sqlQueue = new SQLStatementQueue(this.targetConnection, this.logger);
        SQLStatementQueue nodeIsPartSql = new SQLStatementQueue(this.targetConnection, this.logger);
        
          // figure out classification id.. which describes the
          // type of geometry (building, highway, those kind of things
        Iterator<Map.Entry<String, WayElement>> wayElementIter = ways.entrySet().iterator();

        int counter = 0;
        int rows = 0;
        logger.print(4, "saving ways.. set a star after 20 saved ways when flushing; max 50 stars each line");
        while(wayElementIter.hasNext()) {
            // NOTE FLUSH on sql queue is essentiell!! rest of the code is for debugging
            if(counter++ >= 50) {
                // IMPORTANT:
                sqlQueue.forceExecute();
                
                // debugging
                System.out.print("*");
                counter = 0;
                System.out.flush();
                
                if(rows++ >= 50) {
                    System.out.print("\n");
                    rows = 0;
                }
            }

            sqlQueue.append("INSERT INTO ");
            sqlQueue.append(WAYTABLE);
            sqlQueue.append("(osm_id, classcode, serializedtags, node_ids, valid) VALUES");
            
            /*
            StringBuilder sb = new StringBuilder("INSERT INTO ");
            sb.append(WAYTABLE).append("(osm_id, classcode, valid) VALUES");
            */

            Map.Entry<String, WayElement> wayElementEntry = wayElementIter.next();

            WayElement wayElement = wayElementEntry.getValue();

            // figure out geometry class (highway, building or such a thing
            int wayID = OSMClassification.getOSMClassification().getOHDMClassID(wayElement);

            String wayOSMID = wayElementEntry.getKey();

            String sTags = wayElement.getSerializedTagsAndAttributes();
            
            // serialize node ids
            StringBuilder nodeString = new StringBuilder();
            if(wayElement.getNodes() != null) {
                Iterator<NodeElement> wayNodeIter = wayElement.getNodes().iterator();
                boolean first = true;
                while(wayNodeIter.hasNext()) {
                    NodeElement wayNode = wayNodeIter.next();
                    
                    if(first) {
                        first = false;
                    } else {
                        nodeString.append(",");
                    }

                    nodeString.append(wayNode.getID());
                }
            }
            
            // lets add values to sql statement

            sqlQueue.append(" (");
            sqlQueue.append(wayOSMID);
            sqlQueue.append(", "); // osm_id
            sqlQueue.append(wayID);
            sqlQueue.append(", '");
            sqlQueue.append(sTags);
            sqlQueue.append("', '");
            sqlQueue.append(nodeString.toString());
            sqlQueue.append("', ");
            sqlQueue.append("true");
            sqlQueue.append(");"); // it's a valid way... it's still in OSM
            
            // add related nodes to way_node table and mark node to be part of something
            
            // iterate nodes
            if(wayElement.getNodes() != null) {
                // set up first part of sql statement
                String sqlStart = "INSERT INTO " + WAYMEMBER 
                        + "(way_id, node_id) VALUES ( " + wayOSMID + ", ";
                
                nodeIsPartSql.append("UPDATE nodes SET is_part=true WHERE ");
                Iterator<NodeElement> wayNodeIter = wayElement.getNodes().iterator();
                boolean first = true;
                while(wayNodeIter.hasNext()) {
                    NodeElement wayNode = wayNodeIter.next();

                    long nodeOSMID = wayNode.getID();

                    // set up sql statement - use queue for performace reasons
                    sqlQueue.append(sqlStart);
                    sqlQueue.append(nodeOSMID); // add node ID
                    sqlQueue.append(");"); // finish statement
                    
                    // update
                    if(!first) {
                        nodeIsPartSql.append(" OR ");
                    } else {
                        first = false;
                    }
                    nodeIsPartSql.append("osm_id=");
                    nodeIsPartSql.append(nodeOSMID); // add node ID
                }
                nodeIsPartSql.append("; ");
//                // flush remaining sql statements
//                sqlQueue.flush();
            }
        }
        
//        nodeIsPartSql.forceExecute();
        nodeIsPartSql.forceExecute("is part of statement");
        // flush sql statements (required when using append variant)
        
        // already some way processed?
        if(!this.waysProcessed) {
            this.waysProcessed = true;
            sqlQueue.flushThreads();
        }
//        sqlQueue.forceExecute();
//        sqlQueue.forceExecute("ways");
        sqlQueue.forceExecute(lastOSMID);
        
        this.ways.clear();
    }

  private void saveWayElement(WayElement way) {
      int classID = OSMClassification.getOSMClassification().getOHDMClassID(way);
    StringBuilder sb = new StringBuilder("INSERT INTO ");
    if (classID < 0) {
      sb.append(WAYTABLE).append(" (osm_id, valid) VALUES (?, true);");
    } else {
      sb.append(WAYTABLE).append(" (osm_id, classcode, valid) VALUES (?, ?, true);");
    }
    PreparedStatement stmt = null;
    try {
      stmt = targetConnection.prepareStatement(sb.toString());
      stmt.setLong(1, way.getID());
      if (classID > -1) {
        stmt.setInt(2, classID);
      }
      stmt.execute();
    } catch (SQLException e) {
      logger.print(1, e.getLocalizedMessage(), true);
    } finally {
      try {
        if (stmt != null) {
          stmt.close();
        }
      } catch (SQLException e) {
      }
    }
  }

    /**
     * http://wiki.openstreetmap.org/wiki/Way
     *
     * @param attributes
     * @param nds
     * @param tags
     * @throws java.lang.Exception
     */
    @Override
    public void addWay(HashMap<String, String> attributes, ArrayList<NodeElement> nds, ArrayList<TagElement> tags) throws Exception {
        if (nds == null || nds.isEmpty()) {
            return; // a way without nodes makes no sense.
        }
        if (!nodes.isEmpty()) {
            nodesNew += nodes.size();
            this.saveNodeElements();
            printStatusShort(1);
            this.sqlQueue.append("create index node_osmidindex on ");
            this.sqlQueue.append(NODETABLE);
            this.sqlQueue.append(" (osm_id);");
            this.sqlQueue.forceExecute(true);
            this.nextLog = 0;
            logger.print(1, "finished saving nodes, continue with ways", true);
        }
        WayElement newWay = new WayElement(attributes, nds, tags);
        this.waysNew++;
        this.ways.put(String.valueOf(newWay.getID()), newWay);
        if (((ways.size() * 10) + 1) % tmpStorageSize == 0) {
            this.saveWayElements();
//            System.gc();
//            Thread.sleep(GC_PAUSE);
            if(this.nextLog < this.waysNew) {
                this.nextLog += this.nextSteps;
                this.printStatusShort(4);
            }
        }
        
        this.logLastElement(newWay);
    }
    
    private static final int GC_PAUSE = 100;

    private final HashMap<String, RelationElement> rels = new HashMap<>();

    private void saveRelElements() throws SQLException, IOException {
//        SQLStatementQueue sqlQueue = new SQLStatementQueue(this.targetConnection, this.logger);
        
        for (Map.Entry<String, RelationElement> entry : rels.entrySet()) {
            RelationElement relationElement = entry.getValue();
            
            String osm_id = entry.getKey();
            int classID = OSMClassification.getOSMClassification().getOHDMClassID(relationElement);
            String sTags = relationElement.getSerializedTagsAndAttributes();
    
            String memberIDs = "";
            
            if(relationElement.getMember() != null) {
                // create member_ids
                StringBuilder memberIDsb = new StringBuilder();

                boolean first = true;
                for (MemberElement member : entry.getValue().getMember()) {
                    if(first) {
                        first = false;
                    } else {
                        memberIDsb.append(",");
                    }

                    memberIDsb.append(member.getID());
                }
                
                memberIDs = memberIDsb.toString();
            }

            sqlQueue.append("INSERT INTO ");
            sqlQueue.append(RELATIONTABLE);
            sqlQueue.append(" (osm_id, classcode, serializedtags, member_ids, valid) VALUES (");
            sqlQueue.append(osm_id);
            sqlQueue.append(", ");
            sqlQueue.append(classID);
            sqlQueue.append(", '");
            sqlQueue.append(sTags);
            sqlQueue.append("', '");
            sqlQueue.append(memberIDs);
            sqlQueue.append("', true);");
            
//            sq.flush();

        // add entry to member table and set flag in nodes or way tables
        ArrayList<Long> nodeMemberIDs = new ArrayList<>();
        ArrayList<Long> wayMemberIDs = new ArrayList<>();
            
        for (MemberElement member : entry.getValue().getMember()) {
            sqlQueue.append("INSERT INTO ");
            sqlQueue.append(RELATIONMEMBER);
            sqlQueue.append(" (relation_id, role, ");

            switch (member.getType()) {
            case "node":
                sqlQueue.append(" node_id)");  
                nodeMemberIDs.add(member.getID());
                break;
            case "way":
                sqlQueue.append(" way_id)"); 
                wayMemberIDs.add(member.getID());
                break;
            case "relation":
                sqlQueue.append(" member_rel_id)"); break;
            default:
                logger.print(3, "member with incorrect type"); break;
            }

            // add values
            sqlQueue.append(" VALUES (");
            sqlQueue.append(osm_id);
            sqlQueue.append(", '");
            sqlQueue.append(member.getRole());
            sqlQueue.append("', ");
            sqlQueue.append(member.getId());
            sqlQueue.append(");");

            // sq.flush();

            // member.getId();
          }
//            sq.forceExecute(); // after each relation

            // update nodes and ways
            if(nodeMemberIDs.size() > 0) {
                sqlQueue.append("UPDATE nodes SET is_part=true WHERE ");
                boolean first = true;
                for (Long id : nodeMemberIDs) {
                    if(!first) {
                        sqlQueue.append(" OR ");
                    } else {
                        first = false;
                    }
                    sqlQueue.append("osm_id = ");
                    sqlQueue.append(id);
                }
                sqlQueue.append("; ");
            }
            
            if(wayMemberIDs.size() > 0) {
                sqlQueue.append("UPDATE ways SET is_part=true WHERE ");
                boolean first = true;
                for (Long id : wayMemberIDs) {
                    if(!first) {
                        sqlQueue.append(" OR ");
                    } else {
                        first = false;
                    }
                    sqlQueue.append("osm_id = ");
                    sqlQueue.append(id);
                }
                sqlQueue.append("; ");
            }
//            sqlQueue.forceExecute();
            
            if(!this.relationsProcessed) {
                this.relationsProcessed = true;
                sqlQueue.flushThreads();
            }
            
            sqlQueue.forceExecute(lastOSMID);
        }
    }

  /**
   * OSM Relations are defined here: http://wiki.openstreetmap.org/wiki/Relation
   *
   * @param attributes
   * @param members
   * @param tags
   */
    @Override
    public void addRelation(HashMap<String, String> attributes, 
          ArrayList<MemberElement> members, ArrayList<TagElement> tags) 
            throws Exception {
      
    if (members == null || members.isEmpty() || tags == null) {
        return; // empty relations makes no sense;
    }
    
    if (!ways.isEmpty()) {
        waysNew += ways.size();
        saveWayElements();
        this.printStatusShort(1);
        // create index on ways
        this.sqlQueue.append("create index way_osmidindex on ");
        this.sqlQueue.append(WAYTABLE);
        this.sqlQueue.append(" (osm_id);");
        this.sqlQueue.forceExecute(true);
        logger.print(1, "finished saving ways, proceed with relations", true);
    }
    RelationElement newRel = new RelationElement(attributes, members, tags);
    
    this.relNew++;
    this.rels.put(String.valueOf(newRel.getID()), newRel);
    if (((rels.size() * 100) + 1) % tmpStorageSize == 0) {
        this.saveRelElements();
        this.printStatusShort(4);
//        System.gc();
//        Thread.sleep(GC_PAUSE);
    }
    
    this.logLastElement(newRel);
  }

    @Override
    public void flush() throws Exception {
        if (!nodes.isEmpty()) {
            this.saveNodeElements();
        }
        if (!ways.isEmpty()) {
            this.saveWayElements();
        }
        if (!rels.isEmpty()) {
            this.saveRelElements();
        }
    }

    @Override
    public void printStatus() {
        logger.print(0, "\n\t\t|---------------|---------------|---------------|");
        logger.print(0, "\t\t| new\t\t| changed\t| existing\t|");
        logger.print(0, "|---------------|---------------|---------------|---------------|");
        logger.print(0, "| Nodes\t\t| " + this.nodesNew + "\t\t| " + this.nodesChanged + "\t\t| " + this.nodesExisting + "\t\t|");
        logger.print(0, "|---------------|---------------|---------------|---------------|");
        logger.print(0, "| Ways\t\t| " + this.waysNew + "\t\t| " + this.waysChanged + "\t\t| " + this.waysExisting + "\t\t|");
        logger.print(0, "|---------------|---------------|---------------|---------------|");
        logger.print(0, "| Relations\t| " + this.relNew + "\t\t| " + this.relChanged + "\t\t| " + this.relExisting + "\t\t|");
        logger.print(0, "|---------------|---------------|---------------|---------------|");
    }

  public void printStatusShort(int level) {
    System.out.println("n: " + nodesNew + " w: " + waysNew);
  }

  public Connection getConnection() {
    return this.targetConnection;
  }

  ////////////////////////////////////////////////////////////////////
  //                       counter                                  //
  ////////////////////////////////////////////////////////////////////
  public void incrementNodeCounter(String type) {
    switch (type) {
      case "new":
        this.nodesNew++;
        break;
      case "changed":
        this.nodesChanged++;
        break;
      case "existing":
        this.nodesExisting++;
        break;
      default:
        break;
    }
  }

  ////////////////////////////////////////////////////////////////////
  //                       print for debug                          //
  ////////////////////////////////////////////////////////////////////
  private void printAttributes(HashMap<String, String> a) {
    System.out.println("\nAttributes");
    if (a == null) {
      System.out.println("Empty");
      return;
    }

    Iterator<String> kIter = a.keySet().iterator();
    while (kIter.hasNext()) {
      String k = kIter.next();
      String v = a.get(k);
      System.out.print("k|v: " + k + "|" + v + "\n");
    }
  }

  private void printTags(HashSet<TagElement> tags) {
    System.out.println("\nTags");
    if (tags == null) {
      System.out.println("Empty");
      return;
    }
    Iterator<TagElement> iterator = tags.iterator();
    while (iterator.hasNext()) {
      TagElement tag = iterator.next();
      tag.print();
    }
  }

  private void printNodes(HashSet<NDElement> nds) {
    System.out.println("\nNodes");
    if (nds == null) {
      System.out.println("Empty");
      return;
    }
    Iterator<NDElement> iterator = nds.iterator();
    while (iterator.hasNext()) {
      System.out.print("node:\n");
      NDElement node = iterator.next();
      node.print();
    }
  }

  private void printMembers(HashSet<MemberElement> members) {
    System.out.println("\nMember");
    if (members == null) {
      System.out.println("Empty");
      return;
    }
    Iterator<MemberElement> iterator = members.iterator();
    while (iterator.hasNext()) {
      System.out.print("member:\n");
      MemberElement member = iterator.next();
      member.print();
    }
  }
}
