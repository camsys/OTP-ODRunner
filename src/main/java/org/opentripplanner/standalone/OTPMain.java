package org.opentripplanner.standalone;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;

import org.opentripplanner.api.resource.PlannerResource;
import org.opentripplanner.datastore.DataSource;
import org.opentripplanner.graph_builder.GraphBuilder;
import org.opentripplanner.model.TransitMode;
import org.opentripplanner.model.plan.Itinerary;
import org.opentripplanner.model.plan.TripPlan;
import org.opentripplanner.routing.RoutingService;
import org.opentripplanner.routing.api.request.RequestModes;
import org.opentripplanner.routing.api.request.RoutingRequest;
import org.opentripplanner.routing.api.request.StreetMode;
import org.opentripplanner.routing.api.response.RoutingResponse;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.SerializedGraphObject;
import org.opentripplanner.standalone.config.CommandLineParameters;
import org.opentripplanner.standalone.configure.OTPAppConstruction;
import org.opentripplanner.standalone.server.Router;
import org.opentripplanner.util.OtpAppException;
import org.opentripplanner.visualizer.GraphVisualizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import static org.opentripplanner.model.projectinfo.OtpProjectInfo.projectInfo;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Scanner;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * This is the main entry point to OpenTripPlanner. It allows both building graphs and starting up
 * an OTP server depending on command line options. OTPMain is a concrete class making it possible
 * to construct one with custom CommandLineParameters and use its graph builder construction method
 * from web services or scripts, not just from the
 * static main function below.
 */
public class OTPMain {

    private static final Logger LOG = LoggerFactory.getLogger(OTPMain.class);

    static {

        // Disable HSQLDB reconfiguration of Java Unified Logging (j.u.l)
        //noinspection AccessOfSystemProperties
        System.setProperty("hsqldb.reconfig_logging", "false");

        // Remove existing handlers attached to the j.u.l root logger
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        // Bridge j.u.l (used by Jersey) to the SLF4J root logger, so all logging goes through the same API
        SLF4JBridgeHandler.install();
    }

    /**
     * ENTRY POINT: This is the main method that is called when running otp.jar from the command line.
     */
    public static void main(String[] args) {
        try {
            CommandLineParameters params = parseAndValidateCmdLine(args);
            OtpStartupInfo.logInfo();
            startOTPServer(params);
        }
        catch (OtpAppException ae) {
            LOG.error(ae.getMessage());
            System.exit(100);
        }
        catch (Exception e) {
            LOG.error("An uncaught error occurred inside OTP: {}", e.getLocalizedMessage(), e);
            System.exit(-1);
        }
    }

    /**
     * Parse and validate command line parameters. If the arguments is invalid the
     * method uses {@code System.exit()} to exit the application.
     */
    private static CommandLineParameters parseAndValidateCmdLine(String[] args) {
        CommandLineParameters params = new CommandLineParameters();
        try {
            // It is tempting to use JCommander's command syntax: http://jcommander.org/#_more_complex_syntaxes_commands
            // But this seems to lead to confusing switch ordering and more difficult subsequent use of the
            // parsed commands, since there will be three separate objects.
            JCommander jc = JCommander.newBuilder().addObject(params).args(args).build();
            if (params.version) {
                System.out.println("OpenTripPlanner " + projectInfo().getVersionString());
                System.exit(0);
            }
            if (params.serializationVersionId) {
                System.out.println(projectInfo().getOtpSerializationVersionId());
                System.exit(0);
            }
            if (params.help) {
                System.out.println("OpenTripPlanner " + projectInfo().getVersionString());
                jc.setProgramName("java -Xmx4G -jar otp.jar");
                jc.usage();
                System.exit(0);
            }
            params.inferAndValidate();
        } catch (ParameterException pex) {
            LOG.error("Parameter error: {}", pex.getMessage());
            System.exit(1);
        }
        return params;
    }

