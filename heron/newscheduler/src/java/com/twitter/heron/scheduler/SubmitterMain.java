package com.twitter.heron.scheduler;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import com.twitter.heron.api.generated.TopologyAPI;
import com.twitter.heron.common.basics.FileUtils;
import com.twitter.heron.spi.common.ClusterConfig;
import com.twitter.heron.spi.common.ClusterDefaults;
import com.twitter.heron.spi.common.Config;
import com.twitter.heron.spi.common.Context;
import com.twitter.heron.spi.common.Keys;
import com.twitter.heron.spi.packing.IPacking;
import com.twitter.heron.spi.scheduler.ILauncher;
import com.twitter.heron.spi.statemgr.IStateManager;
import com.twitter.heron.spi.statemgr.SchedulerStateManagerAdaptor;
import com.twitter.heron.spi.uploader.IUploader;
import com.twitter.heron.spi.utils.NetworkUtils;
import com.twitter.heron.spi.utils.TopologyUtils;

/**
 * Calls Uploader to upload topology package, and Launcher to launch Scheduler.
 */
public class SubmitterMain {
  private static final Logger LOG = Logger.getLogger(SubmitterMain.class.getName());

  /**
   * Load the topology config
   *
   * @param topologyPackage, tar ball containing user submitted jar/tar, defn and config
   * @param topologyJarFile, name of the user submitted topology jar/tar file
   * @param topology, proto in memory version of topology definition
   * @return config, the topology config
   */
  protected static Config topologyConfigs(
      String topologyPackage, String topologyJarFile, String topologyDefnFile,
      TopologyAPI.Topology topology) {

    String pkgType = FileUtils.isOriginalPackageJar(
        FileUtils.getBaseName(topologyJarFile)) ? "jar" : "tar";

    Config config = Config.newBuilder()
        .put(Keys.topologyId(), topology.getId())
        .put(Keys.topologyName(), topology.getName())
        .put(Keys.topologyDefinitionFile(), topologyDefnFile)
        .put(Keys.topologyPackageFile(), topologyPackage)
        .put(Keys.topologyJarFile(), topologyJarFile)
        .put(Keys.topologyPackageType(), pkgType)
        .build();

    return config;
  }

  /**
   * Load the defaults config
   *
   * @param heronHome, directory of heron home
   * @param configPath, directory containing the config
   * <p/>
   * return config, the defaults config
   */
  protected static Config defaultConfigs(String heronHome, String configPath) {
    Config config = Config.newBuilder()
        .putAll(ClusterDefaults.getDefaults())
        .putAll(ClusterDefaults.getSandboxDefaults())
        .putAll(ClusterConfig.loadConfig(heronHome, configPath))
        .build();
    return config;
  }

  /**
   * Load the config parameters from the command line
   *
   * @param cluster, name of the cluster
   * @param role, user role
   * @param environ, user provided environment/tag
   * @return config, the command line config
   */
  protected static Config commandLineConfigs(String cluster, String role, String environ) {
    Config config = Config.newBuilder()
        .put(Keys.cluster(), cluster)
        .put(Keys.role(), role)
        .put(Keys.environ(), environ)
        .build();
    return config;
  }

  // Print usage options
  private static void usage(Options options) {
    HelpFormatter formatter = new HelpFormatter();
    formatter.printHelp("SubmitterMain", options);
  }

