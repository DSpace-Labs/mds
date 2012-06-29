/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.administer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.BeanMapper;
import org.skife.jdbi.v2.util.StringMapper;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Argument;

import static com.google.common.base.Preconditions.*;
import com.google.common.base.Strings;
import com.google.common.io.Files;

import org.dspace.browse.BrowseException;
import org.dspace.browse.IndexBrowse;
import org.dspace.core.ConfigurationManager;
import org.dspace.core.Context;
import org.dspace.core.I18nUtil;
import org.dspace.eperson.Group;
import org.dspace.search.DSIndexer;
import org.dspace.storage.rdbms.DatabaseManager;

/**
 * Contains methods for installing/updating mds modules from built code
 * to a configured location. main() method is a command-line tool invoking same.
 * 
 * The Installer requires a very specific configuration to operate. There
 * is a single directory (the 'base') where the kernel module is present.
 * It contains a file declaring the maven coordinates of any dependent jars.
 * It is a simple text file ('deps.txt') in the format emitted by
 * the maven dependency plugin. It also must contain the maven
 * POM ('pom.xml') which is interrogated for module data.
 * 
 * It also can contain the subdirectories:
 * 
 *   bin
 *   conf
 *   db
 *   lib
 *   reg
 *   modules
 *   
 * The modules directory will contain any modules that are to be installed on
 * the kernel, and they can be added at any time after the kernel has been
 * installed. They can have arbitrary directory names and have the some
 * sub-structure as the kernel, except they will lack the modules subdirectory.
 * The directory name is used as the module name to install, update etc.
 * 
 * @author richardrodgers
 */
public final class Installer
{
	// expected directory for executables
	private static final String BIN_DIR = "bin";
	// expected directory for configuration
	private static final String CONF_DIR = "conf";
	// expected directory for DDL code
	private static final String DDL_DIR = "db";
	// expected name for DDL 'up' definition
	private static final String DDL_UPFILE = "database_schema.sql";
	// expected name for DDL 'down' definition
	private static final String DDL_DOWNFILE = "clean-database.sql";
	// expected directory for jars
	private static final String LIB_DIR = "lib";
	// expected directory for module source
	private static final String SRC_DIR = "src";
	// directory where modules reside
	private static final String MODULES_DIR = "modules";
	// expected directory for registry files
	private static final String REG_DIR = "reg";
	// expected name of module dependents list
	private static final String DEPS_FILE = "deps.txt";
	// maven pom file
	private static final String POM_FILE = "pom.xml";
	// maven build dir
	private static final String BUILD_DIR = "target";
	// list of content locations to exclude from installation
	private static final String[] exclusions = { DEPS_FILE, POM_FILE, DDL_DIR, SRC_DIR, BUILD_DIR, LIB_DIR, REG_DIR }; 
			
	private DSIndexer indexer = null;
	
	enum Action {install, update, cleandb}
	
	@Argument(index=0, usage="action to take", required=true)
	private Action action;
		
	@Argument(index=1, usage="module name", required=true)
	private String module;
	
	// source/staging filesystem location
	private File baseDir;
	
	// maven coordinates of module being worked on
	private String groupId;
	private String artifactId;
	private String version;
	private String packaging;
	
    /**
     * For invoking via the command line.
     * 
     * @param args
     *            command-line arguments
     */
    public static void main(String[] args) throws Exception
    {
    	Installer installer = new Installer();
    	CmdLineParser parser = new CmdLineParser(installer);
        try {
        	parser.parseArgument(args);
        	installer.checkEnv();
        	installer.process();
        }  catch (CmdLineException clE) {
        	System.err.println(clE.getMessage());
        	parser.printUsage(System.err);
        } catch (Exception e) {
        	System.err.println(e.getMessage());
        }
    }
        
    public void checkEnv() throws Exception {
    	// check JRE version
    	checkState(System.getProperty("java.version").charAt(2) >= 6,
    		       "Java runtime below minimum required version: 1.6");
    	// make sure we are executing where we ought to be
    	baseDir = new File(System.getProperty("user.dir")).getParentFile();
    	checkState(new File(baseDir, "lib").isDirectory(),
    			  "Installer must be run from kernel 'bin' directory");
    	// and a pom is present
    	checkState(new File(baseDir, POM_FILE).exists(),
    		       "Module must possess a maven pom file");
    }
    