    /**
     * All startup logic is in an instance method instead of the static main method so it is possible to build graphs
     * from web services or scripts, not just from the command line. If options cause an OTP API server to start up,
     * this method will return when the web server shuts down.
     *
     * @throws RuntimeException if an error occurs while loading the graph.
     */
    private static void startOTPServer(CommandLineParameters params) {
        LOG.info(
            "Searching for configuration and input files in {}",
            params.getBaseDirectory().getAbsolutePath()
        );

        Graph graph = null;
        OTPAppConstruction app = new OTPAppConstruction(params);

        // Validate data sources, command line arguments and config before loading and
        // processing input data to fail early
        app.validateConfigAndDataSources();

        /* Load graph from disk if one is not present from build. */
        if (params.doLoadGraph() || params.doLoadStreetGraph()) {
            DataSource inputGraph = params.doLoadGraph()
                    ? app.store().getGraph()
                    : app.store().getStreetGraph();
            SerializedGraphObject obj = SerializedGraphObject.load(inputGraph);
            graph = obj.graph;
            app.config().updateConfigFromSerializedGraph(obj.buildConfig, obj.routerConfig);
        }

        /* Start graph builder if requested. */
        if (params.doBuildStreet() || params.doBuildTransit()) {
            // Abort building a graph if the file can not be saved
            SerializedGraphObject.verifyTheOutputGraphIsWritableIfDataSourceExist(
                    app.graphOutputDataSource()
            );

            GraphBuilder graphBuilder = app.createGraphBuilder(graph);
            if (graphBuilder != null) {
                graphBuilder.run();
                // Hand off the graph to the server as the default graph
                graph = graphBuilder.getGraph();
            } else {
                throw new IllegalStateException("An error occurred while building the graph.");
            }
            // Store graph and config used to build it, also store router-config for easy deployment
            // with using the embedded router config.
            new SerializedGraphObject(graph, app.config().buildConfig(), app.config().routerConfig())
                    .save(app.graphOutputDataSource());
        }

        if(graph == null) {
            LOG.error("Nothing to do, no graph loaded or build. Exiting.");
            System.exit(101);
        }

        if(!params.doServe()) {
            LOG.info("Done building graph. Exiting.");
            return;
        }

        // Index graph for travel search
        graph.index();

        // publishing the config version info make it available to the APIs
        app.setOtpConfigVersionsOnServerInfo();

        Router router = new Router(graph, app.config().routerConfig());
        router.startup();

        /* Start visualizer if requested. */
        if (params.visualize) {
            router.graphVisualizer = new GraphVisualizer(router);
            router.graphVisualizer.run();
        }
/*
        if (params.doServe()) {
            GrizzlyServer grizzlyServer = app.createGrizzlyServer(router);
            // Loop to restart server on uncaught fatal exceptions.
            while (true) {
                try {
                    grizzlyServer.run();
                    return;
                } catch (Throwable throwable) {
                    LOG.error(
                        "An uncaught error occurred inside OTP. Restarting server. Error was: {}",
                        ThrowableUtils.detailedString(throwable)
                    );
                }
            }
        }
*/        
        
        try {
	        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<Runnable>(50);
	        ThreadPoolExecutor threadPool = new ThreadPoolExecutor(25, 25, 0L, TimeUnit.MILLISECONDS, queue);

	        // we need our RejectedExecutionHandler to block if the queue is full
	        threadPool.setRejectedExecutionHandler(new RejectedExecutionHandler() {
	            @Override
	            public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
	                try {
	                     // this will block the producer until there's room in the queue
	                     executor.getQueue().put(r);
	                } catch (InterruptedException e) {
	                     throw new RejectedExecutionException("Unexpected InterruptedException", e);
	                }
	            }
	        });

        	LOG.info("Reading input CSV...");
        	
        	List<String> header = null;

        	File outputFile = new File(params.getBaseDirectory().getAbsolutePath() + "/output.csv");
            FileWriter outputStream = new FileWriter(outputFile);
            
	        try (Scanner scanner = new Scanner(new File(params.getBaseDirectory().getAbsolutePath() + "/OD_TEST.csv"));) {	        	
	        	while (scanner.hasNextLine()) {
	        		if(header == null) {
	        			String headerAsText = scanner.nextLine();
	        			header = Arrays.asList(headerAsText.split(","));
	        			header = header.stream().map(i -> i.trim().replace("\"", "")).collect(Collectors.toList());
	        			
	                	outputStream.write(headerAsText 
	                			+ ", totalWalkMinutes, totalDriveOrTransitMinutes, totalWaitMinutes, totalDriveOrTransitDistanceMeters\n");

	        			continue;
	        		}
	        		
	        		threadPool.submit(new ProcessCSVRecord(getRecordFromLine(scanner.nextLine(), header), router, outputStream));
	            }
	        }
        
        	LOG.info("All records forked to a worker, requesting shutdown of thread pool");	        
	        threadPool.shutdown();

        	LOG.info("Waiting for threads to finish...");
	        threadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);       

