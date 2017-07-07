package util;


import inter2ohdm.OHDMImporter;
import java.io.IOException;
import java.io.PrintStream;
import java.sql.SQLException;
import java.util.HashMap;

import inter2ohdm.OHDMUpdateInter;
import inter2ohdm.OSMChunkExtractor;
import inter2ohdm.OSMChunkExtractorProcessFactory;
import ohdm2rendering.OHDM2Rendering;
import osm2inter.OSMImport;
import osm2inter.OSMUpdateInter;

/**
 *
 * @author thsc
 */
public class OSM2Rendering {
    public static final String CHUNK_FACTORY = "-chunkimport";
    public static final String CHUNK_PROCESS = "-chunkprocess";

    public static void main(String[] args) throws IOException, SQLException {

        System.out.println("try to figure out what to import...");
        if(args.length < 4) {
            /* at least two parameter are required which are 
            defined with at least four arguments
            */
            OSM2Rendering.printUsageAndExit("at least two parameters are required");
        }
        
        String osmFile = null;
        String importInterDBConfig = null;
        String updateInterDBConfig = null;
        String ohdmDBConfig = null;
        String renderingDBConfig = null;

        boolean chunkFactory = false;
        boolean chunkProcess = false;

        // now get real parameters
        HashMap<String, String> argumentMap = Util.parametersToMap(args,
                false, "at least two parameters are required");

        if(argumentMap != null) {
            // got some - overwrite defaults
            String value = argumentMap.get("-o");
            if (value != null) {
                osmFile = value;
            }

            value = argumentMap.get("-i");
            if (value != null) {
                importInterDBConfig = value;
            }

            value = argumentMap.get("-u");
            if (value != null) {
                updateInterDBConfig = value;
            }

            value = argumentMap.get("-d");
            if (value != null) {
                ohdmDBConfig = value;
            }

            value = argumentMap.get("-r");
            if (value != null) {
                renderingDBConfig = value;
            }

            chunkFactory = argumentMap.containsKey(CHUNK_FACTORY);
            chunkProcess = argumentMap.containsKey(CHUNK_PROCESS);
        }

        // launch chunk factory
        if(chunkFactory) {
            OSMChunkExtractorProcessFactory.main(args);
            System.exit(1);
        }

        // launch chunk process
        if(chunkProcess) {
            OSMChunkExtractor.main(args);
            System.exit(1);
        }

        // check consistency

        // decide wether import or exit
        if(updateInterDBConfig != null && (importInterDBConfig == null || osmFile == null)) {
            OSM2Rendering.printUsageAndExit("update requires osm file and intermediate database");
        }

        // unclear what to do: import / update into intermediate db or import / update ohdm from intermediate db
        if( (importInterDBConfig != null || updateInterDBConfig != null) && osmFile == null && ohdmDBConfig == null)  {
            OSM2Rendering.printUsageAndExit("unclear what to do: import / update into intermediate db or import / update ohdm from intermediate db");
        }
        
        // osm file but neither import nor update declared
        if( importInterDBConfig == null && updateInterDBConfig == null && osmFile != null)  {
            OSM2Rendering.printUsageAndExit("osm file declared but no import configuration");
        }
        
        // unclear what to do: import into ohdm or create rendering database out of ohdm
        if( ohdmDBConfig != null && importInterDBConfig == null && updateInterDBConfig == null && renderingDBConfig == null)  {
            OSM2Rendering.printUsageAndExit("unclear what to do: import into ohdm or create rendering database out of ohdm");
        }
        
        // producing rendering requires definition of ohdm database
        if( renderingDBConfig != null && ohdmDBConfig == null)  {
            OSM2Rendering.printUsageAndExit("producing rendering requires definition of ohdm database");
        }
        
        // debug
        System.err.println("osmFile: " + osmFile);
        System.err.println("importInterDBConfig: " + importInterDBConfig);
        System.err.println("updateInterDBConfig: " + updateInterDBConfig);
        System.err.println("ohdmDBConfig: " + ohdmDBConfig);
        System.err.println("renderingDBConfig: " + renderingDBConfig);
        
        OSM2Rendering.printMessage("start processes:");
        if(importInterDBConfig != null && osmFile != null && updateInterDBConfig == null) {
            OSM2Rendering.printMessage("import from osm file into intermediate db");
        }
        
        if(updateInterDBConfig != null && importInterDBConfig != null && osmFile != null) {
            OSM2Rendering.printMessage("update intermediate db from osm file");
        }

        if(importInterDBConfig != null && ohdmDBConfig != null && updateInterDBConfig == null) {
            OSM2Rendering.printMessage("import from intermediate db into ohdm db");
        }
        
        if(updateInterDBConfig != null && (importInterDBConfig != null && ohdmDBConfig != null)) {
            OSM2Rendering.printMessage("update ohdm db from intermediate db");
        }
        
        if(renderingDBConfig != null && ohdmDBConfig != null) {
            OSM2Rendering.printMessage("produce rendering db");
        }
        
        if(osmFile != null && importInterDBConfig != null && updateInterDBConfig == null) {
            OSMImport.main(new String[]{osmFile, importInterDBConfig});
        }

        if(importInterDBConfig != null && ohdmDBConfig != null && updateInterDBConfig == null) {
            OHDMImporter.main(new String[]{importInterDBConfig, ohdmDBConfig});
        }

        if(updateInterDBConfig != null && importInterDBConfig != null && osmFile != null) {
            // make an import to temporary intermediate data base
            OSM2Rendering.printMessage("import new osm data into temporary intermediate db");
            OHDMImporter.main(new String[]{updateInterDBConfig, ohdmDBConfig, "update"});

            // merge temporary and permanent intermediate data base
            OSM2Rendering.printMessage("merge temporary intermediate db into intermediate db");
            OSMUpdateInter.main(new String[]{importInterDBConfig, updateInterDBConfig});
        }

        if(updateInterDBConfig != null && importInterDBConfig != null && ohdmDBConfig != null) {
            // merge changes into ohdm database
            OSM2Rendering.printMessage("update ohdm with new osm data");
            OHDMUpdateInter.main(new String[]{importInterDBConfig, ohdmDBConfig});
        }

        if(ohdmDBConfig != null &&  renderingDBConfig != null) {
            OHDM2Rendering.main(new String[]{ohdmDBConfig, renderingDBConfig});
        }
    }