    public void process() throws BrowseException, IOException, SQLException, Exception {
    	// reset base if not kernel
    	if (! "kernel".equals(module)) {
    		baseDir = new File(baseDir, MODULES_DIR + File.separator + module);
    	}
    	// read module POM so we know what we are dealing with
    	readPOM();
    	// make sure module obeys the naming convention
    	checkState(artifactId.startsWith("dsm"),
    			   "Cannot install module: " + module + " improperly named");
    	Handle h = new DBI(DatabaseManager.getDataSource()).open();
    	try {
    		if (action.equals(Action.install)) {
    			install(h);
    		} else if (action.equals(Action.update)) {
    			update(h);
    		} else if (action.equals(Action.cleandb)) {
    			cleanDB(h);
    		}
    	} finally {
    		if (h != null) {
    			h.close();
    		}
    	}
    }
    
    private void install(Handle h) throws BrowseException, IOException, SQLException, Exception {

    	checkState("jar".equals(packaging) || "war".equals(packaging),
    			   "Cannot install module: " + module + " not a valid dspace module");
    	   	
    	// kernel is a special case as first module - initialize DB if not already done
    	boolean dbReady = dbInitialized(h);
    	if (! dbReady) {
    		if ("kernel".equals(module)) {
    			initDB(h);
    		} else {
    			throw new IOException("Module 'kernel' must be installed first");
    		}
    	}
    	
    	// Determine whether this module has already been installed
    	checkState(getComponent(h, groupId, artifactId) == null, "Module: '" + artifactId + "' already installed");
    	System.out.println("Start dependency check");	
    	
    	// Determine whether the installation would create any classpath conflicts
    	List<List<String>> components = readDependencies();
    	for (List<String> cparts : components) {
    		// determine if this component needs to be checked
    		String grpId = cparts.get(0);
    		String artId = cparts.get(1);
    		String vsn = cparts.get(3);
    		String scope = cparts.get(4);
    		if ("compile".equals(scope) || artId.startsWith("dsm-")) {
    			// Query the deployed system for this component
    			Component depComp = getComponent(h, grpId, artId);
    			if (depComp != null) {
    				// do versions conflict?
    				if (! depComp.getVersionStr().equals(vsn)) {
    					throw new IOException("Module dependency: '" + artId + "' conflicts with an existing component");
    				} else {
    					// note that we can forgo installing this jar - it's already there
    					cparts.add("count");
    				}
    			} else {
    				if (artId.startsWith("dsm-")) {
    					throw new IOException("Module dependency: '" + artId + "' must be installed first");
    				} else {
    					cparts.add("install");
    				}
    			}
    		} else {
    			cparts.add("ignore");
    		}
    	}
    	System.out.println("Finished dependency check");			
    	String destPath = ConfigurationManager.getProperty("dspace.dir");
    	File destFile = new File(destPath);
    	if ("kernel".equals(module)) {
    		// create destination directory if it doesn't exist
    		if (! destFile.isDirectory()) {
    			if (! destFile.exists()) {
    				destFile.mkdirs();
    			}
    		}
    	}
    	
    	// first install the module jar itself - this is a special case,
    	// since we check for locally modified version if available
		File libDestDir = new File(destFile, LIB_DIR);
		libDestDir.mkdir();
		File modJar = getModuleArtifact();
    	if ("jar".equals(packaging)) {
    		safeCopy(modJar, libDestDir, false);
    	}
    	// next, update the installation data with module
    	h.execute("INSERT INTO installation (compid, comptype, groupid, artifactid, versionstr, checksum, graph, updated) " +
    	          "VALUES (nextval('installation_seq'), ?, ?, ?, ?, ?, ?, ?)",
    			  0, groupId, artifactId, version, checksum(modJar), "self", new Timestamp(System.currentTimeMillis()));
    	// update this new component with ref_graph data
    	//updateReferenceGraph(h, groupId, artifactId, -1);
    	
    	// now process module resources
    	List<String> excludes = Arrays.asList(exclusions);
    	for (File file : baseDir.listFiles()) {
    		if (! excludes.contains(file.getName()) && file.isDirectory()) {
    			safeCopy(file, destFile, false);
    		}
    	}
    	
    	// if a WAR module, stop here - dependent jars need not be added to the classpath,
    	// since they will be used only in the container classpath
    	if ("war".equals(packaging)) {
    		System.out.println("done - WAR");
    		return;
    	}
    	
    	// Install dependent jars that aren't already there, updating their reference graph in any case
    	Component comp = getComponent(h, groupId, artifactId);
    	File libSrcDir = new File(baseDir, LIB_DIR);
    	for (List<String> cparts : components) {
    		String grpId = cparts.get(0);
    		String artId = cparts.get(1);
    		String vsn = cparts.get(3);
    		String status = cparts.get(5);
    		if ("count".equals(status)) {
    			// just update reference graph
    			updateReferenceGraph(h, grpId, artId, comp.getCompId());
    		} else if ("install".equals(status)) {
    			// copy jar to lib & add to installation table
    			File jarFile = new File(libSrcDir, artId + "-" + vsn + ".jar");
    			safeCopy(jarFile, libDestDir, false);
    	    	h.execute("INSERT INTO installation (compid, comptype, groupid, artifactid, versionstr, checksum, graph, updated) " +
    	    	          "VALUES (nextval('installation_seq'), ?, ?, ?, ?, ?, ?, ?)",
    	    			  1, grpId, artId, vsn, checksum(jarFile), String.valueOf(comp.getCompId()), new Timestamp(System.currentTimeMillis()));
    		}
    	}
    	System.out.println("Copied components");
    	loadDDL();
    	// special initialization in kernel module
    	if ("kernel".equals(module)) {
    		// create system-required groups   
    		Context ctx = null;
    		try {
    			ctx = new Context();
    			ctx.turnOffAuthorisationSystem();
    			Group anon = Group.create(ctx);
    			anon.setName("Anonymous");
    			anon.update();
    			
    			Group admin = Group.create(ctx);
    			admin.setName("Administrator");
    			admin.update();
    		} catch (Exception e) {
    			System.out.println("Exception: " + e.getMessage());
    		} finally {
    			if (ctx != null) {
    				try {
    					ctx.complete();
    				} catch (Exception e) {System.out.println("Complete Exception: " + e.getMessage());}
    			}
    		}
    	}
    	//initBrowse();
    	// now load registry data into DB
    	loadRegistries();
    	if ("kernel".equals(module)) {
    		initIndexes();
    	}
    }
    