  // Construct all required command line options
  private static Options constructOptions() {
    Options options = new Options();

    Option cluster = Option.builder("c")
        .desc("Cluster name in which the topology needs to run on")
        .longOpt("cluster")
        .hasArgs()
        .argName("cluster")
        .required()
        .build();

    Option role = Option.builder("r")
        .desc("Role under which the topology needs to run")
        .longOpt("role")
        .hasArgs()
        .argName("role")
        .required()
        .build();

    Option environment = Option.builder("e")
        .desc("Environment under which the topology needs to run")
        .longOpt("environment")
        .hasArgs()
        .argName("environment")
        .required()
        .build();

    Option heronHome = Option.builder("d")
        .desc("Directory where heron is installed")
        .longOpt("heron_home")
        .hasArgs()
        .argName("heron home dir")
        .required()
        .build();

    Option configFile = Option.builder("p")
        .desc("Path of the config files")
        .longOpt("config_path")
        .hasArgs()
        .argName("config path")
        .required()
        .build();

    // TODO: Need to figure out the exact format
    Option configOverrides = Option.builder("o")
        .desc("Command line config overrides")
        .longOpt("config_overrides")
        .hasArgs()
        .argName("config overrides")
        .build();

    Option topologyPackage = Option.builder("y")
        .desc("tar ball containing user submitted jar/tar, defn and config")
        .longOpt("topology_package")
        .hasArgs()
        .argName("topology package")
        .required()
        .build();

    Option topologyDefn = Option.builder("f")
        .desc("serialized file containing Topology protobuf")
        .longOpt("topology_defn")
        .hasArgs()
        .argName("topology definition")
        .required()
        .build();

    Option topologyJar = Option.builder("j")
        .desc("user heron topology jar")
        .longOpt("topology_jar")
        .hasArgs()
        .argName("topology jar")
        .required()
        .build();

    Option verbose = Option.builder("v")
        .desc("Enable debug logs")
        .longOpt("verbose")
        .build();

    options.addOption(cluster);
    options.addOption(role);
    options.addOption(environment);
    options.addOption(heronHome);
    options.addOption(configFile);
    options.addOption(configOverrides);
    options.addOption(topologyPackage);
    options.addOption(topologyDefn);
    options.addOption(topologyJar);
    options.addOption(verbose);

    return options;
  }

  // construct command line help options
  private static Options constructHelpOptions() {
    Options options = new Options();
    Option help = Option.builder("h")
        .desc("List all options and their description")
        .longOpt("help")
        .build();

    options.addOption(help);
    return options;
  }

  // Initialize logger
  public static void initLog(Level level) {

    // update all root handlers to the required level
    Logger globalLogger = Logger.getLogger("");
    Handler[] handlers = globalLogger.getHandlers();
    for(Handler handler : handlers) {
      handler.setLevel(level);
    }

    globalLogger.setLevel(level);
  }