    private static void printUsageAndExit(String message) {
        OSM2Rendering.printUsageAndExit(message, System.err);
    }
    
    private static void printMessage(String message) {
        OSM2Rendering.printMessage(message, System.err);
    }
    
    private static void printMessage(String message, PrintStream out) {
        out.println(message);
    }
    
    private static void printUsageAndExit(String message, PrintStream out) {
        out.println("Failure:");
        out.println(message);
        out.println("\nUsage:");
        OSM2Rendering.printUsageAndExit(out);
    }
    
    private static void printUsageAndExit() {
        OSM2Rendering.printUsageAndExit(System.out);
    }
    
    private static void printUsageAndExit(PrintStream out) {
        out.println("-o [osmfilename]");
        out.println("-i [parameter file intermediateDB import]");
        out.println("-u [parameter file intermediateDB update]");
        out.println("-d [parameter file OHDM DB]");
        out.println("-r [parameter file rendering DB]");
        out.println("Note 1: -i and -u exclude each other: It's either an import or an update, never both");
        out.println("Note 2: The process comprises up to three steps");
        out.println("1) Import or update OSM file to intermediate DB (requires -o and (-i or -u) )");
        out.println("2) Import/Update OHDM from intermediate DB (requires (-i or -u) and -d)");
        out.println("3) Create rendering tables from OHDM from intermediate DB (requires -d und -r)");
        out.println("Each step is performed if all required parameters are found");
        out.println("Enter e.g. all parameter to create rendering tables from an OSM file");
        out.println("Enter only e.g. -o, -u and -d to update OHDM with a new .osm file");
        out.println("Enter only e.g. -d and -r to produce new rendering table from OHDM");
        System.exit(0);
    }
}