    private void update(Handle h) throws BrowseException, IOException, SQLException, Exception {
    	checkState("jar".equals(packaging) || "war".equals(packaging),
 			       "Cannot update module: " + module + " not a dspace module");
    	checkState(dbInitialized(h), "No kernel module present - install one");
    	Component curComp = getComponent(h, groupId, artifactId);
    	checkState(curComp != null, "Module: '" + artifactId + "' is not installed - cannot update");
    	
    	String destPath = ConfigurationManager.getProperty("dspace.dir");
    	File destFile = new File(destPath);
    	File libDestDir = new File(destFile, LIB_DIR);
    	   	
    	if (curComp.getVersionStr().equals(version)) {
    		// if the version is the same, the *only* code change we permit
    		// is a difference in the module jar itself (if not a war module)
    		if ("jar".equals(packaging)) {
    			File modJar = getModuleArtifact();
    			String checksum = checksum(modJar);
    			if (! checksum.equals(curComp.getChecksum())) {
    				safeCopy(modJar, libDestDir, true);
    				// update DB to reflect this change
    				h.execute("UPDATE installation SET checksum = :csum, updated = :upd WHERE groupid = :gid AND artifactid = :aid",
    						  checksum, new Timestamp(System.currentTimeMillis()), groupId, artifactId);
    			}
    		}
    	} else {
    		// a version change, need to check everything
        	// Determine whether the update would create any classpath conflicts
        	List<List<String>> components = readDependencies();
        	for (List<String> cparts : components) {
        		// determine if this component needs to be checked
        		String grpId = cparts.get(0);
        		String artId = cparts.get(1);
        		String vsn = cparts.get(3);
        		String scope = cparts.get(4);
        		if ("compile".equals(scope) || artId.startsWith("dsm-")) {
        			// Query the deployed system for this component
        			Component depComp = getComponent(h, grpId, artId);
        			if (depComp != null) {
        				// version differences will only matter if other components also depend on this one
        				String graph = depComp.getGraph();
        				if (graph.indexOf("-") > 0 || ! graph.equals(String.valueOf(depComp.getCompId()))) {
        					// do versions conflict?
        					if (! version.equals(vsn)) {
        						throw new IOException("Module dependency: '" + artId + "' conflicts with an existing component");
        					} else {
        						cparts.add("ignore");
        					}
        				} else {
        					cparts.add("update");
        				}
        			} else {
        				if (artId.startsWith("dsm-")) {
        					throw new IOException("Module dependency: '" + artId + "' must be installed first");
        				} else {
        					cparts.add("install");
        				}
        			}
        		} else {
        			cparts.add("ignore");
        		}
        	}
        	
        	// Next step, remove any modules that are no longer needed
        	List<Component> allComps = h.createQuery("SELECT * FROM installation").
        		                       map(new BeanMapper<Component>(Component.class)).list();
        	for (Component comp: allComps) {
        		String graph = comp.getGraph();
        		// we only need to examine potentially orphaned components
        		// i.e. those only referenced by module being updated
        		if (comp.getCompId() != curComp.getCompId() && graph.equals(String.valueOf(curComp.getCompId()))) {
        			// OK, see it they are on current list of components
        			boolean found = false;
        			for (List<String> cparts : components) {
        				if (cparts.get(0).equals(comp.getCompId()) && cparts.get(1).equals(comp.getArtifactId())) {
        					found = true;
        					break;
        				}
        			}
        			if (! found) {
        				// we can remove this component - it will no longer be needed
        				System.out.println("Removing orphaned component: " + comp.getArtifactId());
        				File delFile = new File(libDestDir, comp.getArtifactId() + "=" + comp.getVersionStr() + ".jar");
        				delFile.delete();
        				h.execute("DELETE FROM installation WHERE compid = :cid", comp.getCompId());
        			}
        		}
        	}
        	
        	// reinstall module jar and update its Component entry
    		File modJar = getModuleArtifact();
    		String checksum = checksum(modJar);
        	if ("jar".equals(packaging)) {
        		safeCopy(modJar, libDestDir, false);
        	}
			// update DB to reflect this change
			h.execute("UPDATE installation SET versionstr = :vsn, checksum = :csum, updated = :upd WHERE groupid = :gid AND artifactid = :aid",
					  version, checksum, new Timestamp(System.currentTimeMillis()), groupId, artifactId);
    	
        	// Install dependent jars that aren't already there, updating their reference graph in any case
        	File libSrcDir = new File(baseDir, LIB_DIR);
        	for (List<String> cparts : components) {
        		String grpId = cparts.get(0);
        		String artId = cparts.get(1);
        		String vsn = cparts.get(3);
        		String status = cparts.get(5);
        		File jarFile = new File(libSrcDir, artId + "-" + vsn + ".jar");
        		if ("update".equals(status)) {
        			// just update version of component
        			safeCopy(jarFile, libDestDir, true);
        			updateVersion(h, grpId, artId, version, checksum(jarFile));
        		} else if ("install".equals(status)) {
        			// copy jar to lib & add to installation table
        			safeCopy(jarFile, libDestDir, false);
        			h.execute("INSERT INTO installation (compid, comptype, groupid, artifactid, versionstr, checksum, graph, updated) " +
        					"VALUES (nextval('installation_seq'), ?, ?, ?, ?, ?, ?, ?)",
        					1, grpId, artId, vsn, checksum(jarFile), String.valueOf(curComp.getCompId()), new Timestamp(System.currentTimeMillis()));
        		}
        	}
        	System.out.println("Copied components");
    	}
    	
    	// in either case, update the resource data
    	List<String> excludes = Arrays.asList(exclusions);
       	for (File file : baseDir.listFiles()) {
    		if (! excludes.contains(file.getName()) && file.isDirectory()) {
    			safeCopy(file, destFile, true);
    		}
    	}
    }
    