  public static void main(String[] args) throws
      ClassNotFoundException, InstantiationException,
      IllegalAccessException, IOException, ParseException {

    Options options = constructOptions();
    Options helpOptions = constructHelpOptions();
    CommandLineParser parser = new DefaultParser();
    // parse the help options first.
    CommandLine cmd = parser.parse(helpOptions, args, true);
    ;

    if (cmd.hasOption("h")) {
      usage(options);
      return;
    }

    try {
      // Now parse the required options
      cmd = parser.parse(options, args);
    } catch (ParseException e) {
      LOG.severe("Error parsing command line options: " + e.getMessage());
      usage(options);
      System.exit(1);
    }

    Level logLevel = Level.INFO;
    if(cmd.hasOption("v")) {
      logLevel = Level.ALL;
    }

    // init log
    initLog(logLevel);

    String cluster = cmd.getOptionValue("cluster");;
    String role = cmd.getOptionValue("role");;
    String environ = cmd.getOptionValue("environment");;
    String heronHome = cmd.getOptionValue("heron_home");;
    String configPath = cmd.getOptionValue("config_path");;
    String configOverrideEncoded = cmd.getOptionValue("config_overrides");;
    String topologyPackage = cmd.getOptionValue("topology_package");;
    String topologyDefnFile = cmd.getOptionValue("topology_defn");;
    String topologyJarFile = cmd.getOptionValue("topology_jar");;

    // load the topology definition into topology proto
    TopologyAPI.Topology topology = TopologyUtils.getTopology(topologyDefnFile);

    // first load the defaults, then the config from files to override it
    // next add config parameters from the command line
    // load the topology configs
    // TODO (Karthik) override any parameters from the command line

    // build the final config by expanding all the variables
    Config config = Config.expand(
        Config.newBuilder()
            .putAll(defaultConfigs(heronHome, configPath))
            .putAll(commandLineConfigs(cluster, role, environ))
            .putAll(topologyConfigs(
                topologyPackage, topologyJarFile, topologyDefnFile, topology))
            .build());

    LOG.fine("Static config loaded successfully ");
    LOG.fine(config.toString());

    // 1. Do prepare work
    // create an instance of state manager
    String statemgrClass = Context.stateManagerClass(config);
    IStateManager statemgr = (IStateManager) Class.forName(statemgrClass).newInstance();

    // Create an instance of the launcher class
    String launcherClass = Context.launcherClass(config);
    ILauncher launcher = (ILauncher) Class.forName(launcherClass).newInstance();

    // Create an instance of the packing class
    String packingClass = Context.packingClass(config);
    IPacking packing = (IPacking) Class.forName(packingClass).newInstance();

    // create an instance of the uploader class
    String uploaderClass = Context.uploaderClass(config);
    IUploader uploader = (IUploader) Class.forName(uploaderClass).newInstance();

    // Local variable for convenient access
    String topologyName = topology.getName();

    boolean isSuccessful = false;
    // Put it in a try block so that we can always clean resources
    try {
      // initialize the state manager
      statemgr.initialize(config);

      boolean isValid = validateSubmit(statemgr, topologyName);

      // 2. Try to submit topology if valid
      if (isValid) {
        // invoke method to submit the topology
        LOG.log(Level.INFO, "Topology {0} to be submitted", topologyName);

        // Firstly, try to upload necessary packages
        URI packageURI = uploadPackage(config, uploader);
        if (packageURI == null) {
          LOG.severe("Failed to upload package.");
        } else {
          // Secondly, try to submit a topology
          isSuccessful = submitTopology(config, topology, statemgr, launcher, packing, packageURI);
        }
      }
    } finally {
      // 3. Do post work basing on the result
      if (!isSuccessful) {
        // Undo if failed to submit
        uploader.undo();
        launcher.undo();
      }

      // 4. Do generic cleaning
      // close the uploader
      uploader.close();
      // close the state manager
      statemgr.close();
    }

    // Log the result and exit
    if (!isSuccessful) {
      LOG.log(Level.SEVERE, "Failed to submit topology {0}. Exiting", topologyName);

      System.exit(1);
    } else {
      LOG.log(Level.INFO, "Topology {0} submitted successfully", topologyName);

      System.exit(0);
    }
  }

  public static boolean validateSubmit(IStateManager statemgr, String topologyName) {
    // Check whether the topology has already been running
    Boolean isTopologyRunning =
        NetworkUtils.awaitResult(statemgr.isTopologyRunning(topologyName), 5, TimeUnit.SECONDS);

    if (isTopologyRunning != null && isTopologyRunning.equals(Boolean.TRUE)) {
      LOG.severe("Topology already exists");
      return false;
    }

    return true;
  }

  public static URI uploadPackage(Config config, IUploader uploader) {
    // initialize the uploader
    uploader.initialize(config);

    // upload the topology package to the storage
    URI uploaderRet = uploader.uploadPackage();

    return uploaderRet;
  }

  public static boolean submitTopology(Config config, TopologyAPI.Topology topology,
                                       IStateManager statemgr, ILauncher launcher,
                                       IPacking packing, URI packageURI)
      throws ClassNotFoundException, InstantiationException, IllegalAccessException, IOException {
    // build the runtime config
    Config runtime = Config.newBuilder()
        .put(Keys.topologyId(), topology.getId())
        .put(Keys.topologyName(), topology.getName())
        .put(Keys.topologyDefinition(), topology)
        .put(Keys.schedulerStateManagerAdaptor(), new SchedulerStateManagerAdaptor(statemgr))
        .put(Keys.topologyPackageUri(), packageURI)
        .put(Keys.launcherClassInstance(), launcher)
        .put(Keys.packingClassInstance(), packing)
        .build();

    // using launch runner, launch the topology
    LaunchRunner launchRunner = new LaunchRunner(config, runtime);
    boolean result = launchRunner.call();

    // if failed, undo the uploaded package
    if (!result) {
      LOG.severe("Failed to launch topology. Attempting to roll back upload.");
      return false;
    }
    return true;
  }
}