        	LOG.info("Complete. Syncing results to disk.");        	
        	outputStream.close();        	
        } catch (Exception e) {
        	LOG.error("Exception thrown: " + e);
        }
    }

	private static HashMap<String, String> getRecordFromLine(String line, List<String> header) {
		HashMap<String, String> values = new HashMap<String, String>();

        int i = 0;
	    for(String element : line.split(",")) {
	    	element = element.trim().replace("\"", "");

	    	String name = element.trim().replace("\"", "");
	        if(header != null) name = header.get(i);
	        	
	        values.put(name, element);
	        i++;
	    }

	    return values;
	}
    
    private static class ProcessCSVRecord extends PlannerResource implements Runnable {
    	
    	private final RoutingService routingService;

    	private final Router router;

    	private final HashMap<String, String> values;
    	
    	private final FileWriter outputStream;
    	
    	public ProcessCSVRecord(HashMap<String, String> values, Router router, FileWriter outputStream) {
    		this.values = values;
    		this.router = router;
    		this.routingService = new RoutingService(router.graph);
    		this.outputStream = outputStream;
    	}

        public void run() {
            RoutingRequest request;

            long start = System.currentTimeMillis();
            
            try {
				request = router.defaultRoutingRequest.clone();
				
				request.setFromString("(" + values.get("FY") + "," + values.get("FX") + ")");
				request.setToString("(" + values.get("TY") + "," + values.get("TX") + ")");
				request.ignoreRealtimeUpdates = true;
				request.setDateTime("02-23-2021", "16:30:00", TimeZone.getTimeZone("CDT"));

				boolean isTransitRequest = false;
				if(values.get("MODE").equals("CAR")) {
					request.modes = new RequestModes(StreetMode.WALK, StreetMode.WALK, StreetMode.CAR, new HashSet<>(
				            Arrays.asList()));
					
					isTransitRequest = false;

				} else if(values.get("MODE").equals("TRANSIT")) {
					request.modes = new RequestModes(StreetMode.WALK, StreetMode.WALK, StreetMode.WALK, new HashSet<>(
				            Arrays.asList(TransitMode.values())));

					isTransitRequest = true;

				} else {
					LOG.error("Unknown mode, skipping record: " + values.get("MODE"));
					return;
				}
				
				if(values.get("MAXWALKDISTANCE") != null)
					request.setMaxWalkDistance(Double.parseDouble(values.get("MAXWALKDISTANCE")));
				
				request.setNumItineraries(1);


				RoutingResponse res = null;
				if(isTransitRequest) 
					res = routingService.routeTransitOnly(request, router);
				else
					res = routingService.routeCarOnly(request, router);
					
	            if (!res.getRoutingErrors().isEmpty()) {
	            	LOG.error("TP threw error, skipping record: " + res.getRoutingErrors().get(0).code);
	            	return;
	            }

	            TripPlan tripPlan = res.getTripPlan();
	            
	            if(tripPlan == null || tripPlan.itineraries.isEmpty()) {
					LOG.error("Trip plan query returned no results; skipping: " + String.join(",",  values.values()));
	            	return;
	            }
	            
	            Itinerary itin = tripPlan.itineraries.get(0);	            

	            double totalWalkMinutes = 
	            		(isTransitRequest == false) ? 0 : (itin.nonTransitTimeSeconds / 60);	            

	            double totalDriveOrTransitMinutes = 
	            		(isTransitRequest == false) ? (itin.nonTransitTimeSeconds / 60) : (itin.transitTimeSeconds / 60);

	            double totalDriveOrTransitDistance = 
	            		(isTransitRequest == false) ? itin.nonTransitDistanceMeters : itin.nonTransitDistanceMeters;
	            
	            double totalWaitMinutes = itin.waitingTimeSeconds / 60;
	            		            
	            synchronized(outputStream) {
	            	outputStream.write(String.join(",",  values.values()) 
	            			+ "," + totalWalkMinutes + "," + totalDriveOrTransitMinutes + "," + totalWaitMinutes + "," + totalDriveOrTransitDistance + "\n");	          
	            }
	            
	            System.out.println("Execution time = " + (System.currentTimeMillis() - start) + "ms");

            } catch (Exception e) {
				e.printStackTrace();
			}
        }
    }
}