    private boolean dbInitialized(Handle h) throws SQLException {
   	 	DatabaseMetaData md = h.getConnection().getMetaData();
   	 	return md.getTables(null, null, "installation", null).next();
    }
    
    private Component getComponent(Handle h, String grpId, String artId) throws SQLException {
    	return h.createQuery("SELECT * FROM installation WHERE groupid = :gid AND artifactid = :aid").
    		   bind("gid", grpId).bind("aid", artId).
    		   map(new BeanMapper<Component>(Component.class)).first();
    }
    
    private void updateReferenceGraph(Handle h, String grpId, String artId, int node) throws SQLException {
    	Component comp = getComponent(h, grpId, artId);
    	if (comp != null) {
    		h.execute("UPDATE installation SET graph = :rc WHERE groupid = :gid AND artifactid = :aid",
    				  comp.getGraph() + "-" + String.valueOf(node), grpId, artId);
    	}
    }
    
    private void updateVersion(Handle h, String grpId, String artId, String version, String checksum) throws SQLException {
		h.execute("UPDATE installation SET versionstr = :vsn, checksum = :csum, updated = :upd WHERE groupid = :gid AND artifactid = :aid",
				  version, checksum, new Timestamp(System.currentTimeMillis()), grpId, artId);
    }
    
    private File getModuleArtifact() throws IOException {
    	File libSrcDir = new File(baseDir, LIB_DIR);
    	File buildDir = new File(baseDir, BUILD_DIR);
    	File modArt = null;
    	// prefer the locally built (customized) version if present
    	if (buildDir.isDirectory()) {
    		modArt = new File(buildDir, artifactId + "-" + version + "." + packaging);
    	}
    	// use packaged version otherwise
    	if (modArt == null || ! modArt.exists()) {
    		if (libSrcDir.isDirectory()) {
    			modArt = new File(libSrcDir, artifactId + "-" + version + "." + packaging);
    		}
    	}
    	return modArt;
    }
    
    private void safeCopy(File src, File dest, boolean overwrite) throws IOException {
    	File destFile = new File(dest, src.getName());
    	if (src.isDirectory()) {
    		if (! overwrite) {
    			destFile.mkdir();
    		}
    		for (File file : src.listFiles()) {
    			safeCopy(file, destFile, overwrite);
    		}
    	} else if (overwrite) {
    		if (src.exists() && destFile.exists()) {
    			Files.copy(src, destFile);
    		} else {
    			System.out.println("Error - expected file to be present: " + destFile.getName());
    		}
    	} else {
    		if (src.exists() && ! destFile.exists()) {
    			Files.copy(src, destFile);
    		} else {
    			System.out.println("Error - expected file to be unique: " + destFile.getName());
    		}
    	}
    }
    
    private void readPOM() throws IOException, ParserConfigurationException,
    	SAXException, XPathExpressionException {
    	File pomFile = new File(baseDir, POM_FILE);
    	if (pomFile.exists()) {
    		DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
    		Document pomDoc = builder.parse(pomFile);
    		// All we currently need is module coordinates
    		XPath xpath = XPathFactory.newInstance().newXPath();   		
    		groupId = findPomValue(pomDoc, xpath.compile("/project/groupId/text()"));
    		artifactId = findPomValue(pomDoc, xpath.compile("/project/artifactId/text()"));
    		version = findPomValue(pomDoc, xpath.compile("/project/version/text()"));
    		// packaging defaults to jar so this call may fail
    		packaging = findPomValue(pomDoc, xpath.compile("/project/packaging/text()"));
    		if (packaging == null) {
    			packaging = "jar";
    		}
    	} else {
    		System.out.println("No POM file at expected location: " + pomFile.getAbsolutePath());
    	}
    	// did we read from POM successfully?
    	checkState(artifactId != null, "Bad POM file - unable to process");
    }
    
    private String findPomValue(Document doc, XPathExpression expr) throws XPathExpressionException {
	    NodeList nodes = (NodeList)expr.evaluate(doc, XPathConstants.NODESET);
	    if (nodes.getLength() > 0) {
	    	return nodes.item(0).getNodeValue();
	    }
	    return null;
    }
    
    private List<List<String>> readDependencies() throws IOException {
    	List<List<String>> compList = new ArrayList<List<String>>();
    	File comps = new File(baseDir, DEPS_FILE);
    	BufferedReader reader = new BufferedReader(new FileReader(comps));
    	String lineIn = null;
    	while ((lineIn = reader.readLine()) != null) {
    		lineIn = lineIn.trim();
    		if (lineIn.length() > 0 && ! lineIn.startsWith("The")) {
    			List<String> parts = new ArrayList(Arrays.asList(lineIn.split(":")));
    			compList.add(parts);
    		}
    	}
    	reader.close();
    	return compList;
    }
    
    private void cleanDB(Handle h) throws BrowseException, IOException, SQLException {
    	clearBrowse();
    	unloadDDL();
    }
    
    private void initDB(Handle h) throws IOException, SQLException {
   	 	checkState(ConfigurationManager.getProperty("db.password") != null, "no database password defined");
   	 	// the only module-independent data to be 'bootstrapped' is in the installation
   	 	// table itself: everything else will be loaded when a module is installed
   	 	h.execute("CREATE SEQUENCE installation_seq");
   	 	h.execute("CREATE TABLE installation (" +
   	 	          "compid INTEGER PRIMARY KEY, " +
   	 			  "comptype INTEGER, " +
   	 	          "groupid VARCHAR, " +
   	 			  "artifactid VARCHAR, " +
   	 	          "versionstr VARCHAR, " +
   	 			  "checksum VARCHAR, " +
   	 			  "graph VARCHAR, " +
   	 	          "updated TIMESTAMP)");
    	//"CREATE USER dspace WITH CREATEDB PASSWORD '" + dbPassword + "';");
    	//"CREATE DATABASE dspace ENCODING UTF8 OWNER dspace;");
    }
    
    private void loadDDL() throws IOException, SQLException {
    	 String dbName = ConfigurationManager.getProperty("db.name");
    	 checkState(dbName != null, "no database name defined");

    	 String path = baseDir.getAbsolutePath() + File.separator + DDL_DIR + File.separator + dbName;
    	 File ddlFile = new File(path, DDL_UPFILE);
    	 // not all modules have DDLs
    	 //checkState(ddlFile.exists(), "no DDL file present");
    	 if (ddlFile.exists()) {
    		 DatabaseManager.loadSql(new FileReader(ddlFile.getCanonicalPath()));
    	 }
    }
    
    private void unloadDDL() throws IOException, SQLException {
   	    String dbName = ConfigurationManager.getProperty("db.name");
   	    checkState(dbName != null, "no database name defined");

    	String path = baseDir.getAbsolutePath() + File.separator + DDL_DIR + File.separator + dbName;
    	File ddlFile = new File(path, DDL_DOWNFILE);
    	//checkState(ddlFile.exists(), "no DDL file present");
    	if (ddlFile.exists()) {
    		DatabaseManager.loadSql(new FileReader(ddlFile.getCanonicalPath()));
    	}
    }
    
    private void initBrowse() throws BrowseException, SQLException {
    	 IndexBrowse browse = new IndexBrowse();
         browse.setRebuild(true);
         browse.setExecute(true);
         browse.initBrowse();
    }
    
    private void clearBrowse() throws BrowseException, SQLException {
        IndexBrowse browse = new IndexBrowse();
        browse.setDelete(true);
        browse.setExecute(true);
        browse.clearDatabase();
    }
    
    private void loadRegistries() throws Exception {
   	 	File regDir = new File(baseDir, REG_DIR);
   	 	if (regDir.isDirectory()) {
   	 		Context context = null;
   	 		try {
   	 			context = new Context();
   	 			context.turnOffAuthorisationSystem();
   	 			for (File regFile : regDir.listFiles()) {
   	 				RegistryLoader.loadRegistryFile(context, regFile.getAbsolutePath());
   	 			}
   	 		} finally {
   	 			if (context != null) {
   	 				context.complete();
   	 			}
   	 		}
   	 	}
    }
    
    private void initIndexes() throws Exception {
	 	Context context = null;
   	 	try {
   	 		context = new Context();
   	 		indexer = new DSIndexer();
   	 		indexer.createIndex(context);
	 	} finally {
   	 		if (context != null) {
   	 			context.complete();
   	 		}
   	 	}
    }
    
    private String checksum(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        FileInputStream fis = new FileInputStream(file);
        byte[] dataBytes = new byte[1024];
 
        int nread = 0; 
        while ((nread = fis.read(dataBytes)) != -1) {
          md.update(dataBytes, 0, nread);
        };
        fis.close();
        byte[] mdbytes = md.digest();
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < mdbytes.length; i++) {
          sb.append(Integer.toString((mdbytes[i] & 0xff) + 0x100, 16).substring(1));
        }
        return sb.toString();
    }
}
